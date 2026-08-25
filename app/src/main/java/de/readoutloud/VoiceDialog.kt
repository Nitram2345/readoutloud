package de.readoutloud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import de.readoutloud.CountryUtils.getFlagForLanguage

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
    licenseAcknowledged: Boolean,
    onAcknowledgeLicense: () -> Unit,
    onVoiceSelected: (Voice) -> Unit,
    onVoiceDownloaded: (Voice) -> Unit,
    onVoiceDeleted: (Voice) -> Unit,
    onCancelDownload: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    // Set while the license notice is up, so the download can run once it is confirmed.
    var pendingDownload by remember { mutableStateOf<Voice?>(null) }

    // Merge all voices with download status - computed once and memoized
    val sortedVoices = remember(downloadedVoices, allVoices, selectedVoice) {
        val voiceMap = mutableMapOf<String, Voice>()

        // getDownloadedVoices() only scans the filesystem and has no catalogue data, so the
        // MODEL_CARD link would vanish the moment a voice finished downloading. Carry it over.
        val catalogByName = allVoices.associateBy { it.name }

        // Add downloaded voices first
        downloadedVoices.forEach { voice ->
            voiceMap[voice.name] = voice.copy(
                isDownloaded = true,
                isSelected = voice.name == selectedVoice?.name,
                modelCardUrl = voice.modelCardUrl ?: catalogByName[voice.name]?.modelCardUrl
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

    val flagForLanguage = remember(currentLanguage) {
        getFlagForLanguage(currentLanguage)
    }

    MaterialAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "$flagForLanguage Voices for $currentLanguage",
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
                        isDownloading = activeDownloads[voice.name]?.status == DownloadStatus.Downloading,
                        downloadProgress = activeDownloads[voice.name]?.progress ?: 0f,
                        isSelected = voice.name == selectedVoice?.name,
                        onSelect = { onVoiceSelected(voice) },
                        // First download of any voice shows the license notice instead.
                        onDownload = {
                            if (licenseAcknowledged) onVoiceDownloaded(voice)
                            else pendingDownload = voice
                        },
                        onDelete = { onVoiceDeleted(voice) },
                        onCancel = { onCancelDownload(voice.name) },
                        onOpenLicense = { voice.modelCardUrl?.let(uriHandler::openUri) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(android.R.string.cancel))
            }
        }
    )

    // Voice licenses differ per voice and some are non-commercial or research only, so say where
    // the voices come from and where their license is before fetching the first one. Shown once.
    val voiceToConfirm = pendingDownload
    if (voiceToConfirm != null) {
        var acknowledged by remember(voiceToConfirm) { mutableStateOf(false) }
        MaterialAlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = {
                Text(stringResource(R.string.voice_license_title), fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.voice_license_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { acknowledged = !acknowledged },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                        Text(
                            text = stringResource(R.string.voice_license_checkbox),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = acknowledged,
                    onClick = {
                        onAcknowledgeLicense()
                        onVoiceDownloaded(voiceToConfirm)
                        pendingDownload = null
                    }
                ) {
                    Text(stringResource(R.string.voice_license_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownload = null }) {
                    Text(context.getString(android.R.string.cancel))
                }
            }
        )
    }
}

/** Opens the voice's MODEL_CARD on HuggingFace, which states its license and its lineage. */
@Composable
private fun LicenseLink(onClick: () -> Unit) {
    Text(
        text = stringResource(R.string.voice_license_link),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(top = 2.dp)
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
    onCancel: () -> Unit,
    onOpenLicense: () -> Unit = {}
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
                        text = "${"%.1f".format(voice.sizeMb)} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = downloadProgress.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                TextButton(onClick = onCancel) {
                    Text("❌", fontSize = MaterialTheme.typography.bodySmall.fontSize)
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
                    val voiceFlag = remember(voice.language) { getFlagForLanguage(voice.language) }
                    Text(
                        text = "$voiceFlag ${voice.name}",
                        fontWeight = if (voice.name == "android TTS default") FontWeight.Normal else FontWeight.Medium
                    )
                    Text(
                        text = "${"%.1f".format(voice.sizeMb)} MB • Quality: ${voice.quality}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (voice.modelCardUrl != null) LicenseLink(onOpenLicense)
                }

                // Delete button (only for downloaded voices, not default)
                if (voice.name != "android TTS default") {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
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
                    val voiceFlag = remember(voice.language) { getFlagForLanguage(voice.language) }
                    Text(
                        text = "$voiceFlag ${voice.name}",
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${"%.1f".format(voice.sizeMb)} MB • Quality: ${voice.quality}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (voice.modelCardUrl != null) LicenseLink(onOpenLicense)
                }

                // Download button
                TextButton(onClick = onDownload) {
                    Text("⬇️", fontSize = MaterialTheme.typography.titleLarge.fontSize)
                }
            }
        }
    }
}
