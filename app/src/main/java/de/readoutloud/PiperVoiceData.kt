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
    val isSelected: Boolean = false
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
