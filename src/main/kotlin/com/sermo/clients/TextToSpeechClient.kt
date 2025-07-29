package com.sermo.clients

import com.sermo.models.SynthesisResponse

interface TextToSpeechClient {
    suspend fun synthesize(
        text: String,
        language: String = "en-US",
        voice: String? = null,
        speed: Double = 1.0,
        pitch: Double = 0.0,
    ): Result<SynthesisResponse>
}
