package com.sermo.clients

import com.sermo.models.TranscriptionResponse

interface SpeechToText {
    suspend fun transcribe(
        audioBytes: ByteArray,
        language: String = "en-US",
        contextPhrases: List<String> = emptyList()
    ): Result<TranscriptionResponse>
}