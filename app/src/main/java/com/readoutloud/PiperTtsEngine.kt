package com.readoutloud

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.File

/**
 * PiperTtsEngine: Placeholder for ONNX-based Piper TTS integration.
 *
 * NOTE: Full ONNX Runtime integration requires:
 * 1. Correct ONNX Runtime API usage (version-specific)
 * 2. Proper Piper model input/output tensor handling
 * 3. Audio sample rate conversion if needed
 *
 * This stub provides the interface but logs that actual inference is not implemented.
 */
class PiperTtsEngine(
    private val context: Context,
    private val voiceManager: VoiceManager
) : TtsEngine {

    private var currentVoice: Voice? = null
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var currentAudioSessionId = 0

    override val isReady: Boolean get() = currentVoice != null

    /**
     * Load a Piper voice model from storage.
     * Full implementation requires ONNX Runtime session creation.
     */
    fun loadVoice(voice: Voice): Result<Unit> {
        return try {
            val voiceFile = File(context.filesDir, "voices/${voice.name}.onnx")
            if (!voiceFile.exists()) {
                return Result.failure(IllegalStateException("Voice file not found: ${voice.name}"))
            }

            // TODO: Create ONNX session with the model file
            // model = ortEnv.createSession(voiceFile.absolutePath, ...)

            currentVoice = voice
            Log.i("PiperTtsEngine", "Voice loaded (ONNX session not yet implemented): ${voice.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("PiperTtsEngine", "Failed to load voice: ${voice.name}", e)
            Result.failure(e)
        }
    }

    override fun speak(text: String, params: SpeakParams) {
        val voice = currentVoice ?: run {
            Log.e("PiperTtsEngine", "No voice loaded")
            return
        }

        currentAudioSessionId = params.audioSessionId
        isPlaying = true

        Log.w("PiperTtsEngine", "speak() called but ONNX inference not implemented yet. Text: $text")

        // TODO: Implement ONNX inference:
        // 1. Create input tensor from text (tokenized)
        // 2. Run model inference
        // 3. Extract audio float array from output tensor
        // 4. Play audio via AudioTrack

        // For now, just simulate completion
        isPlaying = false
    }

    private fun playAudio(audioData: FloatArray, sampleRate: Int, params: SpeakParams) {
        audioTrack?.let {
            if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                it.stop()
            }
            it.release()
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBufferSize.coerceAtLeast(4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(currentAudioSessionId)
            .build()

        audioTrack?.play()

        // Convert float to short (16-bit)
        val volume = params.volume.coerceIn(0f, 1f)
        val shortArray = ShortArray(audioData.size) { i ->
            val sample = (audioData[i] * volume * 32767f).toInt().coerceIn(-32768, 32767)
            sample.toShort()
        }

        audioTrack?.write(shortArray, 0, shortArray.size)

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    override fun stop() {
        audioTrack?.let {
            if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                it.stop()
            }
        }
        isPlaying = false
    }

    override fun setLanguage(lang: String) {
        Log.d("PiperTtsEngine", "setLanguage called but ignored (voice-specific)")
    }

    override fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        currentVoice = null
    }
}
