package com.sermo.websocket

import com.sermo.exceptions.WebSocketMessageParsingException
import com.sermo.exceptions.WebSocketMessageRoutingException
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Routes WebSocket messages based on frame type and message content
 */
class MessageRouter(
    private val json: Json,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(MessageRouter::class.java)
        private const val MESSAGE_TYPE_FIELD = "type"
    }

    /**
     * Routes incoming WebSocket frame to appropriate handler
     */
    suspend fun routeMessage(
        frame: Frame,
        sessionId: String,
        audioChunkChannel: SendChannel<AudioChunkData>,
        controlMessageChannel: SendChannel<ControlMessage>,
    ) {
        try {
            when (frame) {
                is Frame.Binary -> {
                    logger.debug("Routing binary frame for session $sessionId, size: ${frame.data.size}")
                    routeBinaryFrame(frame, sessionId, audioChunkChannel)
                }
                is Frame.Text -> {
                    logger.debug("Routing text frame for session $sessionId")
                    routeTextFrame(frame, sessionId, controlMessageChannel)
                }
                is Frame.Close -> {
                    logger.info("Close frame received for session $sessionId")
                    val closeMessage =
                        ControlMessage(
                            sessionId = sessionId,
                            type = WebSocketMessageType.CONNECTION_STATUS,
                            data =
                                ConnectionStatusMessage(
                                    status = ConnectionStatus.DISCONNECTED,
                                    message = "Client initiated close",
                                ),
                        )
                    controlMessageChannel.send(closeMessage)
                }
                is Frame.Ping, is Frame.Pong -> {
                    logger.debug("Ping/Pong frame received for session $sessionId")
                    // Handle ping/pong frames for connection keep-alive
                }
            }
        } catch (e: Exception) {
            logger.error("Error routing message for session $sessionId", e)
            throw WebSocketMessageRoutingException("Failed to route message for session $sessionId", e)
        }
    }

    /**
     * Routes binary frame (assumed to be audio data)
     */
    private suspend fun routeBinaryFrame(
        frame: Frame.Binary,
        sessionId: String,
        audioChunkChannel: SendChannel<AudioChunkData>,
    ) {
        val audioData =
            AudioChunkData(
                sessionId = sessionId,
                audioData = frame.data,
                timestamp = System.currentTimeMillis(),
                sequenceNumber = generateSequenceNumber(),
            )

        audioChunkChannel.send(audioData)
        logger.debug("Routed audio chunk: session=$sessionId, size=${frame.data.size} bytes")
    }

    /**
     * Routes text frame (JSON control messages)
     */
    private suspend fun routeTextFrame(
        frame: Frame.Text,
        sessionId: String,
        controlMessageChannel: SendChannel<ControlMessage>,
    ) {
        try {
            val messageText = frame.readText()
            val messageType = parseMessageType(messageText)

            val controlMessage =
                ControlMessage(
                    sessionId = sessionId,
                    type = messageType,
                    rawData = messageText,
                )

            controlMessageChannel.send(controlMessage)
            logger.debug("Routed control message: session=$sessionId, type=${messageType.value}")
        } catch (e: Exception) {
            throw WebSocketMessageParsingException("Failed to parse text frame for session $sessionId", e)
        }
    }

    /**
     * Parses message type from JSON text. (We should never receive json)
     */
    private fun parseMessageType(messageText: String): WebSocketMessageType {
        return try {
            val jsonObject =
                json.parseToJsonElement(messageText) as? JsonObject
                    ?: throw WebSocketMessageParsingException("Invalid JSON: Not an object")

            val typeString =
                jsonObject[MESSAGE_TYPE_FIELD]?.jsonPrimitive?.content
                    ?: throw WebSocketMessageParsingException("Missing 'type' field in message")

            WebSocketMessageType.entries.firstOrNull { it.value == typeString }
                ?: WebSocketMessageType.ERROR
        } catch (e: Exception) {
            logger.warn("Failed to parse message type from: $messageText", e)
            WebSocketMessageType.ERROR
        }
    }

    /**
     * Creates channels for message routing
     */
    fun createChannels(): MessageChannels {
        return MessageChannels(
            audioChunkChannel = Channel(Channel.UNLIMITED),
            controlMessageChannel = Channel(Channel.UNLIMITED),
        )
    }

    /**
     * Generates sequence number for audio chunks
     */
    private fun generateSequenceNumber(): Long {
        return System.nanoTime()
    }
}

/**
 * Container for message routing channels
 */
data class MessageChannels(
    val audioChunkChannel: Channel<AudioChunkData>,
    val controlMessageChannel: Channel<ControlMessage>,
)

/**
 * Represents incoming audio chunk data
 */
data class AudioChunkData(
    val sessionId: String,
    val audioData: ByteArray,
    val timestamp: Long,
    val sequenceNumber: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioChunkData

        if (sessionId != other.sessionId) return false
        if (!audioData.contentEquals(other.audioData)) return false
        if (timestamp != other.timestamp) return false
        if (sequenceNumber != other.sequenceNumber) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + audioData.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        return result
    }
}

/**
 * Represents incoming control message
 */
data class ControlMessage(
    val sessionId: String,
    val type: WebSocketMessageType,
    val rawData: String? = null,
    val data: WebSocketMessage? = null,
)
