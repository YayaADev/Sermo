package com.sermo.services

import com.sermo.clients.SpeechToText
import com.sermo.models.TranscriptionResponse
import org.slf4j.LoggerFactory

class SpeechToTextService(
    private val speechToText: SpeechToText,
) {
    private val logger = LoggerFactory.getLogger(SpeechToTextService::class.java)

    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        language: String = "en-US",
        contextPhrases: List<String> = emptyList(),
    ): Result<TranscriptionResponse> {
        logger.info("Starting speech transcription - Language: $language, Audio size: ${audioBytes.size} bytes")

        val result = speechToText.transcribe(audioBytes, language, contextPhrases)

        result.fold(
            onSuccess = { transcriptionResult ->
                logger.info(
                    "Transcription completed successfully: '${transcriptionResult.transcription}' " +
                        "(confidence: ${transcriptionResult.confidence})",
                )
            },
            onFailure = { error ->
                logger.error("Transcription failed", error)
            },
        )

        return result
    }
}
