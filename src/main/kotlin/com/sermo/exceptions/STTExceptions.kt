package com.sermo.exceptions

/**
 * Base exception class for all Speech-to-Text related errors
 */
sealed class STTException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Exception thrown when gRPC streaming connection to Google STT fails to establish
 */
class STTConnectionException(message: String, cause: Throwable? = null) : STTException(message, cause)

/**
 * Exception thrown when gRPC stream times out during long conversations
 */
class STTStreamTimeoutException(message: String, cause: Throwable? = null) : STTException(message, cause)

/**
 * Exception thrown when gRPC stream encounters configuration errors
 */
class STTConfigurationException(message: String, cause: Throwable? = null) : STTException(message, cause)

/**
 * Exception thrown when gRPC stream fails to process audio data
 */
class STTProcessingException(message: String, cause: Throwable? = null) : STTException(message, cause)

/**
 * Exception thrown when gRPC stream encounters authentication issues
 */
class STTAuthenticationException(message: String, cause: Throwable? = null) : STTException(message, cause)
