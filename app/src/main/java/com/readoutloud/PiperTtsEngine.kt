package com.readoutloud

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PiperTtsEngine(
    private val context: Context,
    private val voiceManager: VoiceManager
) : TtsEngine {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tts: OfflineTts? = null
    private var currentVoice: Voice? = null
    private var audioTrack: AudioTrack? = null
    private var speakJob: Job? = null
    @Volatile private var stopped = false

    var isSpeaker: Boolean = true

    override val isReady: Boolean get() = tts != null

    fun loadVoice(voice: Voice): Result<Unit> {
        return try {
            val voiceFile = File(context.filesDir, "voices/${voice.name}.onnx")
            if (!voiceFile.exists()) {
                return Result.failure(IllegalStateException("Voice file not found: ${voice.name}"))
            }

            val espeakDataDir = File(context.filesDir, "espeak-ng-data")
            if (!espeakDataDir.exists() || espeakDataDir.list().isNullOrEmpty()) {
                return Result.failure(IllegalStateException(
                    "espeak-ng-data not ready yet — download still in progress"
                ))
            }

            tts?.release()

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = voiceFile.absolutePath,
                        lexicon = "",
                        tokens = "",
                        dataDir = espeakDataDir.absolutePath
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                maxNumSentences = 1
            )

            tts = OfflineTts(config = config)
            currentVoice = voice

            Log.i("PiperTtsEngine", "Loaded Piper voice: ${voice.name} (${voice.language})")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("PiperTtsEngine", "Error loading voice ${voice.name}: ${e.message}")
            Result.failure(e)
        }
    }

    override fun speak(
        text: String,
        params: SpeakParams,
        utteranceId: String,
        onDone: (() -> Unit)?
    ) {
        val engine = tts ?: run {
            Log.e("PiperTtsEngine", "speak() called but no voice loaded")
            onDone?.invoke()
            return
        }

        stopped = false
        speakJob?.cancel()
        speakJob = scope.launch {
            withContext(Dispatchers.Default) {
                val sampleRate = engine.sampleRate()
                val track = createAudioTrack(sampleRate, params)
                audioTrack = track
                track.play()

                try {
                    engine.generateWithConfigAndCallback(
                        text = text,
                        config = GenerationConfig(speed = params.rate, sid = 0),
                        callback = { samples ->
                            if (stopped) {
                                0
                            } else {
                                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                                1
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e("PiperTtsEngine", "Generation error: ${e.message}")
                }

                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    try { track.stop() } catch (_: Exception) {}
                }
                track.release()
                audioTrack = null
            }

            if (!stopped) {
                onDone?.invoke()
            }
        }
    }

    private fun createAudioTrack(sampleRate: Int, params: SpeakParams): AudioTrack {
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_FLOAT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
            .coerceAtLeast(4096)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(if (isSpeaker) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(encoding)
            .build()

        val builder = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (params.audioSessionId != 0) {
            builder.setSessionId(params.audioSessionId)
        }

        val track = builder.build()
        track.setVolume(params.volume.coerceIn(0f, 1f))
        return track
    }

    override fun stop() {
        stopped = true
        speakJob?.cancel()
        speakJob = null
        audioTrack?.let {
            try {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
            } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioTrack = null
    }

    override fun setLanguage(lang: String) {
        Log.d("PiperTtsEngine", "setLanguage ignored — language fixed at voice load time")
    }

    override fun release() {
        stop()
        scope.cancel()
        tts?.release()
        tts = null
        currentVoice = null
    }
}
