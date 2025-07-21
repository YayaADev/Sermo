package com.sermo.services

import com.sermo.clients.TextToSpeechClient
import com.sermo.models.SynthesisResponse
import org.slf4j.LoggerFactory

class TextToSpeechService(
    private val textToSpeechClient: TextToSpeechClient,
    private val languageDetectionService: LanguageDetectionService
) {
    private val logger = LoggerFactory.getLogger(TextToSpeechService::class.java)

    suspend fun synthesizeText(
        text: String,
        language: String? = null,
        voice: String? = null,
        speed: Double = 1.0,
        pitch: Double = 0.0
    ): Result<SynthesisResponse> {
        logger.info("Synthesizing text - Length: ${text.length}, Language: ${language ?: "auto-detect"}")

        // Detect language only if not provided
        val detectionResult = languageDetectionService.detectLanguageForTTS(text)
        val effectiveLanguage = language ?: detectionResult.language

        return textToSpeechClient.synthesize(
            text = text,
            language = effectiveLanguage,
            voice = voice,
            speed = speed,
            pitch = pitch
        ).map { synthesis ->
            SynthesisResponse(
                audio = synthesis.audio,
                audioFormat = synthesis.audioFormat,
                detectedLanguage = detectionResult.language,
                languageConfidence = detectionResult.confidence,
                durationSeconds = synthesis.durationSeconds,
                voiceUsed = synthesis.voiceUsed
            )
        }.onSuccess { result ->
            logger.info("Synthesis completed - Format: ${result.audioFormat}, Language: ${result.detectedLanguage}")
        }.onFailure { error ->
            logger.error("Synthesis failed for text length ${text.length}", error)
        }
    }
}