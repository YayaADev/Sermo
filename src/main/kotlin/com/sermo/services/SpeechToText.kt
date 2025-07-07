package com.sermo.services

data class TranscriptionResult(
    val transcription: String,
    val confidence: Double,
    val detectedLanguage: String? = null,
    val alternatives: List<String> = emptyList()
)

interface SpeechToText {
    suspend fun transcribe(
        audioBytes: ByteArray,
        language: String = "en-US",
        contextPhrases: List<String> = emptyList()
    ): Result<TranscriptionResult>
}