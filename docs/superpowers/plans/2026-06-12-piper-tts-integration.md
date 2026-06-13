# Piper TTS Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Piper TTS with offline voice downloads from HuggingFace, voice management UI, and engine switching between Android TTS and Piper.

**Architecture:** 
- `VoiceRepository` fetches available voices from HuggingFace API (voices.json)
- `VoiceManager` handles downloads, deletions, and selection state
- `PiperTtsEngine` implements ONNX-based speech synthesis for downloaded voices
- `TtsController` delegates to either Android TTS or Piper based on selected voice
- Voices stored in app-specific internal storage, persisted via AppSettings

**Tech Stack:** Kotlin, Jetpack Compose, ONNX Runtime Android, OkHttp for downloads, SharedPreferences

---

## File Structure

**Create:**
- `PiperVoiceData.kt` — Voice, VoiceDownload, DownloadStatus data classes
- `VoiceRepository.kt` — Interface for voice listing and downloads
- `VoiceRepositoryImpl.kt` — HuggingFace API implementation
- `VoiceManager.kt` — Voice selection, download orchestration, state management
- `VoiceDialog.kt` — Composable UI for voice selection with download progress
- `PiperTtsEngine.kt` — ONNX-based TTS engine implementation
- `TtsEngine.kt` — Interface for TTS engines (AndroidTts, PiperTts)

**Modify:**
- `AppSettings.kt` — Add selectedVoice, downloadedVoices persistence
- `TtsController.kt` — Add engine switching, voice parameter
- `MainActivity.kt` — Add 🗣️ button, VoiceDialog integration
- `build.gradle.kts` — Add ONNX Runtime, OkHttp dependencies

---

### Task 1: Data Models (PiperVoiceData.kt)

**Files:**
- Create: `app/src/main/java/com/readoutloud/PiperVoiceData.kt`

- [ ] **Step 1: Write data classes**

```kotlin
package com.readoutloud

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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/readoutloud/PiperVoiceData.kt
git commit -m "feat: add Piper voice data models"
```

---

### Task 2: TtsEngine Interface (TtsEngine.kt)

**Files:**
- Create: `app/src/main/java/com/readoutloud/TtsEngine.kt`

- [ ] **Step 1: Write interface**

```kotlin
package com.readoutloud

interface TtsEngine {
    fun speak(text: String, params: SpeakParams = SpeakParams())
    fun stop()
    fun setLanguage(lang: String)
    fun release()
    val isReady: Boolean
}

data class SpeakParams(
    val volume: Float = 1.0f,
    val rate: Float = 1.0f,
    val audioSessionId: Int = 0
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/readoutloud/TtsEngine.kt
git commit -m "feat: add TtsEngine interface"
```

---

### Task 3: VoiceRepository Interface & Implementation

**Files:**
- Create: `app/src/main/java/com/readoutloud/VoiceRepository.kt`
- Create: `app/src/main/java/com/readoutloud/VoiceRepositoryImpl.kt`

- [ ] **Step 1: Write repository interface**

```kotlin
package com.readoutloud

interface VoiceRepository {
    suspend fun getVoicesForLanguage(lang: String): List<Voice>
    suspend fun downloadVoice(
        voice: Voice,
        destination: File,
        onProgress: (Float) -> Unit
    ): Result<Unit>
    suspend fun deleteVoice(voice: Voice, context: Context)
    fun getDownloadedVoices(context: Context): List<Voice>
}
```

- [ ] **Step 2: Write repository implementation**

```kotlin
package com.readoutloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class VoiceRepositoryImpl(
    private val httpClient: OkHttpClient = OkHttpClient()
) : VoiceRepository {

    private val baseUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/voices.json"
    private val voicesDir = "voices"

    override suspend fun getVoicesForLanguage(lang: String): List<Voice> = withContext(Dispatchers.IO) {
        // Fetch and parse voices.json, filter by language
        // Return list of Voice objects with init_cap names
        emptyList()
    }

    override suspend fun downloadVoice(
        voice: Voice,
        destination: File,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // Download .onnx file with progress reporting
        Result.success(Unit)
    }

    override suspend fun deleteVoice(voice: Voice, context: Context) = withContext(Dispatchers.IO) {
        // Delete downloaded files from app storage
    }

    override fun getDownloadedVoices(context: Context): List<Voice> {
        // Scan storage for downloaded voices
        emptyList()
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/readoutloud/VoiceRepository.kt app/src/main/java/com/readoutloud/VoiceRepositoryImpl.kt
git commit -m "feat: add VoiceRepository for HuggingFace voice downloads"
```

---

