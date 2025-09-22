package com.sermo.services

import com.sermo.clients.TextToSpeechClient
import com.sermo.models.Constants.DEFAULT_LANGUAGE_CODE
import com.sermo.models.SynthesisResponse
import org.slf4j.LoggerFactory

class TextToSpeechService(
    private val textToSpeechClient: TextToSpeechClient,
) {
    private val logger = LoggerFactory.getLogger(TextToSpeechService::class.java)

    suspend fun synthesizeText(
        text: String,
        language: String = DEFAULT_LANGUAGE_CODE,
        voice: String? = null,
        speed: Double = 1.0,
        pitch: Double = 0.0,
    ): Result<SynthesisResponse> {
        logger.info("Synthesizing text - Length: ${text.length}, Language: $language")

        return textToSpeechClient.synthesize(
            text = text,
            language = language,
            voice = voice,
            speed = speed,
            pitch = pitch,
        ).map { synthesis ->
            SynthesisResponse(
                audio = synthesis.audio,
                audioFormat = synthesis.audioFormat,
                detectedLanguage = language,
                durationSeconds = synthesis.durationSeconds,
                voiceUsed = synthesis.voiceUsed,
            )
        }.onSuccess { result ->
            logger.info("Synthesis completed - Format: ${result.audioFormat}, Language: ${result.detectedLanguage}")
        }.onFailure { error ->
            logger.error("Synthesis failed for text length ${text.length}", error)
        }
    }
}
