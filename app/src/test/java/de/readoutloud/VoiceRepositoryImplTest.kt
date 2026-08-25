package de.readoutloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceRepositoryImplTest {

    // File keys as they appear in a voices.json entry.
    private val thorstenFiles = listOf(
        "de/de_DE/thorsten/medium/de_DE-thorsten-medium.onnx",
        "de/de_DE/thorsten/medium/de_DE-thorsten-medium.onnx.json",
        "de/de_DE/thorsten/medium/MODEL_CARD",
    )

    @Test
    fun buildsBrowsableModelCardUrl() {
        assertEquals(
            "https://huggingface.co/rhasspy/piper-voices/blob/main/de/de_DE/thorsten/medium/MODEL_CARD",
            VoiceRepositoryImpl.modelCardUrlFrom(thorstenFiles)
        )
    }

    @Test
    fun returnsNullWhenTheEntryHasNoCard() {
        assertNull(VoiceRepositoryImpl.modelCardUrlFrom(thorstenFiles.filterNot { it.endsWith("MODEL_CARD") }))
        assertNull(VoiceRepositoryImpl.modelCardUrlFrom(emptyList()))
    }

    @Test
    fun doesNotMistakeTheModelForTheCard() {
        // A path merely containing the word must not win over the real card.
        val files = listOf("en/en_US/x/low/MODEL_CARD.onnx", "en/en_US/x/low/MODEL_CARD")
        assertEquals(
            "https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/x/low/MODEL_CARD",
            VoiceRepositoryImpl.modelCardUrlFrom(files)
        )
    }
}
