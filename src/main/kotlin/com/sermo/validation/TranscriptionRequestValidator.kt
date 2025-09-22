package com.sermo.validation

import com.sermo.exceptions.ValidationException
import com.sermo.models.Constants.DEFAULT_LANGUAGE_CODE
import com.sermo.models.TranscriptionRequest
import java.util.Base64

object TranscriptionRequestValidator {
    private const val MAX_AUDIO_SIZE = 10 * 1024 * 1024 // 10MB

    fun validate(request: TranscriptionRequest): TranscriptionRequest {
        if (request.audio.isBlank()) {
            throw ValidationException("INVALID_AUDIO", "Audio data cannot be empty")
        }

        // Validate base64 format and decode
        val audioBytes =
            try {
                Base64.getDecoder().decode(request.audio)
            } catch (_: IllegalArgumentException) {
                throw ValidationException("INVALID_BASE64", "Invalid base64 audio data")
            }

        if (audioBytes.isEmpty()) {
            throw ValidationException("INVALID_AUDIO", "Decoded audio data cannot be empty")
        }

        if (audioBytes.size > MAX_AUDIO_SIZE) {
            throw ValidationException("AUDIO_TOO_LARGE", "Audio file too large. Maximum size: ${MAX_AUDIO_SIZE / 1024 / 1024}MB")
        }

        request.language?.let {
            if (!it.matches(Regex("^[a-z]{2}(-[A-Z]{2})?$"))) {
                throw ValidationException("INVALID_LANGUAGE", "Language code format is invalid")
            }
        }

        return request
    }

    fun getDecodedAudio(request: TranscriptionRequest): ByteArray {
        return Base64.getDecoder().decode(request.audio)
    }

    fun getLanguageCode(request: TranscriptionRequest): String {
        return request.language ?: DEFAULT_LANGUAGE_CODE
    }

    fun getContextPhrases(request: TranscriptionRequest): List<String> {
        return request.context?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }
}
