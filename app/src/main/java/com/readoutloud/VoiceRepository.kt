package com.readoutloud

import java.io.File

/**
 * Repository interface for managing Piper TTS voices from HuggingFace.
 * Provides operations to fetch, download, delete, and scan for voices.
 */
interface VoiceRepository {
    /**
     * Fetch available voices for a given language from HuggingFace.
     * @param lang Language code (e.g., "de", "en", "en-US")
     * @return List of Voice objects with init_cap names
     */
    suspend fun getVoicesForLanguage(lang: String): List<Voice>

    /**
     * Download a voice file from HuggingFace.
     * @param voice The voice to download
     * @param destination Destination file path
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return Result with success/failure
     */
    suspend fun downloadVoice(
        voice: Voice,
        destination: File,
        onProgress: (Float) -> Unit
    ): Result<Unit>

    /**
     * Delete a downloaded voice from app storage.
     * @param voice The voice to delete
     * @param context Android context for accessing files
     */
    suspend fun deleteVoice(voice: Voice, context: android.content.Context)

    /**
     * Scan app storage for downloaded voices.
     * @param context Android context for accessing files
     * @return List of downloaded Voice objects
     */
    fun getDownloadedVoices(context: android.content.Context): List<Voice>
}
