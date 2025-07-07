package com.sermo.clients

import com.sermo.models.generated.TranscriptionResponse

interface SpeechClient {
    suspend fun transcribe(
        audioBytes: ByteArray,
        language: String = "en-US",
        contextPhrases: List<String> = emptyList()
    ): Result<TranscriptionResponse>
}