package com.sermo.exceptions

/**
 * Base exception for session management errors
 */
open class SessionManagementException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Exception thrown when session state is not found
 */
class SessionNotFoundException(
    sessionId: String,
    cause: Throwable? = null,
) : SessionManagementException("Session not found: $sessionId", cause)

/**
 * Exception thrown when session state is corrupted or invalid
 */
class SessionStateCorruptedException(
    sessionId: String,
    details: String,
    cause: Throwable? = null,
) : SessionManagementException("Session state corrupted for $sessionId: $details", cause)

/**
 * Exception thrown when session has reached maximum capacity or limits
 */
class SessionCapacityExceededException(
    details: String,
    cause: Throwable? = null,
) : SessionManagementException("Session capacity exceeded: $details", cause)

/**
 * Exception thrown when session cleanup or resource management fails
 */
class SessionCleanupException(
    sessionId: String,
    operation: String,
    cause: Throwable? = null,
) : SessionManagementException("Session cleanup failed for $sessionId during $operation", cause)

/**
 * Exception thrown when concurrent access to session state causes conflicts
 */
class SessionConcurrencyException(
    sessionId: String,
    operation: String,
    cause: Throwable? = null,
) : SessionManagementException("Concurrent access conflict for session $sessionId during $operation", cause)