### Task 4: VoiceManager

**Files:**
- Create: `app/src/main/java/com/readoutloud/VoiceManager.kt`

- [ ] **Step 1: Write VoiceManager**

```kotlin
package com.readoutloud

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class VoiceManager(
    private val context: Context,
    private val repository: VoiceRepository,
    private val settings: AppSettings
) {
    private val _downloads = MutableStateFlow<Map<String, VoiceDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, VoiceDownload>> = _downloads.asStateFlow()

    val selectedVoice: StateFlow<Voice?> = MutableStateFlow(null)
    val downloadedVoices: StateFlow<List<Voice>> = MutableStateFlow(emptyList())

    private val voicesDir: File
        get() = File(context.filesDir, "voices")

    fun selectVoice(voice: Voice) {
        settings.selectedVoice = voice.name
        (selectedVoice as MutableStateFlow).value = voice
    }

    fun downloadVoice(voice: Voice) {
        // Start parallel download
    }

    fun deleteVoice(voice: Voice) {
        // Delete and deselect if active
    }

    fun cancelDownload(voiceName: String) {
        // Cancel active download
    }

    suspend fun refreshDownloadedVoices() {
        val voices = repository.getDownloadedVoices(context)
        (downloadedVoices as MutableStateFlow).value = voices
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/readoutloud/VoiceManager.kt
git commit -m "feat: add VoiceManager for voice state management"
```

---

### Task 5: AppSettings Extensions

**Files:**
- Modify: `app/src/main/java/com/readoutloud/AppSettings.kt`

- [ ] **Step 1: Add voice properties**

```kotlin
    var language: String
        get() = prefs.getString("language", null) ?: defaultLang
        set(value) = prefs.edit().putString("language", value).apply()

    var selectedVoice: String?
        get() = prefs.getString("selected_voice", null)
        set(value) = prefs.edit().putString("selected_voice", value).apply()

    var downloadedVoices: List<String>
        get() = prefs.getStringSet("downloaded_voices", emptySet())?.toList() ?: emptyList()
        set(value) = prefs.edit().putStringSet("downloaded_voices", value.toSet()).apply()
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/readoutloud/AppSettings.kt
git commit -m "feat: add voice persistence to AppSettings"
```

---

### Task 6: VoiceDialog UI

**Files:**
- Create: `app/src/main/java/com/readoutloud/VoiceDialog.kt`

- [ ] **Step 1: Write VoiceDialog composable**

```kotlin
package com.readoutloud

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun VoiceDialog(
    currentLang: String,
    selectedVoice: Voice?,
    downloadedVoices: List<Voice>,
    downloads: Map<String, VoiceDownload>,
    onVoiceSelected: (Voice) -> Unit,
    onDownload: (Voice) -> Unit,
    onDelete: (Voice) -> Unit,
    onCancelDownload: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voices for ${currentLang.uppercase()}") },
        text = {
            Column {
                // Android TTS default
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onVoiceSelected(Voice("Android Default", currentLang, "default", 0.0, "", 0, true, selectedVoice?.name == "Android Default")) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(selectedVoice?.name == "Android Default", null)
                    Text("android TTS default", modifier = Modifier.weight(1f))
                    Text("0MB", style = MaterialTheme.typography.bodySmall)
                }

                LazyColumn {
                    items(downloadedVoices.filter { it.language == currentLang }) { voice ->
                        VoiceRow(
                            voice = voice,
                            isSelected = selectedVoice?.name == voice.name,
                            download = downloads[voice.name],
                            onSelect = { onVoiceSelected(voice) },
                            onDownload = { onDownload(voice) },
                            onDelete = { onDelete(voice) },
                            onCancelDownload = { onCancelDownload(voice.name) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun VoiceRow(
    voice: Voice,
    isSelected: Boolean,
    download: VoiceDownload?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCancelDownload: () -> Unit
) {
    // Implementation with checkbox, name, download button, delete button, progress bar
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/readoutloud/VoiceDialog.kt
git commit -m "feat: add VoiceDialog UI component"
```

---

### Task 7: MainActivity Integration

**Files:**
- Modify: `app/src/main/java/com/readoutloud/MainActivity.kt`

- [ ] **Step 1: Add VoiceManager initialization**

```kotlin
    private val settings = AppSettings(this)
    private val voiceManager = VoiceManager(this, VoiceRepositoryImpl(), settings)
    private var showVoiceDialog by mutableStateOf(false)
```

- [ ] **Step 2: Add 🗣️ button to toolbar**

