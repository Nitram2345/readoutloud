package de.readoutloud

import java.io.Serializable

data class Voice(
    val name: String,
    val language: String,
    val quality: String,
    val sizeMb: Double,
    val onnxUrl: String,
    val sampleRate: Int,
    val isDownloaded: Boolean = false,
    val isSelected: Boolean = false,
    // Link to the voice's MODEL_CARD on HuggingFace, which states its license and what it was
    // finetuned from. Licenses differ per voice and some are non-commercial or research only, so
    // the picker links here rather than implying every voice is free to use. Null for the Android
    // system voice and for downloaded voices whose catalogue entry is no longer around.
    val modelCardUrl: String? = null
)

data class VoiceDownload(
    val voice: Voice,
    val progress: Float,
    val status: DownloadStatus,
    val destination: String
)

enum class DownloadStatus {
    Idle, Downloading, Completed, Failed, Cancelled
}
