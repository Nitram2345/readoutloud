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

class TtsController(
    private val tts: TextToSpeech,
    private val audioSessionId: Int = 0,
    private val audioManager: AudioManager? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private fun onMain(block: () -> Unit) = mainHandler.post(block)

    private var sentences: List<String> = emptyList()
    private var sentenceLines: List<List<TextLine>> = emptyList()
    private var currentIndex = 0
    var isPlaying = false
        private set
    private var currentRate = 1.0f
    private var currentVolume = 1.0f
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentStream = AudioManager.STREAM_MUSIC
    private var originalStreamVolume = -1
    var onPlayStateChanged: ((Boolean) -> Unit)? = null
    var onLinesChanged: ((List<TextLine>) -> Unit)? = null

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

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

            override fun onError(utteranceId: String?) {
                Log.e("TTS", "Error at utterance $utteranceId")
                onMain {
                    isPlaying = false
                    onLinesChanged?.invoke(emptyList())
                    onPlayStateChanged?.invoke(false)
                }
            }
        })
    }

    fun startFrom(lines: List<TextLine>) {
        val fullText = lines.joinToString(" ") { it.text }
        sentences = splitSentences(fullText)
        sentenceLines = mapSentencesToLines(fullText, sentences, lines)
        currentIndex = 0
        isPlaying = true
        onLinesChanged?.invoke(sentenceLines.getOrElse(0) { emptyList() })
        onPlayStateChanged?.invoke(true)
        speakAt(0)
    }

    fun pause() {
        isPlaying = false
        tts.stop()
        onPlayStateChanged?.invoke(false)
    }

    fun resume() {
        if (currentIndex < sentences.size) {
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
        tts.setSpeechRate(rate)
        if (isPlaying && currentIndex < sentences.size) {
            tts.stop()
            speakAt(currentIndex)
        }
    }

    fun setLanguage(lang: String) {
        val locale = when (lang) {
            "de" -> Locale.GERMAN
            "en" -> Locale.ENGLISH
            "nl" -> Locale("nl")
            "it" -> Locale.ITALIAN
            "fr" -> Locale.FRENCH
            "pt" -> Locale("pt")
            "sv" -> Locale("sv")
            else -> Locale.getDefault()
        }
        tts.setLanguage(locale)
    }

    fun setAudioOutput(speaker: Boolean) {
        val newStream = if (speaker) AudioManager.STREAM_MUSIC else AudioManager.STREAM_VOICE_CALL
        if (currentVolume > 1.0f && originalStreamVolume >= 0) {
            audioManager?.setStreamVolume(currentStream, originalStreamVolume, 0)
            originalStreamVolume = -1
        }
        currentStream = newStream
        if (currentVolume > 1.0f) boostStream()
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (speaker) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        if (isPlaying && currentIndex < sentences.size) { tts.stop(); speakAt(currentIndex) }
    }

    fun setVolume(vol: Float) {
        currentVolume = vol.coerceIn(0.1f, 1.5f)
        if (currentVolume > 1.0f) {
            boostStream()
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
        if (isPlaying && currentIndex < sentences.size) { tts.stop(); speakAt(currentIndex) }
    }

    private fun boostStream() {
        audioManager?.let { am ->
            val max = am.getStreamMaxVolume(currentStream)
            if (originalStreamVolume < 0) originalStreamVolume = am.getStreamVolume(currentStream)
            am.setStreamVolume(currentStream, max, 0)
        }
    }

    fun release() {
        audioManager?.let { am ->
            if (originalStreamVolume >= 0) {
                try { am.setStreamVolume(currentStream, originalStreamVolume, 0) } catch (_: Exception) {}
                originalStreamVolume = -1
            }
        }
        loudnessEnhancer?.release()
        loudnessEnhancer = null
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
        tts.stop()
        onLinesChanged?.invoke(sentenceLines.getOrElse(idx) { emptyList() })
        if (isPlaying) speakAt(idx)
    }

    private fun speakAt(idx: Int) {
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, minOf(currentVolume, 1.0f))
            if (audioSessionId != 0)
                putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, audioSessionId)
        }
        tts.speak(sentences[idx], TextToSpeech.QUEUE_FLUSH, params, idx.toString())
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
