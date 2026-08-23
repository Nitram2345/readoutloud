package de.readoutloud

/**
 * Interface for Text-to-Speech engines.
 * Defines the contract for TTS implementations (e.g., Android TTS, Piper TTS).
 */
interface TtsEngine {
    /**
     * Speak the given text with optional parameters.
     * @param text The text to speak
     * @param params Speech parameters (volume, rate, audioSessionId)
     * @param utteranceId Identifier passed back via onDone (Android TTS also uses this for progress tracking)
     * @param onDone Called on main thread when audio finishes. Ignored by AndroidTtsEngine (uses UtteranceProgressListener instead).
     */
    fun speak(
        text: String,
        params: SpeakParams = SpeakParams(),
        utteranceId: String = "",
        onDone: (() -> Unit)? = null
    )

    /**
     * Stop current speech.
     */
    fun stop()

    /**
     * Set the language for TTS.
     * @param lang Language code (e.g., "de", "en", "en-US")
     */
    fun setLanguage(lang: String)

    /**
     * Release resources.
     */
    fun release()

    /**
     * Check if the engine is ready to speak.
     */
    val isReady: Boolean
}

/**
 * Parameters for speech playback.
 * @param volume Volume level (0.0 to 1.5, where >1.0 may require additional processing)
 * @param rate Speech rate (0.5 to 2.0, default 1.0)
 * @param audioSessionId Android audio session ID for effects (0 if not used)
 */
data class SpeakParams(
    val volume: Float = 1.0f,
    val rate: Float = 1.0f,
    val audioSessionId: Int = 0
)
