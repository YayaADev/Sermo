package com.sermo.services

import com.sermo.clients.TextToSpeechClient
import com.sermo.models.SynthesisResponse
import org.slf4j.LoggerFactory

class TextToSpeechService(
    private val textToSpeechClient: TextToSpeechClient
) {
    private val logger = LoggerFactory.getLogger(TextToSpeechService::class.java)

    suspend fun synthesizeText(
        text: String,
        language: String = "en-US",
        voice: String? = null,
        speed: Double = 1.0,
        pitch: Double = 0.0
    ): Result<SynthesisResponse> {
        logger.info("Starting text synthesis - Language: $language, Text length: ${text.length}")
        
        // Business logic validations
        if (text.isBlank()) {
            logger.warn("Text is empty or blank")
            return Result.failure(IllegalArgumentException("Text cannot be empty"))
        }
        
        if (text.length > MAX_TEXT_LENGTH) {
            logger.warn("Text too long: ${text.length} characters (max: $MAX_TEXT_LENGTH)")
            return Result.failure(IllegalArgumentException("Text too long. Maximum length: $MAX_TEXT_LENGTH characters"))
        }
        
        // Validate language code
        if (!isValidLanguageCode(language)) {
            logger.warn("Invalid language code: $language")
            return Result.failure(IllegalArgumentException("Invalid language code: $language"))
        }
        
        // Validate speed parameter
        if (speed < MIN_SPEED || speed > MAX_SPEED) {
            logger.warn("Invalid speed: $speed (must be between $MIN_SPEED and $MAX_SPEED)")
            return Result.failure(IllegalArgumentException("Speed must be between $MIN_SPEED and $MAX_SPEED"))
        }
        
        // Validate pitch parameter
        if (pitch < MIN_PITCH || pitch > MAX_PITCH) {
            logger.warn("Invalid pitch: $pitch (must be between $MIN_PITCH and $MAX_PITCH)")
            return Result.failure(IllegalArgumentException("Pitch must be between $MIN_PITCH and $MAX_PITCH"))
        }
        
        // Call the text-to-speech client
        val result = textToSpeechClient.synthesize(text, language, voice, speed, pitch)
        
        result.fold(
            onSuccess = { synthesisResult ->
                logger.info("Synthesis completed successfully - Audio format: ${synthesisResult.audioFormat}")
            },
            onFailure = { error ->
                logger.error("Synthesis failed", error)
            }
        )
        
        return result
    }
    
    private fun isValidLanguageCode(language: String): Boolean {
        // Basic validation for common language codes
        return language.matches(Regex("^[a-z]{2}(-[A-Z]{2})?$"))
    }
    
    companion object {
        private const val MAX_TEXT_LENGTH = 5000 // 5000 characters
        private const val MIN_SPEED = 0.25
        private const val MAX_SPEED = 4.0
        private const val MIN_PITCH = -20.0
        private const val MAX_PITCH = 20.0
    }
}