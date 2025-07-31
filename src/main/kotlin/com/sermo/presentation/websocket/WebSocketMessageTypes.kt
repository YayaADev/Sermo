package com.sermo.presentation.websocket

import kotlinx.serialization.Serializable

/**
 * Constants for WebSocket message handling
 */
object WebSocketConstants {
    const val WEBSOCKET_ENDPOINT = "/ws/conversation"
    const val MAX_FRAME_SIZE = 65536
    const val BUFFER_SIZE = 131072
    const val CONNECTION_TIMEOUT_MS = 30000L
    const val PING_INTERVAL_MS = 25000L
}

/**
 * Enumeration of WebSocket message types for routing
 */
enum class WebSocketMessageType(val value: String) {
    AUDIO_CHUNK("audio_chunk"),
    TRANSCRIPT_PARTIAL("transcript_partial"),
    TRANSCRIPT_FINAL("transcript_final"),
    TTS_AUDIO("tts_audio"),
    CONVERSATION_STATE("conversation_state"),
    CONNECTION_STATUS("connection_status"),
    ERROR("error"),
    HEARTBEAT_RESPONSE("MY HEART HAS BEEN BEAT"),
}

/**
 * Base interface for all WebSocket messages
 */
interface WebSocketMessage {
    val type: String
    val timestamp: Long
}

/**
 * Partial transcript message sent during real-time transcription
 */
@Serializable
data class PartialTranscriptMessage(
    override val type: String = WebSocketMessageType.TRANSCRIPT_PARTIAL.value,
    override val timestamp: Long = System.currentTimeMillis(),
    val transcript: String,
    val confidence: Float,
    val isFinal: Boolean = false,
) : WebSocketMessage

/**
 * Final transcript message sent when speech segment is complete
 */
@Serializable
data class FinalTranscriptMessage(
    override val type: String = WebSocketMessageType.TRANSCRIPT_FINAL.value,
    override val timestamp: Long = System.currentTimeMillis(),
    val transcript: String,
    val confidence: Float,
    val languageCode: String,
) : WebSocketMessage

/**
 * Conversation state change message
 */
@Serializable
data class ConversationStateMessage(
    override val type: String = WebSocketMessageType.CONVERSATION_STATE.value,
    override val timestamp: Long = System.currentTimeMillis(),
    val state: ConversationState,
    val sessionId: String,
) : WebSocketMessage

/**
 * Connection status message
 */
@Serializable
data class ConnectionStatusMessage(
    override val type: String = WebSocketMessageType.CONNECTION_STATUS.value,
    override val timestamp: Long = System.currentTimeMillis(),
    val status: ConnectionStatus,
    val message: String? = null,
) : WebSocketMessage

/**
 * Error message for WebSocket communication
 */
@Serializable
data class ErrorMessage(
    override val type: String = WebSocketMessageType.ERROR.value,
    override val timestamp: Long = System.currentTimeMillis(),
    val errorCode: String,
    val errorMessage: String,
    val details: String? = null,
) : WebSocketMessage

/**
 * Conversation states for state management
 */
enum class ConversationState {
    IDLE,
    LISTENING,
    PROCESSING_SPEECH,
    GENERATING_RESPONSE,
    SPEAKING,
    ERROR,
}

/**
 * Connection status for client synchronization
 */
enum class ConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    RECONNECTING,
    ERROR,
}
