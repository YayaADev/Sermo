package com.sermo.exceptions

/**
 * Base exception class for all audio streaming pipeline errors
 */
sealed class AudioStreamingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Exception thrown when audio buffer overflows or underflows
 */
class AudioBufferException(message: String, cause: Throwable? = null) : AudioStreamingException(message, cause)

/**
 * Exception thrown when audio chunk processing fails
 */
class AudioProcessingException(message: String, cause: Throwable? = null) : AudioStreamingException(message, cause)

/**
 * Exception thrown when audio stream timing coordination fails
 */
class AudioTimingException(message: String, cause: Throwable? = null) : AudioStreamingException(message, cause)

/**
 * Exception thrown when audio pipeline configuration is invalid
 */
class AudioConfigurationException(message: String, cause: Throwable? = null) : AudioStreamingException(message, cause)

/**
 * Exception thrown when audio pipeline state is invalid for operation
 */
class AudioStateException(message: String, cause: Throwable? = null) : AudioStreamingException(message, cause)

/**
 * Exception thrown when audio chunk size validation fails
 */
class AudioChunkSizeException(message: String, cause: Throwable? = null) : AudioStreamingException(message, cause)
