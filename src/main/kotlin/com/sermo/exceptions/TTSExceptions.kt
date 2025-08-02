package com.sermo.exceptions

/**
 * Base exception for text-to-speech related errors
 */
open class TTSException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Exception thrown when TTS generation fails
 */
class TTSGenerationException(
    message: String,
    cause: Throwable? = null,
) : TTSException(message, cause)

/**
 * Exception thrown when TTS streaming fails
 */
class TTSStreamingException(
    message: String,
    cause: Throwable? = null,
) : TTSException(message, cause)

/**
 * Exception thrown when TTS audio processing fails
 */
class TTSAudioProcessingException(
    message: String,
    cause: Throwable? = null,
) : TTSException(message, cause)

/**
 * Exception thrown when TTS configuration is invalid
 */
class TTSConfigurationException(
    message: String,
    cause: Throwable? = null,
) : TTSException(message, cause)
