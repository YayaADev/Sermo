package com.sermo.clients

import com.sermo.models.Constants.DEFAULT_LANGUAGE_CODE
import com.sermo.models.TranscriptionResponse

interface SpeechToText {
    suspend fun transcribe(
        audioBytes: ByteArray,
        language: String = DEFAULT_LANGUAGE_CODE,
        contextPhrases: List<String> = emptyList(),
    ): Result<TranscriptionResponse>
}
