package com.readoutloud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.AlertDialog

/**
 * VoiceDialog: AlertDialog for selecting and managing TTS voices.
 *
 * Features:
 * - Shows "android TTS default" as first row (0MB, no delete)
 * - Lists downloaded voices with checkbox, name, and delete button (🗑️)
 * - Shows progress bar and cancel button for active downloads
 * - Highlights selected voice with different background
 * - Download button for undownloaded voices
 */
@Composable
fun VoiceDialog(
    currentLanguage: String,
    allVoices: List<Voice>,
    downloadedVoices: List<Voice>,
    selectedVoice: Voice?,
    activeDownloads: Map<String, VoiceDownload>,
    onVoiceSelected: (Voice) -> Unit,
    onVoiceDownloaded: (Voice) -> Unit,
    onVoiceDeleted: (Voice) -> Unit,
    onCancelDownload: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // Merge all voices with download status - computed once and memoized
    val sortedVoices = remember(downloadedVoices, allVoices, selectedVoice) {
        val voiceMap = mutableMapOf<String, Voice>()

        // Add downloaded voices first
        downloadedVoices.forEach { voice ->
            voiceMap[voice.name] = voice.copy(
                isDownloaded = true,
                isSelected = voice.name == selectedVoice?.name
            )
        }

        // Add android TTS default (always available, not downloadable)
        val defaultVoice = Voice(
            name = "android TTS default",
            language = currentLanguage,
            quality = "default",
            sizeMb = 0.0,
            onnxUrl = "",
            sampleRate = 22050,
            isDownloaded = true,
            isSelected = selectedVoice?.name == "android TTS default"
        )
        voiceMap["android TTS default"] = defaultVoice

        // Add other voices from the list (not downloaded yet)
        allVoices.forEach { voice ->
            if (!voiceMap.containsKey(voice.name)) {
                voiceMap[voice.name] = voice.copy(isDownloaded = false, isSelected = false)
            }
        }

        // Sort: default first, then downloaded, then available for download
        voiceMap.values.sortedWith(
            compareByDescending<Voice> { it.name == "android TTS default" }
                .thenByDescending { it.isDownloaded }
                .thenBy { it.name }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Voices for $currentLanguage",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(sortedVoices, key = { it.name }) { voice ->
                    VoiceRow(
                        voice = voice,
                        isDownloading = activeDownloads.containsKey(voice.name),
                        downloadProgress = activeDownloads[voice.name]?.progress ?: 0f,
                        isSelected = voice.name == selectedVoice?.name,
                        onSelect = { onVoiceSelected(voice) },
                        onDownload = { onVoiceDownloaded(voice) },
                        onDelete = { onVoiceDeleted(voice) },
                        onCancel = { onCancelDownload(voice.name) }
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * VoiceRow: A single row in the voice list.
 * Shows checkbox, name, size, and action buttons based on voice state.
 */
@Composable
fun VoiceRow(
    voice: Voice,
    isDownloading: Boolean,
    downloadProgress: Float,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
    ) {
        if (isDownloading) {
            // Downloading state: progress bar and cancel button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = voice.name,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${voice.sizeMb} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        barHeight = 6.dp
                    )
                }
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel download",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        } else if (voice.isDownloaded || voice.name == "android TTS default") {
            // Downloaded or default state: checkbox and delete button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSelect)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Checkbox
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelect() }
                )

                // Voice info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = voice.name,
                        fontWeight = if (voice.name == "android TTS default") FontWeight.Normal else FontWeight.Medium
                    )
                    Text(
                        text = "${voice.sizeMb} MB • Quality: ${voice.quality}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Delete button (only for downloaded voices, not default)
                if (voice.name != "android TTS default") {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp),
                        contentDescription = "Delete voice"
                    ) {
                        Text(
                            text = "🗑️",
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize
                        )
                    }
                } else {
                    // Placeholder for alignment
                    Spacer(modifier = Modifier.size(36.dp))
                }
            }
        } else {
            // Not downloaded: download button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDownload)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Empty space for checkbox alignment
                Spacer(modifier = Modifier.size(20.dp))

                // Voice info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = voice.name,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${voice.sizeMb} MB • Quality: ${voice.quality}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Download button
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(36.dp),
                    contentDescription = "Download voice"
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
