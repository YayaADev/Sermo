package com.sermo.clients

import com.sermo.models.Constants.DEFAULT_LANGUAGE_CODE
import com.sermo.models.SynthesisResponse

/**
 * Converts text to speech to play audio to the user.
 * Commonly used to outplay AI response as voice
 */
interface TextToSpeechClient {
    suspend fun synthesize(
        text: String,
        language: String = DEFAULT_LANGUAGE_CODE,
        voice: String? = null,
        speed: Double = 1.0,
        pitch: Double = 0.0,
    ): Result<SynthesisResponse>
}
