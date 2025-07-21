package com.sermo.validation

import com.sermo.models.SynthesisRequest
import com.sermo.exceptions.ValidationException

object SynthesisRequestValidator {
    private const val MAX_TEXT_LENGTH = 5000
    private const val MIN_SPEED = 0.25
    private const val MAX_SPEED = 4.0
    private const val MIN_PITCH = -20.0
    private const val MAX_PITCH = 20.0

    fun validate(request: SynthesisRequest): SynthesisRequest {
        if (request.text.isBlank()) {
            throw ValidationException("INVALID_TEXT", "Text cannot be empty")
        }

        if (request.text.length > MAX_TEXT_LENGTH) {
            throw ValidationException("TEXT_TOO_LONG", "Text cannot exceed $MAX_TEXT_LENGTH characters")
        }

        request.speed?.let {
            if (it < MIN_SPEED || it > MAX_SPEED) {
                throw ValidationException("INVALID_SPEED", "Speed must be between $MIN_SPEED and $MAX_SPEED")
            }
        }

        request.pitch?.let {
            if (it < MIN_PITCH || it > MAX_PITCH) {
                throw ValidationException("INVALID_PITCH", "Pitch must be between $MIN_PITCH and $MAX_PITCH")
            }
        }

        request.language?.let {
            if (!it.matches(Regex("^[a-z]{2}(-[A-Z]{2})?$"))) {
                throw ValidationException("INVALID_LANGUAGE", "Language code format is invalid")
            }
        }

        return request
    }
} 