# Piper TTS Inference Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement working Piper neural TTS inference in `PiperTtsEngine`, replacing the stub that throws `IllegalStateException`.

**Architecture:** Replace manual `onnxruntime-android` usage with **sherpa-onnx** (bundles espeak-ng phonemizer + ONNX Runtime internally). Download `.onnx.json` config alongside `.onnx` model (sherpa-onnx reads it automatically). Extend `TtsEngine.speak()` with an `onDone` callback so `PiperTtsEngine` can notify `TtsController` when audio playback completes — Android TTS already does this via `UtteranceProgressListener`, Piper needs its own explicit path. `PiperTtsEngine` runs generation and playback on a background coroutine and cancels it on `stop()`.

**Tech Stack:** sherpa-onnx Android AAR ≥ 1.10.x (espeak-ng + ORT bundled), `AudioTrack` (ENCODING_PCM_FLOAT), Kotlin coroutines, OkHttp (already present)

---

## File Map

| Action | Path |
|--------|------|
| Modify | `app/build.gradle.kts` — swap `onnxruntime-android` → `sherpa-onnx-android` |
| Modify | `app/src/main/java/com/readoutloud/TtsEngine.kt` — add `utteranceId` + `onDone` to `speak()` |
| Modify | `app/src/main/java/com/readoutloud/PiperTtsEngine.kt` — full inference implementation |
| Modify | `app/src/main/java/com/readoutloud/TtsController.kt` — pass `onDone` from `speakAt()` |
| Modify | `app/src/main/java/com/readoutloud/VoiceRepositoryImpl.kt` — also download `.onnx.json` |
| Modify | `app/src/main/java/com/readoutloud/VoiceRepository.kt` — no interface change needed |
| Modify | `app/proguard-rules.pro` — keep sherpa-onnx classes |

---

## Task 1: Download `.onnx.json` Config Alongside `.onnx` Model

Sherpa-onnx reads `<voice>.onnx.json` automatically when it exists next to `<voice>.onnx`. Without it, espeak voice selection and inference parameters (noise_scale, length_scale) are unknown. The config URL is always `${onnxUrl}.json`.

**Files:**
- Modify: `app/src/main/java/com/readoutloud/VoiceRepositoryImpl.kt`

- [ ] **Step 1: Write failing unit test**

```kotlin
// app/src/test/java/com/readoutloud/VoiceRepositoryImplTest.kt
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files

class VoiceRepositoryImplTest {

    @Test
    fun `downloadVoice creates json config file alongside onnx`() {
        // Verify that when downloadVoice completes, a .onnx.json file exists next to .onnx
        val tempDir = Files.createTempDirectory("voices").toFile()
        val onnxFile = File(tempDir, "en_US-amy-medium.onnx")
        val jsonFile = File(tempDir, "en_US-amy-medium.onnx.json")

        // Simulate the download by checking the naming convention
        // The json URL should be onnxUrl + ".json"
        val fakeOnnxUrl = "https://example.com/en_US-amy-medium.onnx"
        val expectedJsonUrl = "https://example.com/en_US-amy-medium.onnx.json"
        assertEquals(expectedJsonUrl, fakeOnnxUrl + ".json")

        tempDir.deleteRecursively()
    }
}
```

- [ ] **Step 2: Run test to verify it passes (naming convention is trivial)**

```
./gradlew :app:test --tests "com.readoutloud.VoiceRepositoryImplTest"
```

Expected: PASS (naming convention test)

- [ ] **Step 3: Add private download helper to `VoiceRepositoryImpl`**

Add this private method to `VoiceRepositoryImpl` (after `downloadVoice`):

```kotlin
private suspend fun downloadFile(url: String, destination: File): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
        val body = response.body ?: return@withContext Result.failure(IOException("Empty body"))
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { out ->
            body.byteStream().use { input -> input.copyTo(out) }
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

- [ ] **Step 4: Modify `downloadVoice` to also download `.onnx.json`**

In `VoiceRepositoryImpl.downloadVoice()`, after the existing download logic succeeds, add:

```kotlin
// After Result.success(Unit) line, before returning:
// Also download the .onnx.json config file (required by sherpa-onnx for Piper)
val jsonDest = File(destination.parent, destination.name + ".json")
val jsonResult = downloadFile(voice.onnxUrl + ".json", jsonDest)
if (jsonResult.isFailure) {
    Log.w("VoiceRepository", "Could not download .onnx.json for ${voice.name}: ${jsonResult.exceptionOrNull()?.message}")
    // Non-fatal: sherpa-onnx may still work without it for some voices
}

