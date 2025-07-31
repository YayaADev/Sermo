package com.sermo.presentation.websocket

import com.sermo.shared.exceptions.WebSocketSessionException
import io.ktor.server.websocket.WebSocketServerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Main WebSocket handler that manages full-duplex communication for conversation sessions
 */
class WebSocketHandler(
    private val connectionManager: ConnectionManager,
    private val messageRouter: MessageRouter,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            isLenient = true
        }

    private val sessionMutex = Mutex()
    private val activeSessions = mutableSetOf<String>()

    companion object {
        private val logger = LoggerFactory.getLogger(WebSocketHandler::class.java)
        private const val HEARTBEAT_INTERVAL_MS = 30000L
        private const val SESSION_TIMEOUT_MS = 60000L
        private const val MAX_MESSAGE_SIZE = 1024 * 1024 // 1MB
    }

    /**
     * Handles new WebSocket connection and manages the session lifecycle
     */
    suspend fun handleConnection(session: WebSocketServerSession) {
        var sessionId: String? = null
        val sessionActive = AtomicBoolean(true)

        try {
            // Register the session with timeout
            sessionId =
                withTimeout(5.seconds) {
                    connectionManager.registerSession(session)
                }

            sessionMutex.withLock {
                activeSessions.add(sessionId)
            }

            logger.info("WebSocket connection established: $sessionId")

            // Send connection confirmation
            sendConnectionStatus(sessionId, ConnectionStatus.CONNECTED, "Connection established")

            // Set up message routing channels with buffer
            val channels = messageRouter.createChannels()

            // Create supervised job for this session
            val sessionJob = SupervisorJob()
            val sessionScope = CoroutineScope(coroutineScope.coroutineContext + sessionJob)

            // Launch coroutines for handling different types of messages
            val messageProcessingJob =
                sessionScope.launch {
                    try {
                        processAudioMessages(channels.audioChunkChannel.receiveAsFlow(), sessionId, sessionActive)
                    } catch (e: CancellationException) {
                        logger.debug("Audio processing cancelled for session $sessionId")
                        throw e
                    } catch (e: Exception) {
                        logger.error("Audio processing failed for session $sessionId", e)
                        sendErrorMessage(sessionId, "AUDIO_PROCESSING_FATAL", e.message ?: "Audio processing failed")
                    }
                }

            val controlProcessingJob =
                sessionScope.launch {
                    try {
                        processControlMessages(channels.controlMessageChannel.receiveAsFlow(), sessionId, sessionActive)
                    } catch (e: CancellationException) {
                        logger.debug("Control processing cancelled for session $sessionId")
                        throw e
                    } catch (e: Exception) {
                        logger.error("Control processing failed for session $sessionId", e)
                        sendErrorMessage(sessionId, "CONTROL_PROCESSING_FATAL", e.message ?: "Control processing failed")
                    }
                }

            val heartbeatJob =
                sessionScope.launch {
                    try {
                        sendHeartbeat(sessionId, sessionActive)
                    } catch (e: CancellationException) {
                        logger.debug("Heartbeat cancelled for session $sessionId")
                        throw e
                    } catch (e: Exception) {
                        logger.error("Heartbeat failed for session $sessionId", e)
                    }
                }

            try {
                // Main message handling loop with timeout
                withTimeout(SESSION_TIMEOUT_MS) {
                    for (frame in session.incoming) {
                        if (!sessionActive.get()) break

                        try {
                            // Validate message size
                            if (frame.data.size > MAX_MESSAGE_SIZE) {
                                logger.warn("Message too large for session $sessionId: ${frame.data.size} bytes")
                                sendErrorMessage(sessionId, "MESSAGE_TOO_LARGE", "Message exceeds maximum size limit")
                                continue
                            }

                            // Route the incoming message with timeout
                            withTimeout(10.seconds) {
                                messageRouter.routeMessage(
                                    frame = frame,
                                    sessionId = sessionId,
                                    audioChunkChannel = channels.audioChunkChannel,
                                    controlMessageChannel = channels.controlMessageChannel,
                                )
                            }
                        } catch (e: TimeoutCancellationException) {
                            logger.error("Message routing timeout for session $sessionId", e)
                            sendErrorMessage(sessionId, "MESSAGE_TIMEOUT", "Message processing timeout")
                        } catch (e: Exception) {
                            logger.error("Error processing frame for session $sessionId", e)
                            sendErrorMessage(sessionId, "MESSAGE_PROCESSING_ERROR", e.message ?: "Unknown error")
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                logger.info("WebSocket connection closed for session $sessionId")
            } catch (e: TimeoutCancellationException) {
                logger.warn("Session timeout for $sessionId")
                sendErrorMessage(sessionId, "SESSION_TIMEOUT", "Session exceeded maximum duration")
            } finally {
                // Mark session as inactive
                sessionActive.set(false)

                // Cancel all session jobs
                sessionJob.cancelAndJoin()

                // Close channels
                channels.audioChunkChannel.close()
                channels.controlMessageChannel.close()

                logger.debug("Session cleanup completed for $sessionId")
            }
        } catch (e: TimeoutCancellationException) {
            logger.error("Session registration timeout for $sessionId", e)
            sessionId?.let {
                sendErrorMessage(it, "REGISTRATION_TIMEOUT", "Session registration timeout")
            }
        } catch (e: Exception) {
            logger.error("Error handling WebSocket connection for session $sessionId", e)
            sessionId?.let {
                sendErrorMessage(it, "CONNECTION_ERROR", e.message ?: "Connection error")
            }
        } finally {
            // Unregister session
            sessionId?.let { id ->
                try {
                    sessionMutex.withLock {
                        activeSessions.remove(id)
                    }
                    connectionManager.unregisterSession(id)
                    logger.info("WebSocket session cleanup completed: $id")
                } catch (e: Exception) {
                    logger.error("Error during session cleanup for $id", e)
                }
            }
        }
    }

    /**
     * Processes incoming audio chunk messages with proper error handling
     */
    private suspend fun processAudioMessages(
        audioFlow: kotlinx.coroutines.flow.Flow<AudioChunkData>,
        sessionId: String,
        sessionActive: AtomicBoolean,
    ) {
        audioFlow.collect { audioChunk ->
            if (!sessionActive.get()) return@collect

            try {
                // Validate audio chunk
                if (audioChunk.sessionId != sessionId) {
                    logger.warn("Session ID mismatch in audio chunk: expected $sessionId, got ${audioChunk.sessionId}")
                    return@collect
                }

                if (audioChunk.audioData.isEmpty()) {
                    logger.warn("Empty audio data received for session $sessionId")
                    return@collect
                }

                logger.debug(
                    "Processing audio chunk: session=${audioChunk.sessionId}, " +
                        "size=${audioChunk.audioData.size}, " +
                        "seq=${audioChunk.sequenceNumber}",
                )

                // TODO: Forward to speech-to-text processing pipeline
                // This will be implemented in subsequent tasks (BE-03, BE-04)
                // Consider adding:
                // - Audio format validation
                // - Chunk ordering and buffering
                // - Audio quality checks
                // - Rate limiting per session

                logger.debug("Audio chunk queued for processing: ${audioChunk.sequenceNumber}")
            } catch (e: Exception) {
                logger.error("Error processing audio chunk ${audioChunk.sequenceNumber} for session $sessionId", e)
                // Don't rethrow - continue processing other chunks
            }
        }
    }

    /**
     * Processes incoming control messages with enhanced validation
     */
    private suspend fun processControlMessages(
        controlFlow: kotlinx.coroutines.flow.Flow<ControlMessage>,
        sessionId: String,
        sessionActive: AtomicBoolean,
    ) {
        controlFlow.collect { controlMessage ->
            if (!sessionActive.get()) return@collect

            try {
                // Validate control message
                if (controlMessage.sessionId != sessionId) {
                    logger.warn(
                        "Session ID mismatch in control message: expected $sessionId, " +
                            "got ${controlMessage.sessionId}",
                    )
                    return@collect
                }

                logger.debug(
                    "Processing control message: session=${controlMessage.sessionId}, " +
                        "type=${controlMessage.type.value}",
                )

                when (controlMessage.type) {
                    WebSocketMessageType.CONVERSATION_STATE -> {
                        handleConversationStateMessage(controlMessage)
                    }
                    WebSocketMessageType.CONNECTION_STATUS -> {
                        handleConnectionStatusMessage(controlMessage, sessionActive)
                    }
                    WebSocketMessageType.ERROR -> {
                        handleErrorMessage(controlMessage)
                    }
                    WebSocketMessageType.HEARTBEAT_RESPONSE -> {
                        logger.debug("Received heartbeat response from session $sessionId")
                    }
                    else -> {
                        logger.warn(
                            "Unhandled control message type: ${controlMessage.type.value}" +
                                " for session $sessionId",
                        )
                        sendErrorMessage(
                            sessionId,
                            "UNSUPPORTED_MESSAGE_TYPE",
                            "Message type not supported: ${controlMessage.type.value}",
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Error processing control message for session $sessionId", e)
                // Don't rethrow - continue processing other messages
            }
        }
    }

    /**
     * Handles conversation state change messages
     */
    private suspend fun handleConversationStateMessage(message: ControlMessage) {
        logger.debug("Handling conversation state change for session ${message.sessionId}")

        try {
            // TODO: Implement conversation state management
            // This will be integrated with conversation flow management in later tasks
            // Consider adding:
            // - State validation
            // - State transition rules
            // - Persistence of state changes
            // - Broadcasting state changes to relevant sessions

            // Acknowledge state change
            sendConnectionStatus(
                message.sessionId,
                ConnectionStatus.CONNECTED,
                "State change acknowledged",
            )
        } catch (e: Exception) {
            logger.error("Failed to handle conversation state change for session ${message.sessionId}", e)
            sendErrorMessage(
                message.sessionId,
                "STATE_CHANGE_ERROR",
                "Failed to process state change",
            )
        }
    }

    /**
     * Handles connection status messages with session lifecycle management
     */
    private suspend fun handleConnectionStatusMessage(
        message: ControlMessage,
        sessionActive: AtomicBoolean,
    ) {
        logger.debug("Handling connection status for session ${message.sessionId}")

        try {
            if (message.data is ConnectionStatusMessage) {
                when (message.data.status) {
                    ConnectionStatus.DISCONNECTED -> {
                        logger.info("Client requested disconnect for session ${message.sessionId}")
                        sessionActive.set(false)
                        sessionMutex.withLock {
                            activeSessions.remove(message.sessionId)
                        }
                        connectionManager.unregisterSession(message.sessionId)
                    }
                    ConnectionStatus.RECONNECTING -> {
                        logger.info("Client is reconnecting for session ${message.sessionId}")
                        // TODO: Handle reconnection logic
                        // - Validate reconnection token
                        // - Restore session state
                        // - Update connection mapping
                    }
                    ConnectionStatus.CONNECTED -> {
                        logger.debug("Connection status confirmed for session ${message.sessionId}")
                    }
                    else -> {
                        logger.debug(
                            "Connection status update: {} for session {}",
                            message.data.status,
                            message.sessionId,
                        )
                    }
                }
            } else {
                logger.warn("Invalid connection status message format for session ${message.sessionId}")
            }
        } catch (e: Exception) {
            logger.error("Failed to handle connection status for session ${message.sessionId}", e)
            sendErrorMessage(
                message.sessionId,
                "STATUS_HANDLING_ERROR",
                "Failed to process status update",
            )
        }
    }

    /**
     * Handles error messages from client with improved logging
     */
    private suspend fun handleErrorMessage(message: ControlMessage) {
        logger.warn("Client reported error for session ${message.sessionId}: ${message.rawData}")

        try {
            // TODO: Implement client error handling and recovery
            // Consider adding:
            // - Error categorization
            // - Automatic recovery attempts
            // - Error metrics collection
            // - Client-side error acknowledgment

            // For now, just acknowledge the error
            sendConnectionStatus(
                message.sessionId,
                ConnectionStatus.CONNECTED,
                "Error acknowledged",
            )
        } catch (e: Exception) {
            logger.error("Failed to handle client error for session ${message.sessionId}", e)
        }
    }

    /**
     * Sends connection status message to client with retry logic
     */
    private suspend fun sendConnectionStatus(
        sessionId: String,
        status: ConnectionStatus,
        message: String? = null,
    ) {
        try {
            val statusMessage =
                ConnectionStatusMessage(
                    status = status,
                    message = message,
                )
            val jsonMessage = json.encodeToString(ConnectionStatusMessage.serializer(), statusMessage)
            connectionManager.sendToSession(sessionId, jsonMessage)
            logger.debug("Sent connection status to session $sessionId: $status")
        } catch (e: Exception) {
            logger.error("Failed to send connection status to session $sessionId", e)
            // Don't rethrow - this is a notification failure, not a critical error
        }
    }

    /**
     * Sends error message to client with structured error format
     */
    private suspend fun sendErrorMessage(
        sessionId: String,
        errorCode: String,
        errorMessage: String,
        details: String? = null,
    ) {
        try {
            val error =
                ErrorMessage(
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                    details = details,
                    timestamp = System.currentTimeMillis(),
                )
            val jsonMessage = json.encodeToString(ErrorMessage.serializer(), error)
            connectionManager.sendToSession(sessionId, jsonMessage)
            logger.debug("Sent error message to session $sessionId: $errorCode")
        } catch (e: Exception) {
            logger.error("Failed to send error message to session $sessionId", e)
            // Don't rethrow - error reporting failure shouldn't crash the session
        }
    }

    /**
     * Sends periodic heartbeat to maintain connection with better lifecycle management
     */
    private suspend fun sendHeartbeat(
        sessionId: String,
        sessionActive: AtomicBoolean,
    ) {
        try {
            while (sessionActive.get() && connectionManager.isSessionActive(sessionId)) {
                delay(HEARTBEAT_INTERVAL_MS)

                if (sessionActive.get() && connectionManager.isSessionActive(sessionId)) {
                    val heartbeat =
                        ConnectionStatusMessage(
                            status = ConnectionStatus.CONNECTED,
                            message = "heartbeat",
                        )
                    val jsonMessage =
                        json.encodeToString(
                            ConnectionStatusMessage.serializer(),
                            heartbeat,
                        )
                    connectionManager.sendToSession(sessionId, jsonMessage)
                    logger.debug("Sent heartbeat to session $sessionId")
                } else {
                    logger.debug("Session $sessionId no longer active, stopping heartbeat")
                    break
                }
            }
        } catch (e: CancellationException) {
            logger.debug("Heartbeat cancelled for session $sessionId")
            throw e
        } catch (e: Exception) {
            logger.debug("Heartbeat stopped for session $sessionId: ${e.message}")
        }
    }

    /**
     * Sends transcript message to client with validation
     */
    suspend fun sendPartialTranscript(
        sessionId: String,
        transcript: String,
        confidence: Float,
    ) {
        if (!isValidConfidence(confidence)) {
            logger.warn("Invalid confidence value for session $sessionId: $confidence")
            return
        }

        if (transcript.isBlank()) {
            logger.warn("Empty transcript for session $sessionId")
            return
        }

        try {
            val transcriptMessage =
                PartialTranscriptMessage(
                    transcript = transcript,
                    confidence = confidence,
                    timestamp = System.currentTimeMillis(),
                )
            val jsonMessage =
                json.encodeToString(
                    PartialTranscriptMessage.serializer(),
                    transcriptMessage,
                )
            connectionManager.sendToSession(sessionId, jsonMessage)
            logger.debug("Sent partial transcript to session $sessionId")
        } catch (e: Exception) {
            logger.error("Failed to send partial transcript to session $sessionId", e)
            throw WebSocketSessionException("Failed to send partial transcript", e)
        }
    }

    /**
     * Sends final transcript message to client with validation
     */
    suspend fun sendFinalTranscript(
        sessionId: String,
        transcript: String,
        confidence: Float,
        languageCode: String,
    ) {
        if (!isValidConfidence(confidence)) {
            throw WebSocketSessionException("Invalid confidence value: $confidence")
        }

        if (transcript.isBlank()) {
            throw WebSocketSessionException("Empty transcript")
        }

        if (languageCode.isBlank()) {
            throw WebSocketSessionException("Empty language code")
        }

        try {
            val transcriptMessage =
                FinalTranscriptMessage(
                    transcript = transcript,
                    confidence = confidence,
                    languageCode = languageCode,
                    timestamp = System.currentTimeMillis(),
                )
            val jsonMessage =
                json.encodeToString(
                    FinalTranscriptMessage.serializer(),
                    transcriptMessage,
                )
            connectionManager.sendToSession(sessionId, jsonMessage)
            logger.debug("Sent final transcript to session $sessionId")
        } catch (e: Exception) {
            logger.error("Failed to send final transcript to session $sessionId", e)
            throw WebSocketSessionException("Failed to send final transcript", e)
        }
    }

    /**
     * Sends TTS audio data to client with size validation
     */
    suspend fun sendTTSAudio(
        sessionId: String,
        audioData: ByteArray,
    ) {
        if (audioData.isEmpty()) {
            logger.warn("Empty audio data for session $sessionId")
            return
        }

        if (audioData.size > MAX_MESSAGE_SIZE) {
            throw WebSocketSessionException("Audio data too large: ${audioData.size} bytes")
        }

        try {
            connectionManager.sendBinaryToSession(sessionId, audioData)
            logger.debug("Sent TTS audio to session $sessionId, size: ${audioData.size} bytes")
        } catch (e: Exception) {
            logger.error("Failed to send TTS audio to session $sessionId", e)
            throw WebSocketSessionException("Failed to send TTS audio", e)
        }
    }

    /**
     * Sends conversation state update to client with validation
     */
    suspend fun sendConversationState(
        sessionId: String,
        state: ConversationState,
    ) {
        try {
            val stateMessage =
                ConversationStateMessage(
                    state = state,
                    sessionId = sessionId,
                    timestamp = System.currentTimeMillis(),
                )
            val jsonMessage = json.encodeToString(ConversationStateMessage.serializer(), stateMessage)
            connectionManager.sendToSession(sessionId, jsonMessage)
            logger.debug("Sent conversation state to session $sessionId: $state")
        } catch (e: Exception) {
            logger.error("Failed to send conversation state to session $sessionId", e)
            throw WebSocketSessionException("Failed to send conversation state", e)
        }
    }

    /**
     * Utility function to validate confidence values
     */
    private fun isValidConfidence(confidence: Float): Boolean {
        return confidence in 0.0f..1.0f && !confidence.isNaN()
    }

    /**
     * Gracefully shutdown the handler
     */
    suspend fun shutdown() {
        logger.info("Shutting down WebSocket handler...")

        sessionMutex.withLock {
            activeSessions.clear()
        }

        coroutineScope.cancel()
        logger.info("WebSocket handler shutdown complete")
    }
}
