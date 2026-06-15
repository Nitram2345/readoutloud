package com.readoutloud

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlin.math.log10
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TtsController(
    private val tts: TextToSpeech,
    private val audioSessionId: Int = 0,
    private val audioManager: AudioManager? = null,
    private val voiceManager: VoiceManager? = null,
    private val piperEngine: PiperTtsEngine? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private fun onMain(block: () -> Unit) = mainHandler.post(block)

    // Engine management
    private val androidEngine = AndroidTtsEngine(tts)
    private var currentEngine: TtsEngine = androidEngine

    private var sentences: List<String> = emptyList()
    private var sentenceLines: List<List<TextLine>> = emptyList()
    private var currentIndex = 0
    @Volatile private var speakingId = -1
    var isPlaying = false
        private set
    var currentRate = 1.0f
        private set
    private var currentVolume = 1.0f
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentStream = AudioManager.STREAM_MUSIC
    private var originalStreamVolume = -1
    var onPlayStateChanged: ((Boolean) -> Unit)? = null
    var onLinesChanged: ((List<TextLine>) -> Unit)? = null
    var onSpeakStart: (() -> Unit)? = null    // fires when TTS audio actually begins
    var onSpeakStop: (() -> Unit)? = null     // fires when TTS audio actually stops
    @Volatile private var speakStartNotified = false
    @Volatile private var speakStopNotified = false

    init {
        // Listen for voice changes to switch engines
        CoroutineScope(Dispatchers.Main).launch {
            voiceManager?.selectedVoice?.collect { voice ->
                if (voice != null) {
                    switchToPiperEngine()
                } else {
                    switchToAndroidEngine()
                }
            }
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (!speakStartNotified) {
                    speakStartNotified = true
                    onMain { onSpeakStart?.invoke() }
                }
            }

            override fun onDone(utteranceId: String?) {
                val idx = utteranceId?.toIntOrNull() ?: return
                onMain {
                    currentIndex = idx + 1
                    if (isPlaying && currentIndex < sentences.size) {
                        onLinesChanged?.invoke(sentenceLines.getOrElse(currentIndex) { emptyList() })
                        speakAt(currentIndex)
                    } else {
                        isPlaying = false
                        onLinesChanged?.invoke(emptyList())
                        onPlayStateChanged?.invoke(false)
                    }
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (interrupted && !speakStopNotified) {
                    speakStopNotified = true
                    onMain { onSpeakStop?.invoke() }
                }
            }

            override fun onError(utteranceId: String?) {
                val idx = utteranceId?.toIntOrNull() ?: return
                // Samsung TTS fires onError for QUEUE_FLUSH-interrupted utterances.
                // Ignore if this isn't the utterance we're currently expecting.
                if (idx != speakingId) return
                Log.e("TTS", "Error at utterance $utteranceId")
                onMain {
                    isPlaying = false
                    onLinesChanged?.invoke(emptyList())
                    onPlayStateChanged?.invoke(false)
                }
            }
        })
    }

    private fun switchToPiperEngine() {
        if (piperEngine != null) {
            val voice = voiceManager?.selectedVoice?.value
            if (voice != null) {
                val result = piperEngine.loadVoice(voice)
                if (result.isFailure) {
                    Log.e("TtsController", "Failed to load Piper voice ${voice.name}: ${result.exceptionOrNull()?.message}")
                    switchToAndroidEngine()
                    return
                }
            }
            currentEngine = piperEngine
            Log.d("TtsController", "Switched to Piper engine: ${voice?.name}")
        }
    }

    /**
     * Call this when Piper engine fails - switches back to Android TTS.
     */
    fun fallbackToAndroidTTS() {
        currentEngine = androidEngine
        Log.d("TtsController", "Fallback to Android TTS")
    }

    private fun switchToAndroidEngine() {
        currentEngine = androidEngine
        Log.d("TtsController", "Switched to Android engine")
    }

    private class AndroidTtsEngine(private val tts: TextToSpeech) : TtsEngine {
        override fun speak(text: String, params: SpeakParams, utteranceId: String, onDone: (() -> Unit)?) {
            val bundle = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, params.volume)
                if (params.audioSessionId != 0)
                    putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, params.audioSessionId)
            }
            tts.setSpeechRate(params.rate)
            // onDone ignored — completion handled by UtteranceProgressListener
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, utteranceId.ifEmpty { null })
        }

        override fun stop() {
            tts.stop()
        }

        override fun setLanguage(lang: String) {
            // Language handling is done externally via tts.setLanguage()
            Log.d("AndroidTtsEngine", "setLanguage called but handled externally")
        }

        override fun release() {
            // tts is managed externally by TtsController
            Log.d("AndroidTtsEngine", "release called but tts managed externally")
        }

        override val isReady: Boolean
            get() = true
    }

    fun startFrom(lines: List<TextLine>) {
        val fullText = lines.joinToString(" ") { it.text }
        sentences = splitSentences(fullText)
        sentenceLines = mapSentencesToLines(fullText, sentences, lines)
        currentIndex = 0
        speakStartNotified = false
        isPlaying = true
        onLinesChanged?.invoke(sentenceLines.getOrElse(0) { emptyList() })
        onPlayStateChanged?.invoke(true)
        speakAt(0)
    }

    /**
     * Speak with error handling - falls back to Android TTS on Piper failure.
     */
    private fun speakAtWithFallback(idx: Int) {
        try {
            speakAt(idx)
        } catch (e: Exception) {
            Log.e("TtsController", "Engine failed, falling back to Android TTS: ${e.message}")
            fallbackToAndroidTTS()
            // Retry with Android engine
            speakAt(idx)
        }
    }

    fun pause() {
        speakStopNotified = false
        isPlaying = false
        currentEngine.stop()
        restoreStreamVolume()
        onPlayStateChanged?.invoke(false)
    }

    fun resume() {
        if (currentIndex < sentences.size) {
            speakStartNotified = false
            isPlaying = true
            onLinesChanged?.invoke(sentenceLines.getOrElse(currentIndex) { emptyList() })
            onPlayStateChanged?.invoke(true)
            speakAt(currentIndex)
        }
    }

    fun canResume() = !isPlaying && sentences.isNotEmpty() && currentIndex < sentences.size

    fun primeFrom(lines: List<TextLine>) {
        val fullText = lines.joinToString(" ") { it.text }
        sentences = splitSentences(fullText)
        sentenceLines = mapSentencesToLines(fullText, sentences, lines)
        currentIndex = 0
        isPlaying = false
        onLinesChanged?.invoke(sentenceLines.getOrElse(0) { emptyList() })
    }

    fun setRate(rate: Float) {
        currentRate = rate
        if (isPlaying && currentIndex < sentences.size) {
            currentEngine.stop()
            speakAt(currentIndex)
        }
    }

    fun setLanguage(lang: String) {
        currentEngine.setLanguage(lang)
        // For Android engine, also set the locale
        if (currentEngine is AndroidTtsEngine) {
            val locale = when (lang) {
                "de" -> Locale.GERMAN
                "en" -> Locale.ENGLISH
                "en-US" -> Locale("en", "US")
                "es" -> Locale("es")
                "nl" -> Locale.forLanguageTag("nl")
                "it" -> Locale.ITALIAN
                "fr" -> Locale.FRENCH
                "pt" -> Locale.forLanguageTag("pt")
                "sv" -> Locale.forLanguageTag("sv")
                "ja" -> Locale.JAPANESE
                else -> Locale.getDefault()
            }
            tts.setLanguage(locale)
        }
    }

    fun setAudioOutput(speaker: Boolean) {
        val newStream = if (speaker) AudioManager.STREAM_MUSIC else AudioManager.STREAM_VOICE_CALL
        if (currentVolume > 1.0f && originalStreamVolume >= 0) {
            audioManager?.setStreamVolume(currentStream, originalStreamVolume, 0)
            originalStreamVolume = -1
        }
        currentStream = newStream
        if (currentVolume > 1.0f && isPlaying) boostStream()
        // Only Android TTS engine needs audio attributes configuration
        if (currentEngine is AndroidTtsEngine) {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (speaker) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        if (isPlaying && currentIndex < sentences.size) { currentEngine.stop(); speakAt(currentIndex) }
    }

    fun setVolume(vol: Float) {
        currentVolume = vol.coerceIn(0.1f, 1.5f)
        if (currentVolume > 1.0f) {
            if (isPlaying) boostStream()   // only boost when actually speaking
            val gainMb = (20.0 * log10(currentVolume.toDouble()) * 100).toInt()
            try {
                if (loudnessEnhancer == null && audioSessionId != 0)
                    loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                loudnessEnhancer?.setTargetGain(gainMb)
                loudnessEnhancer?.enabled = true
            } catch (e: Exception) {
                Log.w("TTS", "LoudnessEnhancer failed: ${e.message}")
            }
        } else {
            audioManager?.let { am ->
                if (originalStreamVolume >= 0) {
                    am.setStreamVolume(currentStream, originalStreamVolume, 0)
                    originalStreamVolume = -1
                }
            }
            loudnessEnhancer?.enabled = false
        }
        if (isPlaying && currentIndex < sentences.size) { currentEngine.stop(); speakAt(currentIndex) }
    }

    private fun boostStream() {
        audioManager?.let { am ->
            val max = am.getStreamMaxVolume(currentStream)
            if (originalStreamVolume < 0) originalStreamVolume = am.getStreamVolume(currentStream)
            am.setStreamVolume(currentStream, max, 0)
        }
    }

    private fun restoreStreamVolume() {
        audioManager?.let { am ->
            if (originalStreamVolume >= 0) {
                try { am.setStreamVolume(currentStream, originalStreamVolume, 0) } catch (_: Exception) {}
                originalStreamVolume = -1
            }
        }
    }

    fun release() {
        restoreStreamVolume()
        audioManager?.let { am ->
            if (originalStreamVolume >= 0) {  // belt-and-suspenders
                try { am.setStreamVolume(currentStream, originalStreamVolume, 0) } catch (_: Exception) {}
                originalStreamVolume = -1
            }
        }
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        currentEngine.release()
    }

    fun skipForward(seconds: Int) = skip(seconds)
    fun skipBackward(seconds: Int) = skip(-seconds)

    private fun skip(seconds: Int) {
        if (sentences.isEmpty()) return
        val targetWords = (Math.abs(seconds) * 2.5f * currentRate).toInt().coerceAtLeast(1)
        var words = 0
        var idx = currentIndex
        if (seconds > 0) {
            while (idx < sentences.size - 1 && words < targetWords) {
                words += sentences[idx].split(Regex("\\s+")).size
                idx++
            }
        } else {
            idx = (idx - 1).coerceAtLeast(0)
            while (idx > 0 && words < targetWords) {
                words += sentences[idx].split(Regex("\\s+")).size
                idx--
            }
        }
        jumpTo(idx)
    }

    private fun jumpTo(idx: Int) {
        currentIndex = idx
        currentEngine.stop()
        onLinesChanged?.invoke(sentenceLines.getOrElse(idx) { emptyList() })
        if (isPlaying) speakAtWithFallback(idx)
    }

    private fun speakAt(idx: Int) {
        if (currentVolume > 1.0f) boostStream()
        speakingId = idx
        val params = SpeakParams(
            volume = minOf(currentVolume, 1.0f),
            rate = currentRate,
            audioSessionId = audioSessionId
        )
        val isPiper = currentEngine !is AndroidTtsEngine
        if (isPiper && !speakStartNotified) {
            speakStartNotified = true
            onMain { onSpeakStart?.invoke() }
        }
        currentEngine.speak(
            text = sentences[idx],
            params = params,
            utteranceId = idx.toString(),
            onDone = if (isPiper) {
                {
                    currentIndex = idx + 1
                    if (isPlaying && currentIndex < sentences.size) {
                        onLinesChanged?.invoke(sentenceLines.getOrElse(currentIndex) { emptyList() })
                        speakAt(currentIndex)
                    } else {
                        isPlaying = false
                        onLinesChanged?.invoke(emptyList())
                        onPlayStateChanged?.invoke(false)
                    }
                }
            } else null
        )
    }

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

    private fun mapSentencesToLines(
        fullText: String,
        sentences: List<String>,
        lines: List<TextLine>
    ): List<List<TextLine>> {
        var pos = 0
        val lineRanges = lines.map { line ->
            val start = pos
            val end = pos + line.text.length
            pos = end + 1
            Triple(line, start, end)
        }

        val result = mutableListOf<List<TextLine>>()
        var searchFrom = 0
        for (sentence in sentences) {
            val sentStart = fullText.indexOf(sentence, searchFrom)
            if (sentStart < 0) { result.add(emptyList()); continue }
            val sentEnd = sentStart + sentence.length
            searchFrom = sentStart + 1
            result.add(lineRanges.filter { (_, ls, le) -> le > sentStart && ls < sentEnd }.map { it.first })
        }
        return result
    }
}
