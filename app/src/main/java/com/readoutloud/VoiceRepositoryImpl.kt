package com.readoutloud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * Implementation of VoiceRepository for fetching and downloading Piper TTS voices from HuggingFace.
 */
class VoiceRepositoryImpl(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .eventListenerFactory { CallEventListener() }
        .build()
) : VoiceRepository {

    companion object {
        private const val VOICES_JSON_URL = "https://huggingface.co/rhasspy/piper-voices/raw/main/voices.json"
        private const val HUGGINGFACE_BASE_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main"
        private const val VOICES_DIR = "voices"
        private const val ONNX_EXTENSION = ".onnx"
        private const val DAT_EXTENSION = ".dat"

        // Initialize cap names for common voice file prefixes
        private val INIT_CAP_MAP = mapOf(
            "vorbis" to "Vorbis",
            "alsa" to "Alsa",
            "espeak" to "Espeak",
            "onnx" to "ONNX",
            "low" to "Low",
            "medium" to "Medium",
            "high" to "High"
        )
    }

    /**
     * Fetch available voices for a given language from HuggingFace.
     * JSON structure: { "voice_key": { "name", "language": { "code" }, "quality", "files": { ... } } }
     */
    override suspend fun getVoicesForLanguage(lang: String): List<Voice> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(VOICES_JSON_URL)
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext emptyList()
            }

            val jsonText = response.body?.string() ?: return@withContext emptyList()
            val voicesJson = JSONObject(jsonText)

            val normalizedLang = normalizeLanguageCode(lang)
            val filteredVoices = mutableListOf<Voice>()

            val voiceKeys = voicesJson.keys().asSequence().toList()
            for (voiceKey in voiceKeys) {
                val voiceData = voicesJson.getJSONObject(voiceKey)
                val languageObj = voiceData.optJSONObject("language")
                val languageCode = languageObj?.optString("code", "") ?: voiceData.optString("lang", "")

                // Filter by language code
                if (languageCode.equals(normalizedLang, ignoreCase = true) ||
                    languageCode.equals(normalizedLang.replace("_", "-"), ignoreCase = true) ||
                    languageCode.startsWith(normalizedLang.substringBefore("_"), ignoreCase = true) ||
                    languageCode.startsWith(normalizedLang.substringBefore("-"), ignoreCase = true)) {

                    val name = voiceData.optString("name", voiceKey)
                    val quality = voiceData.optString("quality", "unknown")
                    val numSpeakers = voiceData.optInt("num_speakers", 1)
                    val sampleRate = voiceData.optInt("sample_rate", 22050)

                    // Find the .onnx file in the files object
                    val files = voiceData.optJSONObject("files")
                    var onnxPath: String? = null
                    var sizeBytes: Long = 0

                    if (files != null) {
                        val fileKeys = files.keys().asSequence().toList()
                        for (fileKey in fileKeys) {
                            if (fileKey.endsWith(".onnx") && !fileKey.endsWith(".onnx.json")) {
                                onnxPath = fileKey
                                val fileInfo = files.optJSONObject(fileKey)
                                sizeBytes = fileInfo?.optLong("size_bytes", 0) ?: 0
                                break
                            }
                        }
                    }

                    if (onnxPath != null) {
                        val onnxUrl = "$HUGGINGFACE_BASE_URL/$onnxPath"
                        val sizeMb = sizeBytes / (1024.0 * 1024.0)
                        val formattedName = formatVoiceName(name)

                        filteredVoices.add(
                            Voice(
                                name = formattedName,
                                language = languageCode,
                                quality = quality,
                                sizeMb = sizeMb,
                                onnxUrl = onnxUrl,
                                sampleRate = sampleRate,
                                isDownloaded = false,
                                isSelected = false
                            )
                        )
                    }
                }
            }

            Log.d("VoiceRepository", "Fetched ${filteredVoices.size} voices for lang=$lang (normalized=$normalizedLang)")
            filteredVoices
        } catch (e: Exception) {
            Log.e("VoiceRepository", "Failed to fetch voices: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Download a voice file from HuggingFace with progress reporting.
     * Also downloads the companion .onnx.json config file (required by sherpa-onnx).
     */
    override suspend fun downloadVoice(
        voice: Voice,
        destination: File,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(voice.onnxUrl)
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Download failed: ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(IOException("Empty response body"))
            val contentLength = body.contentLength()

            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(destination)

            val buffer = ByteArray(8192)
            var totalBytesRead = 0L

            while (true) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break

                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead

                if (contentLength > 0) {
                    val progress = totalBytesRead.toFloat() / contentLength.toFloat()
                    onProgress(progress)
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            // Download companion JSON config (sherpa-onnx reads it automatically for Piper voices)
            val jsonDest = File(destination.parent, destination.name + ".json")
            val jsonResult = downloadFile(voice.onnxUrl + ".json", jsonDest)
            if (jsonResult.isFailure) {
                Log.w("VoiceRepository", "Could not download .onnx.json for ${voice.name}: ${jsonResult.exceptionOrNull()?.message}")
            }

            // Generate tokens.txt from phoneme_id_map in .onnx.json (required by sherpa-onnx Validate())
            if (jsonDest.exists()) {
                val tokensDest = File(destination.parent, destination.name.removeSuffix(".onnx") + ".tokens.txt")
                generateTokensTxt(jsonDest, tokensDest)
                // Inject sherpa-onnx required ONNX metadata into the model file.
                // HuggingFace Piper models store config in .onnx.json, not in ONNX metadata.
                // Sherpa-onnx calls _Exit(-1) if sample_rate/n_speakers/language/comment are missing.
                injectOnnxMetadata(destination, jsonDest)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Inject sherpa-onnx required metadata into a HuggingFace Piper ONNX model file.
     * Appends protobuf-encoded metadata_props entries to the end of the model.
     * Protobuf repeated fields can be safely appended; parsers merge them.
     *
     * Sherpa-onnx requires in ONNX model metadata: sample_rate, n_speakers,
     * language, comment (containing "piper"), frontend="espeak".
     * HuggingFace models store this in .onnx.json instead — so we inject it.
     */
    private fun injectOnnxMetadata(onnxFile: File, jsonFile: File) {
        try {
            val json = JSONObject(jsonFile.readText())
            val sampleRate = json.optJSONObject("audio")?.optInt("sample_rate", 22050)
                ?: json.optInt("sample_rate", 22050)
            val numSpeakers = json.optInt("num_speakers", 1)
            val espeakVoice = json.optJSONObject("espeak")?.optString("voice", "en") ?: "en"

            val metadata = mapOf(
                "sample_rate" to sampleRate.toString(),
                "n_speakers" to numSpeakers.toString(),
                "language" to espeakVoice,
                "comment" to "piper",
                "frontend" to "espeak"
            )

            FileOutputStream(onnxFile, true /* append */).use { out ->
                for ((key, value) in metadata) {
                    out.write(encodeOnnxMetadataProp(key, value))
                }
            }
            Log.d("GREPPBAR", "injectOnnxMetadata: sample_rate=$sampleRate n_speakers=$numSpeakers language=$espeakVoice")
        } catch (e: Exception) {
            Log.e("GREPPBAR", "injectOnnxMetadata FAILED: ${e.message}")
        }
    }

    /** Encode one ONNX ModelProto.metadata_props entry as protobuf bytes. */
    private fun encodeOnnxMetadataProp(key: String, value: String): ByteArray {
        // StringStringEntryProto: field 1 = key, field 2 = value (both length-delimited strings)
        val inner = encodeProtoString(1, key) + encodeProtoString(2, value)
        // ModelProto.metadata_props is field 14, wire type 2 (length-delimited)
        return encodeProtoLengthDelimited(14, inner)
    }

    private fun encodeProtoString(fieldNumber: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return encodeProtoLengthDelimited(fieldNumber, bytes)
    }

    private fun encodeProtoLengthDelimited(fieldNumber: Int, bytes: ByteArray): ByteArray {
        val tag = encodeVarint(((fieldNumber shl 3) or 2).toLong())
        val length = encodeVarint(bytes.size.toLong())
        return tag + length + bytes
    }

    private fun encodeVarint(value: Long): ByteArray {
        val result = mutableListOf<Byte>()
        var v = value
        while (v > 127L) {
            result.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        result.add((v and 0x7F).toByte())
        return result.toByteArray()
    }

    /**
     * Generate tokens.txt from phoneme_id_map in Piper's .onnx.json.
     * Required by sherpa-onnx: Validate() rejects empty tokens path.
     * Format: space phoneme → lone id on line; others → "symbol id".
     * Skips multi-codepoint phonemes (sherpa-onnx rejects them anyway).
     */
    private fun generateTokensTxt(jsonFile: File, outputFile: File) {
        try {
            val phonemeIdMap = JSONObject(jsonFile.readText()).optJSONObject("phoneme_id_map") ?: return
            val sb = StringBuilder()
            phonemeIdMap.keys().forEach { phoneme ->
                val ids = phonemeIdMap.optJSONArray(phoneme) ?: return@forEach
                if (ids.length() == 0) return@forEach
                val id = ids.getInt(0)
                // sherpa-onnx ReadTokens requires exactly 1 Unicode code point per token
                if (phoneme.codePointCount(0, phoneme.length) != 1) return@forEach
                if (phoneme == " ") {
                    sb.appendLine(id.toString())  // space: lone id on its own line
                } else {
                    sb.appendLine("$phoneme $id")
                }
            }
            outputFile.writeText(sb.toString())
            Log.d("GREPPBAR", "generateTokensTxt wrote ${phonemeIdMap.length()} entries to ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("GREPPBAR", "generateTokensTxt FAILED: ${e.message}")
        }
    }

    private fun downloadFile(url: String, destination: File): Result<Unit> {
        return try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return Result.failure(IOException("HTTP ${response.code}"))
            val body = response.body ?: return Result.failure(IOException("Empty body"))
            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Delete a downloaded voice from app storage.
     */
    override suspend fun deleteVoice(voice: Voice, context: Context) = withContext(Dispatchers.IO) {
        try {
            val voicesDir = getVoicesDirectory(context)
            val onnxFile = File(voicesDir, "${voice.name.lowercase()}$ONNX_EXTENSION")
            val datFile = File(voicesDir, "${voice.name.lowercase()}$DAT_EXTENSION")

            if (onnxFile.exists()) onnxFile.delete()
            if (datFile.exists()) datFile.delete()
            val jsonFile = File(voicesDir, "${voice.name.lowercase()}$ONNX_EXTENSION.json")
            if (jsonFile.exists()) jsonFile.delete()
            val tokensFile = File(voicesDir, "${voice.name.lowercase()}.tokens.txt")
            if (tokensFile.exists()) tokensFile.delete()
        } catch (e: Exception) {
            // Log error but don't throw
        }
    }

    /**
     * Scan app storage for downloaded voices.
     */
    override fun getDownloadedVoices(context: Context): List<Voice> {
        val voicesDir = getVoicesDirectory(context)
        if (!voicesDir.exists()) return emptyList()

        val onnxFiles = voicesDir.listFiles { file ->
            file.name.endsWith(ONNX_EXTENSION)
        } ?: return emptyList()

        return onnxFiles.map { file ->
            Voice(
                name = formatVoiceName(file.name.removeSuffix(ONNX_EXTENSION)),
                language = "", // Language would need to be stored separately or parsed from filename
                quality = "downloaded",
                sizeMb = file.length() / (1024.0 * 1024.0),
                onnxUrl = "",
                sampleRate = 22050,
                isDownloaded = true,
                isSelected = false
            )
        }
    }

    /**
     * Get the voices directory in app storage.
     */
    private fun getVoicesDirectory(context: Context): File {
        return File(context.filesDir, VOICES_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Construct download URL from path and file extension.
     */
    private fun constructDownloadUrl(path: String, extension: String): String {
        // Handle both directory paths and direct file paths
        val pathParts = path.split("/")
        if (pathParts.size > 1) {
            // Path like "en/en_US/amy/medium" -> build full URL
            val fileName = pathParts.last()
            val dirPath = pathParts.dropLast(1).joinToString("/")
            return "$HUGGINGFACE_BASE_URL/$dirPath/${fileName}$extension"
        }
        return "$HUGGINGFACE_BASE_URL/${path}$extension"
    }

    /**
     * Format voice name with init_cap (first letter of each word capitalized).
     */
    private fun formatVoiceName(name: String): String {
        // Check for known prefixes first
        INIT_CAP_MAP.forEach { (key, value) ->
            if (name.lowercase(Locale.ROOT).startsWith(key)) {
                return name.replaceFirst(key, value, ignoreCase = true)
            }
        }

        // Default: capitalize first letter
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    /**
     * Normalize language code for comparison.
     */
    private fun normalizeLanguageCode(lang: String): String {
        return lang.replace("-", "_").lowercase(Locale.ROOT)
    }

    /**
     * Event listener for tracking HTTP call events.
     */
    private class CallEventListener : EventListener() {
        override fun callStart(call: Call) {
            // Could log call start
        }

        override fun callEnd(call: Call) {
            // Could log call end
        }
    }
}
