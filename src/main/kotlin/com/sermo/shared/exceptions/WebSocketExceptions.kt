package com.sermo.shared.exceptions

/**
 * Base exception for all WebSocket-related errors in Sermo
 */
sealed class WebSocketException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Exception thrown when WebSocket connection fails to establish
 */
class WebSocketConnectionException(message: String, cause: Throwable? = null) : WebSocketException(message, cause)

/**
 * Exception thrown when WebSocket message routing fails
 */
class WebSocketMessageRoutingException(message: String, cause: Throwable? = null) : WebSocketException(message, cause)

/**
 * Exception thrown when WebSocket message parsing fails
 */
class WebSocketMessageParsingException(message: String, cause: Throwable? = null) : WebSocketException(message, cause)

/**
 * Exception thrown when WebSocket session management fails
 */
class WebSocketSessionException(message: String, cause: Throwable? = null) : WebSocketException(message, cause)

/**
 * Exception thrown when WebSocket frame type is invalid or unsupported
 */
class WebSocketFrameTypeException(message: String, cause: Throwable? = null) : WebSocketException(message, cause)
