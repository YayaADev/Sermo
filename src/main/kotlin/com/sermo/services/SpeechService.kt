package com.sermo.services

import com.sermo.clients.SpeechClient
import com.sermo.models.TranscriptionResponse
import org.slf4j.LoggerFactory

class SpeechService(
    private val speechClient: SpeechClient
) {
    private val logger = LoggerFactory.getLogger(SpeechService::class.java)

    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        language: String = "en-US",
        contextPhrases: List<String> = emptyList()
    ): Result<TranscriptionResponse> {
        logger.info("Starting speech transcription - Language: $language, Audio size: ${audioBytes.size} bytes")
        
        // Business logic validations
        if (audioBytes.isEmpty()) {
            logger.warn("Audio data is empty")
            return Result.failure(IllegalArgumentException("Audio data cannot be empty"))
        }
        
        if (audioBytes.size > MAX_AUDIO_SIZE) {
            logger.warn("Audio file too large: ${audioBytes.size} bytes (max: $MAX_AUDIO_SIZE)")
            return Result.failure(IllegalArgumentException("Audio file too large. Maximum size: ${MAX_AUDIO_SIZE / 1024 / 1024}MB"))
        }
        
        // Validate language code
        if (!isValidLanguageCode(language)) {
            logger.warn("Invalid language code: $language")
            return Result.failure(IllegalArgumentException("Invalid language code: $language"))
        }
        
        // Call the speech client
        val result = speechClient.transcribe(audioBytes, language, contextPhrases)
        
        result.fold(
            onSuccess = { transcriptionResult ->
                logger.info("Transcription completed successfully: '${transcriptionResult.transcription}' (confidence: ${transcriptionResult.confidence})")
            },
            onFailure = { error ->
                logger.error("Transcription failed", error)
            }
        )
        
        return result
    }
    
    private fun isValidLanguageCode(language: String): Boolean {
        // Basic validation for common language codes
        return language.matches(Regex("^[a-z]{2}(-[A-Z]{2})?$"))
    }
    
    companion object {
        private const val MAX_AUDIO_SIZE = 10 * 1024 * 1024 // 10MB
    }
}