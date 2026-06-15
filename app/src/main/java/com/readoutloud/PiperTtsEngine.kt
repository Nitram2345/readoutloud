package com.readoutloud

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import org.json.JSONObject
import java.io.FileOutputStream
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
    @Volatile private var speakEpoch = 0

    var isSpeaker: Boolean = true

    override val isReady: Boolean get() = tts != null

    fun loadVoice(voice: Voice): Result<Unit> {
        return try {
            val voiceFile = File(context.filesDir, "voices/${voice.name}.onnx")
            if (!voiceFile.exists()) {
                return Result.failure(IllegalStateException("Voice file not found: ${voice.name}"))
            }

            val voiceJsonFile = File(voiceFile.parent, voiceFile.name + ".json")
            if (!voiceJsonFile.exists()) {
                return Result.failure(IllegalStateException("Voice config .onnx.json missing: ${voice.name}"))
            }

            // tokens.txt required by sherpa-onnx Validate(); generate from .onnx.json if missing
            val tokensFile = File(voiceFile.parent, "${voice.name}.tokens.txt")
            if (!tokensFile.exists()) {
                generateTokensTxt(voiceJsonFile, tokensFile)
            }
            if (!tokensFile.exists() || tokensFile.length() == 0L) {
                return Result.failure(IllegalStateException("tokens.txt missing for ${voice.name}"))
            }

            val espeakDataDir = File(context.filesDir, "espeak-ng-data")
            val espeakEntries = espeakDataDir.list()?.size ?: -1
            if (!espeakDataDir.exists() || espeakEntries == 0) {
                return Result.failure(IllegalStateException("espeak-ng-data not ready yet"))
            }

            // Ensure ONNX metadata is injected (idempotent: checks for existing entries first)
            ensureOnnxMetadata(voiceFile, voiceJsonFile)

            tts?.release()

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = voiceFile.absolutePath,
                        lexicon = "",
                        tokens = tokensFile.absolutePath,
                        dataDir = espeakDataDir.absolutePath
                    ),
                    numThreads = 1,
                    debug = false,
                    provider = "cpu"
                ),
                maxNumSentences = 1
            )

            tts = OfflineTts(config = config)
            currentVoice = voice

            Result.success(Unit)
        } catch (e: Exception) {
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

        val epoch = ++speakEpoch  // invalidate any in-flight blocking generate() call
        stopped = false
        speakJob?.cancel()
        speakJob = scope.launch {
            withContext(Dispatchers.Default) {
                try {
                    // generate() is a blocking native call — coroutine cancellation can't interrupt it.
                    // After it returns, check epoch to discard stale results (e.g. speed was changed).
                    val audio = engine.generate(text = text, sid = 0, speed = params.rate)
                    if (speakEpoch != epoch || stopped) return@withContext  // stale — discard

                    if (audio.samples.isNotEmpty()) {
                        val track = createAudioTrack(audio.sampleRate, params)
                        audioTrack = track
                        track.play()

                        val chunkSize = 4096
                        var offset = 0
                        while (offset < audio.samples.size && !stopped && speakEpoch == epoch) {
                            val end = minOf(offset + chunkSize, audio.samples.size)
                            track.write(audio.samples, offset, end - offset, AudioTrack.WRITE_BLOCKING)
                            offset = end
                        }

                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            try { track.stop() } catch (_: Exception) {}
                        }
                        track.release()
                        audioTrack = null
                    }
                } catch (e: Exception) {
                }
            }

            if (speakEpoch == epoch && !stopped) {
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
        speakEpoch++  // invalidate any blocking generate() still running
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

    /**
     * Inject sherpa-onnx required ONNX metadata if not already present.
     * Checks for voice-field protobuf marker to avoid double-injection.
     * ONNX protobuf allows safe appending of repeated fields.
     */
    private fun ensureOnnxMetadata(onnxFile: File, jsonFile: File) {
        try {
            // Quick check: scan last 512 bytes for voice-field marker — avoids full parse
            val size = onnxFile.length()
            val checkBytes = minOf(512L, size)
            val tail = ByteArray(checkBytes.toInt())
            onnxFile.inputStream().use { it.skip(size - checkBytes); it.read(tail) }
            // \\x0a\\x05voice = protobuf field1+len5+"voice" — present only if we injected voice field
            val voiceMarker = "\nvoice"
            if (tail.toString(Charsets.ISO_8859_1).contains(voiceMarker)) {
                return
            }

            val json = JSONObject(jsonFile.readText())
            val sampleRate = json.optJSONObject("audio")?.optInt("sample_rate", 22050)
                ?: json.optInt("sample_rate", 22050)
            val numSpeakers = json.optInt("num_speakers", 1)
            val espeakVoice = json.optJSONObject("espeak")?.optString("voice", "en") ?: "en"

            val metadata = mapOf(
                "sample_rate" to sampleRate.toString(),
                "n_speakers" to numSpeakers.toString(),
                "language" to espeakVoice,
                "voice" to espeakVoice,
                "comment" to "piper",
                "frontend" to "espeak"
            )
            FileOutputStream(onnxFile, true).use { out ->
                for ((key, value) in metadata) {
                    out.write(encodeMetadataProp(key, value))
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun encodeMetadataProp(key: String, value: String): ByteArray {
        val inner = encodeProtoStr(1, key) + encodeProtoStr(2, value)
        return encodeProtoLen(14, inner)
    }

    private fun encodeProtoStr(field: Int, v: String) = encodeProtoLen(field, v.toByteArray())

    private fun encodeProtoLen(field: Int, bytes: ByteArray): ByteArray {
        val tag = encodeVarint(((field shl 3) or 2).toLong())
        return tag + encodeVarint(bytes.size.toLong()) + bytes
    }

    private fun encodeVarint(v: Long): ByteArray {
        val r = mutableListOf<Byte>()
        var x = v
        while (x > 127L) { r.add(((x and 0x7F) or 0x80).toByte()); x = x ushr 7 }
        r.add((x and 0x7F).toByte())
        return r.toByteArray()
    }

    private fun generateTokensTxt(jsonFile: File, outputFile: File) {
        try {
            val phonemeIdMap = JSONObject(jsonFile.readText()).optJSONObject("phoneme_id_map") ?: return
            val sb = StringBuilder()
            phonemeIdMap.keys().forEach { phoneme ->
                val ids = phonemeIdMap.optJSONArray(phoneme) ?: return@forEach
                if (ids.length() == 0) return@forEach
                val id = ids.getInt(0)
                if (phoneme.codePointCount(0, phoneme.length) != 1) return@forEach
                if (phoneme == " ") sb.appendLine(id.toString()) else sb.appendLine("$phoneme $id")
            }
            outputFile.writeText(sb.toString())
        } catch (e: Exception) {
        }
    }

    fun releaseModel() {
        tts?.release()
        tts = null
        currentVoice = null
        Log.d("PiperTtsEngine", "Model released to free memory")
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
