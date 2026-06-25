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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // Single source of truth for play state. Service + Activity both observe.
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    var currentRate = 1.0f
        private set
    private var currentVolume = 1.0f
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentStream = AudioManager.STREAM_MUSIC
    private var originalStreamVolume = -1
    var onLinesChanged: ((List<TextLine>) -> Unit)? = null

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

        // Retry engine switch when espeak-ng-data becomes ready (catches the race where
        // espeak-ng-data wasn't ready at voice-selection time)
        CoroutineScope(Dispatchers.Main).launch {
            voiceManager?.espeakDataReady?.collect { ready ->
                if (ready && voiceManager?.selectedVoice?.value != null
                    && currentEngine !is PiperTtsEngine) {
                    Log.d("TtsController", "espeak-ng-data now ready — retrying Piper engine switch")
                    switchToPiperEngine()
                }
            }
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                val idx = utteranceId?.toIntOrNull() ?: return
                onMain {
                    currentIndex = idx + 1
                    if (_isPlaying.value && currentIndex < sentences.size) {
                        onLinesChanged?.invoke(sentenceLines.getOrElse(currentIndex) { emptyList() })
                        speakAt(currentIndex)
                    } else {
                        _isPlaying.value = false
                        onLinesChanged?.invoke(emptyList())
                    }
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {}

            override fun onError(utteranceId: String?) {
                val idx = utteranceId?.toIntOrNull() ?: return
                // Samsung TTS fires onError for QUEUE_FLUSH-interrupted utterances.
                // Ignore if this isn't the utterance we're currently expecting.
                if (idx != speakingId) return
                Log.e("TTS", "Error at utterance $utteranceId")
                onMain {
                    _isPlaying.value = false
                    onLinesChanged?.invoke(emptyList())
                }
            }
        })
    }

    private fun switchToPiperEngine() {
        if (piperEngine == null) return
        val voice = voiceManager?.selectedVoice?.value
        if (voice == null) {
            return
        }
        val result = piperEngine.loadVoice(voice)
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: ""
            Log.e("TtsController", "Failed to load Piper voice ${voice.name}: $msg")
            if (msg.contains("espeak-ng-data") || msg.contains("tokens")) {
                CoroutineScope(Dispatchers.IO).launch {
                    voiceManager?.ensureEspeakNgData()
                }
            }
            switchToAndroidEngine()
            return
        }
        // Only switch engine AFTER successful loadVoice
        currentEngine = piperEngine
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
        _isPlaying.value = true
        onLinesChanged?.invoke(sentenceLines.getOrElse(0) { emptyList() })
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
        _isPlaying.value = false
        currentEngine.stop()
        restoreStreamVolume()
    }

    fun resume() {
        if (currentIndex < sentences.size) {
            _isPlaying.value = true
            onLinesChanged?.invoke(sentenceLines.getOrElse(currentIndex) { emptyList() })
            speakAt(currentIndex)
        }
    }

    fun canResume() = !_isPlaying.value && sentences.isNotEmpty() && currentIndex < sentences.size

    fun primeFrom(lines: List<TextLine>) {
        val fullText = lines.joinToString(" ") { it.text }
        sentences = splitSentences(fullText)
        sentenceLines = mapSentencesToLines(fullText, sentences, lines)
        currentIndex = 0
        _isPlaying.value = false
        onLinesChanged?.invoke(sentenceLines.getOrElse(0) { emptyList() })
    }

    fun setRate(rate: Float) {
        currentRate = rate
        if (_isPlaying.value && currentIndex < sentences.size) {
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
                "de_DE" -> Locale.GERMAN
                "en" -> Locale.ENGLISH
                "en-US" -> Locale("en", "US")
                "en_GB" -> Locale("en", "GB")
                "en_US" -> Locale("en", "US")
                "es" -> Locale("es")
                "es_ES" -> Locale("es", "ES")
                "es_MX" -> Locale("es", "MX")
                "es_AR" -> Locale("es", "AR")
                "nl" -> Locale.forLanguageTag("nl")
                "nl_NL" -> Locale.forLanguageTag("nl")
                "nl_BE" -> Locale("nl", "BE")
                "it" -> Locale.ITALIAN
                "it_IT" -> Locale.ITALIAN
                "fr" -> Locale.FRENCH
                "fr_FR" -> Locale.FRENCH
                "pt" -> Locale.forLanguageTag("pt")
                "pt_PT" -> Locale("pt", "PT")
                "pt_BR" -> Locale("pt", "BR")
                "sv" -> Locale.forLanguageTag("sv")
                "sv_SE" -> Locale.forLanguageTag("sv")
                "ja" -> Locale.JAPANESE
                "ja_JP" -> Locale.JAPANESE
                "ar_JO" -> Locale("ar", "JO")
                "bg_BG" -> Locale("bg", "BG")
                "ca_ES" -> Locale("ca", "ES")
                "cs_CZ" -> Locale("cs", "CZ")
                "cy_GB" -> Locale("cy", "GB")
                "da_DK" -> Locale("da", "DK")
                "el_GR" -> Locale("el", "GR")
                "fa_IR" -> Locale("fa", "IR")
                "fi_FI" -> Locale("fi", "FI")
                "hi_IN" -> Locale("hi", "IN")
                "hu_HU" -> Locale("hu", "HU")
                "id_ID" -> Locale("id", "ID")
                "is_IS" -> Locale("is", "IS")
                "ka_GE" -> Locale("ka", "GE")
                "kk_KZ" -> Locale("kk", "KZ")
                "ku_TR" -> Locale("ku", "TR")
                "lb_LU" -> Locale("lb", "LU")
                "lv_LV" -> Locale("lv", "LV")
                "ml_IN" -> Locale("ml", "IN")
                "ne_NP" -> Locale("ne", "NP")
                "no_NO" -> Locale("no", "NO")
                "pl_PL" -> Locale("pl", "PL")
                "ro_RO" -> Locale("ro", "RO")
                "ru_RU" -> Locale("ru", "RU")
                "sk_SK" -> Locale("sk", "SK")
                "sl_SI" -> Locale("sl", "SI")
                "sq_AL" -> Locale("sq", "AL")
                "sr_RS" -> Locale("sr", "RS")
                "sw_CD" -> Locale("sw", "CD")
                "te_IN" -> Locale("te", "IN")
                "tr_TR" -> Locale("tr", "TR")
                "uk_UA" -> Locale("uk", "UA")
                "ur_PK" -> Locale("ur", "PK")
                "vi_VN" -> Locale("vi", "VN")
                "zh_CN" -> Locale("zh", "CN")
                else -> Locale.forLanguageTag(lang.replace("_", "-")).takeIf { it.language.isNotEmpty() } ?: Locale.getDefault()
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
        if (currentVolume > 1.0f && _isPlaying.value) boostStream()
        if (currentEngine is AndroidTtsEngine) {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (speaker) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        piperEngine?.isSpeaker = speaker
        if (_isPlaying.value && currentIndex < sentences.size) { currentEngine.stop(); speakAt(currentIndex) }
    }

    fun setVolume(vol: Float) {
        currentVolume = vol.coerceIn(0.1f, 1.5f)
        if (currentVolume > 1.0f) {
            if (_isPlaying.value) boostStream()   // only boost when actually speaking
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
        if (_isPlaying.value && currentIndex < sentences.size) { currentEngine.stop(); speakAt(currentIndex) }
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
        if (_isPlaying.value) speakAtWithFallback(idx)
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
        currentEngine.speak(
            text = sentences[idx],
            params = params,
            utteranceId = idx.toString(),
            onDone = if (isPiper) {
                {
                    currentIndex = idx + 1
                    if (_isPlaying.value && currentIndex < sentences.size) {
                        onLinesChanged?.invoke(sentenceLines.getOrElse(currentIndex) { emptyList() })
                        speakAt(currentIndex)
                    } else {
                        _isPlaying.value = false
                        onLinesChanged?.invoke(emptyList())
                    }
                }
            } else null
        )
    }

    private fun splitSentences(text: String): List<String> =
        // Primary split at sentence boundaries, then cap length to limit ORT tensor size.
        // Long sentences → huge VITS intermediate tensors → OOM. Keep chunks ≤ 150 chars.
        text.split(Regex("(?<=[.!?])\\s+"))
            .filter { it.isNotBlank() }
            .flatMap { chopSentence(it, 150) }

    private fun chopSentence(sentence: String, maxChars: Int): List<String> {
        if (sentence.length <= maxChars) return listOf(sentence)
        // Try secondary splits at ,;: first
        val secondary = sentence.split(Regex("(?<=[,;:])\\s+")).filter { it.isNotBlank() }
        if (secondary.size > 1) {
            // Merge short adjacent chunks, split long ones further
            val result = mutableListOf<String>()
            var current = StringBuilder()
            for (chunk in secondary) {
                if (current.isEmpty()) {
                    current.append(chunk)
                } else if (current.length + 1 + chunk.length <= maxChars) {
                    current.append(" ").append(chunk)
                } else {
                    result.add(current.toString())
                    current = StringBuilder(chunk)
                }
            }
            if (current.isNotEmpty()) result.add(current.toString())
            return result.flatMap { chopSentence(it, maxChars) }
        }
        // No secondary boundaries: split at word boundary near maxChars
        val words = sentence.split(" ")
        val result = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            if (current.isEmpty()) {
                current.append(word)
            } else if (current.length + 1 + word.length <= maxChars) {
                current.append(" ").append(word)
            } else {
                result.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

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
