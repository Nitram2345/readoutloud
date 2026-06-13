package com.readoutloud

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages voice selection state, downloaded voices, and active downloads.
 * Supports parallel downloads and persists selection via AppSettings.
 */
class VoiceManager(
    private val context: Context,
    private val repository: VoiceRepository,
    private val settings: AppSettings
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _downloads = MutableStateFlow<Map<String, VoiceDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, VoiceDownload>> = _downloads.asStateFlow()

    private val _selectedVoice = MutableStateFlow<Voice?>(null)
    val selectedVoice: StateFlow<Voice?> = _selectedVoice.asStateFlow()

    private val _downloadedVoices = MutableStateFlow<List<Voice>>(emptyList())
    val downloadedVoices: StateFlow<List<Voice>> = _downloadedVoices.asStateFlow()

    private val activeDownloads = mutableMapOf<String, Job>()

    private val voicesDir: File
        get() = File(context.filesDir, "voices")

    init {
        // Load selected voice from settings
        val savedVoiceName = settings.selectedVoice
        if (savedVoiceName != null) {
            scope.launch {
                refreshDownloadedVoices()
                val voice = _downloadedVoices.value.find { it.name == savedVoiceName }
                if (voice != null) {
                    _selectedVoice.value = voice
                }
            }
        }
    }

    /**
     * Select a voice and persist the selection.
     */
    fun selectVoice(voice: Voice) {
        settings.selectedVoice = voice.name
        _selectedVoice.value = voice
    }

    /**
     * Download a voice with progress tracking.
     * Supports parallel downloads via coroutines.
     */
    fun downloadVoice(voice: Voice) {
        if (activeDownloads.containsKey(voice.name)) {
            return // Already downloading
        }

        val destination = File(voicesDir, "${voice.name}.onnx")

        val job = scope.launch(Dispatchers.IO) {
            val downloadJob = _downloads.value[voice.name]
            if (downloadJob != null) {
                _downloads.value = _downloads.value + (voice.name to downloadJob.copy(status = DownloadStatus.Downloading))
            } else {
                _downloads.value = _downloads.value + (voice.name to VoiceDownload(
                    voice = voice,
                    progress = 0f,
                    status = DownloadStatus.Downloading,
                    destination = destination.absolutePath
                ))
            }

            try {
                repository.downloadVoice(voice, destination) { progress ->
                    _downloads.value = _downloads.value + (voice.name to
                        _downloads.value[voice.name]!!.copy(progress = progress))
                }

                _downloads.value = _downloads.value + (voice.name to
                    _downloads.value[voice.name]!!.copy(status = DownloadStatus.Completed))

                withContext(Dispatchers.Main) {
                    refreshDownloadedVoices()
                }
            } catch (e: Exception) {
                _downloads.value = _downloads.value + (voice.name to
                    _downloads.value[voice.name]!!.copy(status = DownloadStatus.Failed))
            } finally {
                activeDownloads.remove(voice.name)
            }
        }

        activeDownloads[voice.name] = job
    }

    /**
     * Delete a voice file and update state.
     * Deselects the voice if it was the selected one.
     */
    fun deleteVoice(voice: Voice) {
        scope.launch(Dispatchers.IO) {
            repository.deleteVoice(voice, context)

            _downloads.value = _downloads.value - voice.name
            _downloadedVoices.value = _downloadedVoices.value - voice

            if (_selectedVoice.value?.name == voice.name) {
                settings.selectedVoice = null
                _selectedVoice.value = null
            }
        }
    }

    /**
     * Cancel an active download.
     */
    fun cancelDownload(voiceName: String) {
        val job = activeDownloads[voiceName]
        if (job != null) {
            job.cancel()
            activeDownloads.remove(voiceName)
            _downloads.value = _downloads.value - voiceName
        }
    }

    /**
     * Refresh the list of downloaded voices from storage.
     */
    suspend fun refreshDownloadedVoices() {
        val voices = repository.getDownloadedVoices(context)
        _downloadedVoices.value = voices
    }

    /**
     * Check if a voice is currently being downloaded.
     */
    fun isDownloading(voiceName: String): Boolean {
        return activeDownloads.containsKey(voiceName)
    }

    /**
     * Get download progress for a voice.
     */
    fun getDownloadProgress(voiceName: String): Float? {
        return _downloads.value[voiceName]?.progress
    }

    /**
     * Fetch available voices for a language from HuggingFace.
     */
    suspend fun fetchAvailableVoices(lang: String): List<Voice> {
        return repository.getVoicesForLanguage(lang)
    }
}
