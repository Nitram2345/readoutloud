package com.readoutloud

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * PiperTtsEngine: ONNX Runtime-based TTS engine for Piper voices.
 *
 * CURRENT STATUS: Model loading works, inference pending.
 *
 * The ONNX Runtime Android API requires version-specific tensor handling.
 * This implementation provides the structure and model loading.
 * Full inference will be added once the exact tensor API is finalized.
 */
class PiperTtsEngine(
    private val context: Context,
    private val voiceManager: VoiceManager
) : TtsEngine {

    private val ortEnv = OrtEnvironment.getEnvironment()
    private var model: OrtSession? = null
    private var currentVoice: Voice? = null
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var currentAudioSessionId = 0

    override val isReady: Boolean get() = model != null

    /**
     * Load a Piper voice model from storage.
     * This functionality is fully working.
     */
    fun loadVoice(voice: Voice): Result<Unit> {
        return try {
            val voiceFile = File(context.filesDir, "voices/${voice.name}.onnx")
            if (!voiceFile.exists()) {
                Log.e("PiperTtsEngine", "Voice file not found: ${voiceFile.absolutePath}")
                return Result.failure(IllegalStateException("Voice file not found: ${voice.name}"))
            }

            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setIntraOpNumThreads(2)
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

            model = ortEnv.createSession(voiceFile.absolutePath, sessionOptions)
            currentVoice = voice

            Log.i("PiperTtsEngine", "Loaded Piper voice: ${voice.name} (${voice.language}) @ ${voice.sampleRate}Hz")
            Result.success(Unit)
        } catch (e: OrtException) {
            Log.e("PiperTtsEngine", "ONNX Error loading voice ${voice.name}: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e("PiperTtsEngine", "Error loading voice ${voice.name}: ${e.message}")
            Result.failure(e)
        }
    }

    override fun speak(text: String, params: SpeakParams) {
        // ONNX Inference is not yet implemented
        // This will be added in a future update
        Log.w("PiperTtsEngine", "ONNX Inference not yet implemented. Voice: ${currentVoice?.name}")

        // For now, throw an exception so TtsController can fall back to Android TTS
        throw IllegalStateException("Piper ONNX Inference not yet implemented. Using Android TTS as fallback.")
    }

    private fun playAudio(audioData: FloatArray, sampleRate: Int, params: SpeakParams) {
        // Audio playback logic (called after successful inference)
        audioTrack?.let {
            if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                it.stop()
            }
            it.release()
        }

        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(encoding)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBufferSize.coerceAtLeast(4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(currentAudioSessionId)
            .build()

        audioTrack?.play()

        val volume = params.volume.coerceIn(0f, 1f)
        val shortArray = ShortArray(audioData.size) { i ->
            val sample = (audioData[i] * volume * 32767f).toInt().coerceIn(-32768, 32767)
            sample.toShort()
        }

        audioTrack?.write(shortArray, 0, shortArray.size)

        Thread.sleep((audioData.size.toFloat() / sampleRate * 1000).toLong() + 100)

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    override fun stop() {
        audioTrack?.let {
            if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    it.stop()
                } catch (e: Exception) {
                    Log.w("PiperTtsEngine", "Error stopping audio: ${e.message}")
                }
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
        model?.close()
        model = null
        currentVoice = null
    }
}
