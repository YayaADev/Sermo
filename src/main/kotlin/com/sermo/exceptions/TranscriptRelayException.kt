package com.sermo.exceptions

/**
 * Exception thrown when errors occur during transcript relay operations
 */
sealed class TranscriptRelayException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Exception thrown when transcript relay service fails to start
 */
class TranscriptRelayStartException(
    message: String,
    cause: Throwable? = null,
) : TranscriptRelayException(message, cause)

/**
 * Exception thrown when transcript relay service fails to stop
 */
class TranscriptRelayStopException(
    message: String,
    cause: Throwable? = null,
) : TranscriptRelayException(message, cause)

/**
 * Exception thrown when session registration fails for transcript relay
 */
class TranscriptRelaySessionException(
    message: String,
    cause: Throwable? = null,
) : TranscriptRelayException(message, cause)

/**
 * Exception thrown when transcript validation fails
 */
class TranscriptValidationException(
    message: String,
    cause: Throwable? = null,
) : TranscriptRelayException(message, cause)

/**
 * Exception thrown when WebSocket relay operations fail
 */
class TranscriptWebSocketRelayException(
    message: String,
    cause: Throwable? = null,
) : TranscriptRelayException(message, cause)