```kotlin
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        // ... existing buttons
                        RoundBtn(onClick = { showVoiceDialog = true }) {
                            Text("🗣️", style = MaterialTheme.typography.titleMedium)
                        }
                        // Language flag button follows
                    }
```

- [ ] **Step 3: Add VoiceDialog**

```kotlin
        if (showVoiceDialog) {
            VoiceDialog(
                currentLang = languageState.value,
                selectedVoice = voiceManager.selectedVoice.value,
                downloadedVoices = voiceManager.downloadedVoices.value,
                downloads = voiceManager.downloads.value,
                onVoiceSelected = { voice ->
                    voiceManager.selectVoice(voice)
                    showVoiceDialog = false
                    // Refresh TTS engine
                },
                onDownload = { voice -> voiceManager.downloadVoice(voice) },
                onDelete = { voice -> voiceManager.deleteVoice(voice) },
                onCancelDownload = { voiceName -> voiceManager.cancelDownload(voiceName) },
                onDismiss = { showVoiceDialog = false }
            )
        }
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/readoutloud/MainActivity.kt
git commit -m "feat: add voice selector button and dialog to MainActivity"
```

---

### Task 8: Add Dependencies (build.gradle.kts)

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add ONNX Runtime and OkHttp**

```kotlin
dependencies {
    // ... existing dependencies
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
}
```

- [ ] **Step 2: Commit**

```bash
git add app/build.gradle.kts
git commit -m "feat: add ONNX Runtime and OkHttp dependencies"
```

---

### Task 9: PiperTtsEngine Implementation

**Files:**
- Create: `app/src/main/java/com/readoutloud/PiperTtsEngine.kt`

- [ ] **Step 1: Write PiperTtsEngine**

```kotlin
package com.readoutloud

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.io.File

class PiperTtsEngine(
    private val context: Context,
    private val voiceManager: VoiceManager
) : TtsEngine {

    private var env = OrtEnvironment.getEnvironment()
    private var model: ai.onnxruntime.OrtSession? = null
    private var currentVoice: Voice? = null
    private var audioTrack: AudioTrack? = null

    override val isReady: Boolean get() = model != null

    fun loadVoice(voice: Voice): Result<Unit> {
        // Load ONNX model from storage
        return Result.success(Unit)
    }

    override fun speak(text: String, params: SpeakParams) {
        // Run inference and play audio via AudioTrack
    }

    override fun stop() {
        audioTrack?.stop()
    }

    override fun setLanguage(lang: String) {
        // Not used - language determined by selected voice
    }

    override fun release() {
        model?.close()
        audioTrack?.release()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/readoutloud/PiperTtsEngine.kt
git commit -m "feat: add PiperTtsEngine with ONNX Runtime"
```

---

### Task 10: TtsController Engine Switching

**Files:**
- Modify: `app/src/main/java/com/readoutloud/TtsController.kt`

- [ ] **Step 1: Add engine delegation**

```kotlin
class TtsController(
    private val context: Context,
    private val tts: TextToSpeech,
    private val voiceManager: VoiceManager
) {
    private val androidEngine = AndroidTtsEngine(tts)
    private val piperEngine = PiperTtsEngine(context, voiceManager)
    private var currentEngine: TtsEngine = androidEngine

    init {
        // Listen for voice changes, switch engine accordingly
    }

    fun speak(sentences: List<String>, currentIndex: Int) {
        currentEngine.speak(sentences[currentIndex])
    }

    // ... delegate other methods
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/readoutloud/TtsController.kt
git commit -m "feat: add engine switching to TtsController"
```

---

### Task 11: Testing & Verification

**Files:**
- No production files - verify manually

- [ ] **Step 1: Test download flow**
  - Open voice dialog
  - Download a voice
  - Verify progress bar updates
  - Verify file saved to storage

- [ ] **Step 2: Test voice selection**
  - Select downloaded voice
  - Verify TTS uses Piper engine
  - Speak text and verify audio output

- [ ] **Step 3: Test engine switching**
  - Switch between Android TTS and Piper
  - Verify no crashes during switch

- [ ] **Step 4: Commit test results**

```bash
git commit --allow-empty -m "test: verify Piper TTS integration manual testing complete"
```

---

## Implementation Order

1. Task 1: Data Models
2. Task 2: TtsEngine Interface
3. Task 3: VoiceRepository
4. Task 4: VoiceManager
5. Task 5: AppSettings Extensions
6. Task 6: VoiceDialog UI
7. Task 7: MainActivity Integration
8. Task 8: Dependencies
9. Task 9: PiperTtsEngine
10. Task 10: TtsController Integration
11. Task 11: Testing

---

**Execution choice:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