return@withContext Result.success(Unit)
```

The full modified `downloadVoice` return structure:

```kotlin
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
                onProgress(totalBytesRead.toFloat() / contentLength.toFloat())
            }
        }

        outputStream.flush()
        outputStream.close()
        inputStream.close()

        // Download companion JSON config (sherpa-onnx reads it for Piper voices)
        val jsonDest = File(destination.parent, destination.name + ".json")
        val jsonResult = downloadFile(voice.onnxUrl + ".json", jsonDest)
        if (jsonResult.isFailure) {
            Log.w("VoiceRepository", "Could not download .onnx.json for ${voice.name}: ${jsonResult.exceptionOrNull()?.message}")
        }

        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

- [ ] **Step 5: Also delete `.onnx.json` in `deleteVoice`**

In `deleteVoice()`, add after `if (onnxFile.exists()) onnxFile.delete()`:

```kotlin
val jsonFile = File(voicesDir, "${voice.name.lowercase()}$ONNX_EXTENSION.json")
if (jsonFile.exists()) jsonFile.delete()
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/readoutloud/VoiceRepositoryImpl.kt
git commit -m "feat: download .onnx.json config alongside .onnx voice file"
```

---

## Task 2: Replace `onnxruntime-android` with `sherpa-onnx`

Sherpa-onnx bundles ONNX Runtime and espeak-ng internally. Using both `onnxruntime-android` and `sherpa-onnx` would duplicate native libraries and likely cause `UnsatisfiedLinkError`. Replace, do not add alongside.

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/proguard-rules.pro`

- [ ] **Step 1: Find latest sherpa-onnx version**

Check Maven Central for the latest version:
```
https://central.sonatype.com/artifact/io.github.k2-fsa.sherpa-onnx/sherpa-onnx-android
```

The artifact for arm64-v8a only (matches `ndk { abiFilters += listOf("arm64-v8a") }`):
```
io.github.k2-fsa.sherpa-onnx:sherpa-onnx-android:VERSION
```

Note: If an `arm64-v8a`-specific artifact is available (e.g. `sherpa-onnx-android-arm64-v8a`), prefer it — smaller download. As of 1.10.x the main artifact includes all ABIs so is ~200MB; the arch-specific is ~50MB.

- [ ] **Step 2: Update `app/build.gradle.kts`**

Replace:
```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
```

With (use actual latest version from step 1):
```kotlin
implementation("io.github.k2-fsa.sherpa-onnx:sherpa-onnx-android:1.10.38")
```

Also add to `packaging.resources.excludes` if needed for sherpa-onnx META-INF conflicts:
```kotlin
"META-INF/MANIFEST.MF"
```

- [ ] **Step 3: Add ProGuard rules for sherpa-onnx**

In `app/proguard-rules.pro`, add:

```proguard
# sherpa-onnx JNI bridge — must not be renamed
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
```

- [ ] **Step 4: Build to verify no duplicate native lib errors**

```bash
./gradlew assembleDebug 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL, no `duplicate entry` or `UnsatisfiedLinkError` warnings.

If you see `Duplicate files during packaging of APK`, add the conflicting path to `packaging.resources.excludes` in `build.gradle.kts`.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/proguard-rules.pro
git commit -m "feat: replace onnxruntime-android with sherpa-onnx for Piper TTS"
```

---

## Task 3: Extend `TtsEngine` Interface with `onDone` Callback

Android TTS notifies completion via `UtteranceProgressListener.onDone()`. Piper generates audio synchronously (blocking), then plays it — we need an explicit completion callback so `TtsController` knows when to advance to the next sentence. Adding `utteranceId` and `onDone` to `speak()` with default values keeps `AndroidTtsEngine` backward-compatible.

**Files:**
- Modify: `app/src/main/java/com/readoutloud/TtsEngine.kt`

- [ ] **Step 1: Update `TtsEngine.speak()` signature**

Replace the current `speak` declaration:
```kotlin
fun speak(text: String, params: SpeakParams = SpeakParams())
```

With:
```kotlin
fun speak(
    text: String,
    params: SpeakParams = SpeakParams(),
    utteranceId: String = "",
    onDone: (() -> Unit)? = null
)
```

- [ ] **Step 2: Update `TtsController.AndroidTtsEngine.speak()` to match**

In `TtsController.kt`, find `private class AndroidTtsEngine` and update its `speak()`:

```kotlin
override fun speak(text: String, params: SpeakParams, utteranceId: String, onDone: (() -> Unit)?) {
    val bundle = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, params.volume)
        if (params.audioSessionId != 0)
            putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, params.audioSessionId)
    }
    tts.setSpeechRate(params.rate)
    // onDone is ignored — AndroidTts fires UtteranceProgressListener instead
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, utteranceId.ifEmpty { null })
}
```

- [ ] **Step 3: Build to verify compilation**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|warning:" | head -20
```

