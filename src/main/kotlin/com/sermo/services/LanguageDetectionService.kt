package com.sermo.services

import com.github.pemistahl.lingua.api.LanguageDetector
import com.sermo.models.Constants.DEFAULT_LANGUAGE_CODE
import org.slf4j.LoggerFactory

class LanguageDetectionService(
    private val detector: LanguageDetector,
) {
    private val logger = LoggerFactory.getLogger(LanguageDetectionService::class.java)

    /**
     * Detects language and returns Google Cloud compatible language code
     * This is the primary method used by TTS service
     */
    fun detectLanguageForTTS(text: String): LanguageDetectionResult {
        logger.debug("Detecting language for TTS - text length: ${text.length}")

        if (text.isBlank()) {
            logger.warn("Empty text provided for language detection")
            return LanguageDetectionResult(DEFAULT_LANGUAGE_CODE, 0.0)
        }

        return runCatching {
            val detectedLanguage = detector.detectLanguageOf(text)
            val confidence = detector.computeLanguageConfidenceValues(text)[detectedLanguage] ?: 0.0
            val isoCode = detectedLanguage.isoCode639_1.toString().lowercase()
            val ttsLanguageCode = GOOGLE_TTS_CODES[isoCode] ?: DEFAULT_LANGUAGE_CODE

            logger.debug("Detected language: $isoCode -> $ttsLanguageCode (confidence: $confidence)")
            LanguageDetectionResult(ttsLanguageCode, confidence)
        }.getOrElse { exception ->
            logger.error("Language detection failed, using default", exception)
            LanguageDetectionResult(DEFAULT_LANGUAGE_CODE, 0.0)
        }
    }

    companion object {
        // Optimized mapping focused on TTS-supported languages
        private val GOOGLE_TTS_CODES =
            mapOf(
                "en" to "en-US",
                "ar" to "ar-EG", // Egyptian Arabic as specified
                "es" to "es-ES",
                "fr" to "fr-FR",
                "de" to "de-DE",
                "it" to "it-IT",
                "pt" to "pt-PT",
                "ru" to "ru-RU",
                "zh" to "zh-CN",
                "ja" to "ja-JP",
                "ko" to "ko-KR",
                "hi" to "hi-IN",
                "nl" to "nl-NL",
                "th" to "th-TH",
                "vi" to "vi-VN",
                "tr" to "tr-TR",
            )

        data class LanguageDetectionResult(
            val language: String,
            val confidence: Double,
        )
    }
}
