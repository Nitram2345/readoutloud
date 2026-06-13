package com.readoutloud

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

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

    override val isReady: Boolean get() = currentVoice != null

    fun loadVoice(voice: Voice): Result<Unit> {
        return try {
            val voiceFile = File(context.filesDir, "voices/${voice.name}.onnx")
            if (!voiceFile.exists()) {
                return Result.failure(IllegalStateException("Voice file not found: ${voice.name}"))
            }

            model = ortEnv.createSession(voiceFile.absolutePath, OrtSession.SessionOptions())
            currentVoice = voice
            Log.i("PiperTtsEngine", "Loaded voice: ${voice.name} (${voice.language})")
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

        val session = model ?: run {
            Log.e("PiperTtsEngine", "Model not loaded")
            return
        }

        currentAudioSessionId = params.audioSessionId
        isPlaying = true

        try {
            // Piper model expects: input_ids (long tensor)
            // Simple byte-based tokenization (real Piper uses BPE tokenizer)
            val inputBytes = text.toByteArray(Charsets.UTF_8)
            val inputIds = LongArray(inputBytes.size + 2) { i ->
                when {
                    i == 0 -> 0L  // BOS token
                    i == inputBytes.size + 1 -> 2L  // EOS token
                    else -> inputBytes[i - 1].toLong() and 0xFFL
                }
            }

            // Create input tensor - use simple createTensor with data
            val inputTensor = OnnxTensor.createTensor(ortEnv, inputIds)

            val inputs = mapOf("input_ids" to inputTensor)

            val outputs = session.run(inputs)

            // Extract audio from output - get first output tensor
            val outputValue = outputs[0]
            val audioArray: FloatArray? = when (outputValue) {
                is OnnxTensor -> {
                    // ONNX Runtime API for extracting tensor data varies by version
                    // Full implementation requires version-specific API calls
                    Log.w("PiperTtsEngine", "ONNX tensor extraction not fully implemented")
                    null
                }
                else -> {
                    Log.e("PiperTtsEngine", "Unexpected output type: ${outputValue::class.java}")
                    null
                }
            }

            inputTensor.close()
            outputs.close()

            if (audioArray != null && audioArray.size > 0) {
                playAudio(audioArray, voice.sampleRate, params)
            } else {
                Log.e("PiperTtsEngine", "No audio output from model - ONNX integration incomplete")
            }
        } catch (e: Exception) {
            Log.e("PiperTtsEngine", "Inference failed", e)
        } finally {
            isPlaying = false
        }
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
        model?.close()
        model = null
        currentVoice = null
    }
}