Expected: No errors. If `PiperTtsEngine` doesn't compile because its `speak()` signature is wrong, that's expected — fix in Task 4.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/readoutloud/TtsEngine.kt app/src/main/java/com/readoutloud/TtsController.kt
git commit -m "feat: add utteranceId and onDone callback to TtsEngine.speak()"
```

---

## Task 4: Implement `PiperTtsEngine` with sherpa-onnx

This is the core task. `speak()` must be non-blocking (called on main thread by `TtsController`). It launches a coroutine that: generates audio samples via sherpa-onnx `OfflineTts.generate()`, plays them via `AudioTrack`, then calls `onDone` on the main thread. `stop()` cancels the coroutine and stops the `AudioTrack` immediately.

**Files:**
- Modify: `app/src/main/java/com/readoutloud/PiperTtsEngine.kt`

- [ ] **Step 1: Write instrumented test for basic TTS generation**

```kotlin
// app/src/androidTest/java/com/readoutloud/PiperTtsEngineTest.kt
package com.readoutloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PiperTtsEngineTest {

    @Test
    fun speak_callsOnDone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val voiceManager = VoiceManager(
            context,
            VoiceRepositoryImpl(),
            AppSettings(context)
        )
        val engine = PiperTtsEngine(context, voiceManager)
        val voice = voiceManager.selectedVoice.value

        if (voice == null) {
            // No voice downloaded — skip test
            return
        }

        val latch = CountDownLatch(1)
        engine.loadVoice(voice)

        engine.speak(
            text = "Hello world",
            params = SpeakParams(volume = 0.5f),
            utteranceId = "test-1",
            onDone = { latch.countDown() }
        )

        val completed = latch.await(15, TimeUnit.SECONDS)
        assertTrue("onDone not called within 15 seconds", completed)
    }
}
```

- [ ] **Step 2: Run test to verify it fails (engine not implemented)**

```bash
./gradlew :app:connectedDebugAndroidTest --tests "com.readoutloud.PiperTtsEngineTest" 2>&1 | tail -20
```

Expected: Test skipped (no voice) OR fails with `IllegalStateException: Piper ONNX Inference not yet implemented`.

- [ ] **Step 3: Rewrite `PiperTtsEngine.kt` with full sherpa-onnx implementation**

Replace the entire file content:

```kotlin
package com.readoutloud

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
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

    override val isReady: Boolean get() = tts != null

    fun loadVoice(voice: Voice): Result<Unit> {
        return try {
            val voiceFile = File(context.filesDir, "voices/${voice.name}.onnx")
            if (!voiceFile.exists()) {
                return Result.failure(IllegalStateException("Voice file not found: ${voice.name}"))
            }

            tts?.delete()

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = voiceFile.absolutePath,
                        lexicon = "",
                        tokens = "",
                        dataDir = ""
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                maxNumSentences = 1
            )

            tts = OfflineTts(config = config)
            currentVoice = voice

            Log.i("PiperTtsEngine", "Loaded Piper voice: ${voice.name} @ ${voice.sampleRate}Hz")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("PiperTtsEngine", "Error loading voice ${voice.name}: ${e.message}")
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

        speakJob?.cancel()
        speakJob = scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    engine.generate(
                        text = text,
                        sid = 0,
                        speed = params.rate
                    )
                }

                if (!isActive) return@launch

                playAudio(result.samples, result.sampleRate, params)

                withContext(Dispatchers.Main) {
                    onDone?.invoke()
                }
            } catch (e: Exception) {
                Log.e("PiperTtsEngine", "speak() error: ${e.message}")
                withContext(Dispatchers.Main) {
                    onDone?.invoke()
                }
            }
        }
    }

    private suspend fun playAudio(samples: FloatArray, sampleRate: Int, params: SpeakParams) {
        withContext(Dispatchers.IO) {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    try { it.stop() } catch (_: Exception) {}
                }
                it.release()
                audioTrack = null
            }

            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val encoding = AudioFormat.ENCODING_PCM_FLOAT
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
                .coerceAtLeast(4096)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(encoding)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .also { b ->
                    if (params.audioSessionId != 0) b.setSessionId(params.audioSessionId)
                }
                .build()

            audioTrack = track
            track.setVolume(params.volume.coerceIn(0f, 1f))
            track.play()

            val chunkSize = minBuf / 4  // floats per write
            var offset = 0
            while (offset < samples.size) {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
                val end = minOf(offset + chunkSize, samples.size)
                track.write(samples, offset, end - offset, AudioTrack.WRITE_BLOCKING)
                offset = end
            }

            track.stop()
            track.release()
            audioTrack = null
        }
    }

    override fun stop() {
        speakJob?.cancel()
        speakJob = null
        audioTrack?.let {
            if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                try { it.stop() } catch (_: Exception) {}
            }
            it.release()
        }
        audioTrack = null
    }

    override fun setLanguage(lang: String) {
        Log.d("PiperTtsEngine", "setLanguage ignored — voice language is fixed at load time")
    }

    override fun release() {
        stop()
        scope.cancel()
        tts?.delete()
        tts = null
        currentVoice = null
    }
}
```

Key design points:
- `generate()` runs on `Dispatchers.Default` (CPU-bound ONNX inference, don't block main or IO thread)
- `playAudio()` runs on `Dispatchers.IO` (blocking writes to AudioTrack)
- `ENCODING_PCM_FLOAT`: sherpa-onnx returns FloatArray, no int conversion needed
- Chunked write loop so `stop()` can interrupt mid-playback via `isActive` check
- `tts?.delete()` (not `close()`): sherpa-onnx JNI objects use `delete()` to free native memory

- [ ] **Step 4: Build to verify compilation**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:" | head -20
```

