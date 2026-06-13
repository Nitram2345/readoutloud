package com.readoutloud

import android.content.Context
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
        private const val VOICES_JSON_URL = "https://huggingface.co/rhaspy/piper-voices/resolve/main/voices.json"
        private const val HUGGINGFACE_BASE_URL = "https://huggingface.co/rhaspy/piper-voices/resolve/main"
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
            val voicesJson = JSONArray(jsonText)

            val normalizedLang = normalizeLanguageCode(lang)
            val filteredVoices = mutableListOf<Voice>()

            for (i in 0 until voicesJson.length()) {
                val voiceJson = voicesJson.getJSONObject(i)
                val languageCode = voiceJson.optString("lang", "")

                // Filter by language code (exact match or normalized match)
                if (languageCode.equals(normalizedLang, ignoreCase = true) ||
                    languageCode.startsWith(normalizedLang.substringBefore("-"), ignoreCase = true)) {

                    val name = voiceJson.optString("name", "Unknown")
                    val quality = voiceJson.optString("quality", "unknown")
                    val sizeMb = voiceJson.optDouble("size_mb", 0.0)
                    val path = voiceJson.optString("path", "")
                    val sampleRate = voiceJson.optInt("sample_rate", 22050)

                    // Construct download URL and format name with init_cap
                    val onnxUrl = constructDownloadUrl(path, ONNX_EXTENSION)
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

            filteredVoices
        } catch (e: Exception) {
            // Log error and return empty list
            emptyList()
        }
    }

    /**
     * Download a voice file from HuggingFace with progress reporting.
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

        override fun responseReceived(call: Call, response: Response) {
            // Could log response
        }

        override fun callEnd(call: Call) {
            // Could log call end
        }
    }
}
