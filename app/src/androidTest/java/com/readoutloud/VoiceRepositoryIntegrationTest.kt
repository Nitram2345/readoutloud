package com.readoutloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for VoiceRepositoryImpl — require device/emulator with internet access.
 * These tests make real HTTP requests to HuggingFace and verify the full parsing pipeline.
 */
@RunWith(AndroidJUnit4::class)
class VoiceRepositoryIntegrationTest {

    private val repo = VoiceRepositoryImpl()

    @Test
    fun fetchVoices_german_returnsNonEmptyList() = runBlocking {
        val voices = repo.getVoicesForLanguage("de")

        assertTrue(
            "Expected German voices from HuggingFace, got empty list. " +
            "Check INTERNET permission in manifest and network connectivity.",
            voices.isNotEmpty()
        )
    }

    @Test
    fun fetchVoices_german_allHaveOnnxUrl() = runBlocking {
        val voices = repo.getVoicesForLanguage("de")
        assertTrue("Need voices to validate", voices.isNotEmpty())

        voices.forEach { voice ->
            assertTrue(
                "Voice ${voice.name} has empty onnxUrl",
                voice.onnxUrl.isNotBlank()
            )
            assertTrue(
                "Voice ${voice.name} onnxUrl should point to .onnx file: ${voice.onnxUrl}",
                voice.onnxUrl.endsWith(".onnx")
            )
            assertTrue(
                "Voice ${voice.name} onnxUrl should be HTTPS: ${voice.onnxUrl}",
                voice.onnxUrl.startsWith("https://")
            )
        }
    }

    @Test
    fun fetchVoices_german_allHaveValidMetadata() = runBlocking {
        val voices = repo.getVoicesForLanguage("de")
        assertTrue("Need voices to validate", voices.isNotEmpty())

        voices.forEach { voice ->
            assertTrue("Voice name is blank", voice.name.isNotBlank())
            assertTrue("Voice language is blank for ${voice.name}", voice.language.isNotBlank())
            assertTrue("Voice sizeMb <= 0 for ${voice.name}", voice.sizeMb > 0.0)
            assertTrue("Voice sampleRate <= 0 for ${voice.name}", voice.sampleRate > 0)
        }
    }

    @Test
    fun fetchVoices_german_languageCodesAreGerman() = runBlocking {
        val voices = repo.getVoicesForLanguage("de")
        assertTrue("Need voices to validate", voices.isNotEmpty())

        voices.forEach { voice ->
            assertTrue(
                "Expected German language code (de/de_DE/de_*), got '${voice.language}' for ${voice.name}",
                voice.language.startsWith("de", ignoreCase = true)
            )
        }
    }

    @Test
    fun fetchVoices_english_returnsNonEmptyList() = runBlocking {
        val voices = repo.getVoicesForLanguage("en")
        assertTrue(
            "Expected English voices from HuggingFace, got empty list.",
            voices.isNotEmpty()
        )
    }

    @Test
    fun fetchVoices_jsonConfigUrlDerivable() = runBlocking {
        // Verify that .onnx.json URL can be derived from onnxUrl (needed by sherpa-onnx)
        val voices = repo.getVoicesForLanguage("de")
        assertTrue("Need voices to validate", voices.isNotEmpty())

        val firstVoice = voices.first()
        val jsonUrl = firstVoice.onnxUrl + ".json"
        assertTrue("JSON config URL should end in .onnx.json: $jsonUrl", jsonUrl.endsWith(".onnx.json"))
    }
}