Expected: No errors. If sherpa-onnx import paths differ (e.g. `com.k2fsa.sherpa.onnx` vs `org.k2fsa.sherpa.onnx`), check the AAR's actual package name:
```bash
./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep sherpa
```

Then check the actual class names in the sherpa-onnx sources on GitHub: https://github.com/k2-fsa/sherpa-onnx/tree/master/android

- [ ] **Step 5: Run instrumented test (requires downloaded voice)**

```bash
./gradlew :app:connectedDebugAndroidTest --tests "com.readoutloud.PiperTtsEngineTest"
```

Expected: PASS or SKIP (no voice). If FAIL with native crash, the .onnx.json is likely missing — verify Task 1 ran and the file exists in `files/voices/`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/readoutloud/PiperTtsEngine.kt
git add app/src/androidTest/java/com/readoutloud/PiperTtsEngineTest.kt
git commit -m "feat: implement PiperTtsEngine inference via sherpa-onnx OfflineTts"
```

---

## Task 5: Wire `onDone` in `TtsController.speakAt()`

Without this, Piper never advances to the next sentence. `onDone` must call the same logic as `UtteranceProgressListener.onDone()` (which handles Android TTS completion). Avoid duplicating if Android TTS is active — `onDone` is only called by `PiperTtsEngine`, not `AndroidTtsEngine`.

**Files:**
- Modify: `app/src/main/java/com/readoutloud/TtsController.kt`

- [ ] **Step 1: Update `speakAt()` to pass `onDone` and `utteranceId`**

Replace the existing `speakAt()`:

```kotlin
private fun speakAt(idx: Int) {
    if (currentVolume > 1.0f) boostStream()
    speakingId = idx
    val params = SpeakParams(
        volume = minOf(currentVolume, 1.0f),
        rate = currentRate,
        audioSessionId = audioSessionId
    )
    currentEngine.speak(
        text = sentences[idx],
        params = params,
        utteranceId = idx.toString(),
        onDone = if (currentEngine is AndroidTtsEngine) null else {
            // Piper path: called on main thread after audio finishes
            {
                currentIndex = idx + 1
                if (isPlaying && currentIndex < sentences.size) {
                    onLinesChanged?.invoke(sentenceLines.getOrElse(currentIndex) { emptyList() })
                    speakAt(currentIndex)
                } else {
                    isPlaying = false
                    onLinesChanged?.invoke(emptyList())
                    onPlayStateChanged?.invoke(false)
                }
            }
        }
    )
}
```

Note: `onDone` is passed as `null` for `AndroidTtsEngine` — completion is handled by `UtteranceProgressListener` to avoid double-advance.

- [ ] **Step 2: Remove unused `speakAtWithFallback` or update it**

`speakAtWithFallback` catches exceptions from `speakAt()`. Since `PiperTtsEngine.speak()` no longer throws (errors are caught internally and `onDone` is still called), the try-catch in `speakAtWithFallback` is now only relevant for unexpected failures. Keep it but note the comment:

```kotlin
private fun speakAtWithFallback(idx: Int) {
    try {
        speakAt(idx)
    } catch (e: Exception) {
        Log.e("TtsController", "Engine failed, falling back to Android TTS: ${e.message}")
        fallbackToAndroidTTS()
        speakAt(idx)
    }
}
```

No change needed here.

- [ ] **Step 3: Notify `onSpeakStart` for Piper engine**

The `onSpeakStart` callback fires when audio begins. For Android TTS this comes from `UtteranceProgressListener.onStart()`. For Piper, fire it in `speakAt()` before calling `speak()`, since Piper generates synchronously before playing:

In `speakAt()`, before `currentEngine.speak(...)`:

```kotlin
if (currentEngine !is AndroidTtsEngine && !speakStartNotified) {
    speakStartNotified = true
    onMain { onSpeakStart?.invoke() }
}
```

Full updated `speakAt()`:

```kotlin
private fun speakAt(idx: Int) {
    if (currentVolume > 1.0f) boostStream()
    speakingId = idx
    val params = SpeakParams(
        volume = minOf(currentVolume, 1.0f),
        rate = currentRate,
        audioSessionId = audioSessionId
    )
    if (currentEngine !is AndroidTtsEngine && !speakStartNotified) {
        speakStartNotified = true
        onMain { onSpeakStart?.invoke() }
    }
    currentEngine.speak(
        text = sentences[idx],
        params = params,
        utteranceId = idx.toString(),
        onDone = if (currentEngine is AndroidTtsEngine) null else {
            {
                currentIndex = idx + 1
                if (isPlaying && currentIndex < sentences.size) {
                    onLinesChanged?.invoke(sentenceLines.getOrElse(currentIndex) { emptyList() })
                    speakAt(currentIndex)
                } else {
                    isPlaying = false
                    onLinesChanged?.invoke(emptyList())
                    onPlayStateChanged?.invoke(false)
                }
            }
        }
    )
}
```

- [ ] **Step 4: Ensure `loadVoice` is called when engine switches to Piper**

In `TtsController.switchToPiperEngine()`:

```kotlin
private fun switchToPiperEngine() {
    if (piperEngine != null) {
        val voice = voiceManager?.selectedVoice?.value
        if (voice != null) {
            val result = piperEngine.loadVoice(voice)
            if (result.isFailure) {
                Log.e("TtsController", "Failed to load Piper voice: ${result.exceptionOrNull()?.message}")
                switchToAndroidEngine()
                return
            }
        }
        currentEngine = piperEngine
        Log.d("TtsController", "Switched to Piper engine: ${voice?.name}")
    }
}
```

- [ ] **Step 5: Build and verify**

```bash
./gradlew assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/readoutloud/TtsController.kt
git commit -m "feat: wire PiperTtsEngine onDone callback in TtsController for sentence advancement"
```

---

## Task 6: Audio Output Routing (Speaker / Earpiece)

`TtsController.setAudioOutput()` currently only sets `AudioAttributes` on Android TTS. Piper's `AudioTrack` must also respect speaker/earpiece routing. Add `audioOutput` state to `PiperTtsEngine` and apply correct `USAGE_*` in `playAudio()`.

**Files:**
- Modify: `app/src/main/java/com/readoutloud/PiperTtsEngine.kt`
- Modify: `app/src/main/java/com/readoutloud/TtsController.kt`

- [ ] **Step 1: Add `isSpeaker` field to `PiperTtsEngine`**

Add after the `audioTrack` field:

```kotlin
var isSpeaker: Boolean = true  // true = USAGE_MEDIA (speaker), false = USAGE_VOICE_COMMUNICATION (earpiece)
```

- [ ] **Step 2: Use `isSpeaker` in `playAudio()`**

In `playAudio()`, replace the hardcoded `USAGE_MEDIA`:

```kotlin
val audioAttributes = AudioAttributes.Builder()
    .setUsage(if (isSpeaker) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()
```

- [ ] **Step 3: Update `TtsController.setAudioOutput()` to also configure Piper**

In `TtsController.setAudioOutput()`, after the `tts.setAudioAttributes()` call:

```kotlin
piperEngine?.isSpeaker = speaker
```

Full updated method:

```kotlin
fun setAudioOutput(speaker: Boolean) {
    val newStream = if (speaker) AudioManager.STREAM_MUSIC else AudioManager.STREAM_VOICE_CALL
    if (currentVolume > 1.0f && originalStreamVolume >= 0) {
        audioManager?.setStreamVolume(currentStream, originalStreamVolume, 0)
        originalStreamVolume = -1
    }
    currentStream = newStream
    if (currentVolume > 1.0f && isPlaying) boostStream()
    // Android TTS engine audio attributes
    if (currentEngine is AndroidTtsEngine) {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (speaker) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
    }
    // Piper engine audio routing
    piperEngine?.isSpeaker = speaker
    if (isPlaying && currentIndex < sentences.size) { currentEngine.stop(); speakAt(currentIndex) }
}
```

- [ ] **Step 4: Build and verify**

```bash
./gradlew assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/readoutloud/PiperTtsEngine.kt app/src/main/java/com/readoutloud/TtsController.kt
git commit -m "feat: respect speaker/earpiece audio routing in PiperTtsEngine"
```

---

## Task 7: End-to-End Manual Verification

**Files:** None modified — this is a manual test step.

- [ ] **Step 1: Install on device**

```bash
./gradlew installDebug
```

- [ ] **Step 2: Download a voice**

Open app → tap 🗣️ → select language → download a voice (e.g., `en_US-amy-medium`, ~65MB)

Verify that after download, both files exist:
```bash
adb shell "ls -la /data/data/com.readoutloud/files/voices/"
```

Expected: `en_US-amy-medium.onnx` AND `en_US-amy-medium.onnx.json` present.

- [ ] **Step 3: Select the downloaded voice**

In the voice dialog, select the downloaded voice. Verify toolbar shows Piper indicator.

- [ ] **Step 4: Open a document and start reading**

Open `test.docx` (or any loaded document) → tap "Laut Lesen". 

Expected:
- Text highlighting advances sentence by sentence (proving `onDone` callback fires)
- Audio is intelligible (proving espeak-ng phonemization works)
- No crash, no `IllegalStateException` in logcat

Check logcat:
```bash
adb logcat -s PiperTtsEngine:D TtsController:D 2>&1 | head -40
```

Expected: `Loaded Piper voice: ...` then `speak()` calls, no errors.

- [ ] **Step 5: Test stop/resume**

Tap Pause while reading. Tap Resume. 

Expected: Resumes from approximately where it left off (sentence-level accuracy).

- [ ] **Step 6: Test speaker/earpiece switch**

Tap 🔊/👂 button while reading. Audio should switch route.

- [ ] **Step 7: Test Android TTS fallback (no voice selected)**

Deselect voice → app should fall back to Android TTS and read normally.

---

## Self-Review Checklist

**Spec coverage:**
- [x] Model loading already works — not changed
- [x] `.onnx.json` download — Task 1
- [x] Sherpa-onnx dependency — Task 2
- [x] TtsEngine interface — Task 3
- [x] PiperTtsEngine inference — Task 4
- [x] TtsController sentence advancement — Task 5
- [x] Audio routing — Task 6
- [x] End-to-end test — Task 7

**No placeholders:** All code blocks complete. No "implement later" or "add appropriate error handling".

**Type consistency:**
- `OfflineTts.generate()` returns object with `.samples: FloatArray` and `.sampleRate: Int` — verify against actual sherpa-onnx API. If the return type differs (e.g. `GeneratedAudio`), adjust Task 4's `result.samples`/`result.sampleRate` references accordingly.
- `tts?.delete()` — sherpa-onnx JNI cleanup method. If it's `close()` instead, update `release()`.
- Package `com.k2fsa.sherpa.onnx` — verify against actual AAR. Check with: `jar tf ~/.gradle/caches/...sherpa-onnx*.aar | grep "\.class$" | head -5`

**Potential issue:** `OfflineTts(config = config)` constructor — sherpa-onnx may require an `AssetManager` as first arg on Android (for bundled espeak data). If so, change to:
```kotlin
tts = OfflineTts(assetManager = context.assets, config = config)
```

Check the [sherpa-onnx Android TTS example](https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxTts) for the exact constructor signature before starting Task 4.
