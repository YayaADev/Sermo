package com.sermo.websocket

import com.sermo.models.AudioStreamConfig
import com.sermo.models.Constants.DEFAULT_LANGUAGE_CODE
import com.sermo.services.AudioStreamingPipeline
import com.sermo.services.ConversationFlowManager
import com.sermo.session.SessionAwareService
import com.sermo.session.SessionCoordinator
import com.sermo.session.SessionCreatedEvent
import com.sermo.session.SessionEventBus
import com.sermo.session.SessionTerminatedEvent
import com.sermo.session.SessionTerminationReason
import io.ktor.server.websocket.WebSocketServerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * WebSocket handler using centralized session management
 */
class WebSocketHandler(
    private val connectionManager: ConnectionManager,
    private val messageRouter: MessageRouter,
    private val sessionCoordinator: SessionCoordinator,
    private val audioStreamingPipeline: AudioStreamingPipeline,
    private val conversationFlowManager: ConversationFlowManager,
    private val json: Json,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    eventBus: SessionEventBus,
) : SessionAwareService(eventBus, "WebSocketHandler") {
    private val sessionMutex = Mutex()
    private val activeSessions = mutableSetOf<String>()

    companion object {
        private val logger = LoggerFactory.getLogger(WebSocketHandler::class.java)
        private const val HEARTBEAT_INTERVAL_MS = 30000L
        private const val SESSION_TIMEOUT_MS = 60000L
        private const val MAX_MESSAGE_SIZE = 1024 * 1024 // 1MB
        private const val DEFAULT_SAMPLE_RATE_HZ = 16000
        private const val DEFAULT_BUFFER_SIZE_MS = 100
    }

    /**
     * Handles new WebSocket connection
     */
    suspend fun handleConnection(session: WebSocketServerSession) {
        var sessionId: String? = null
        val sessionActive = AtomicBoolean(true)

        try {
            // Register the connection
            sessionId =
                withTimeout(5.seconds) {
                    connectionManager.registerSession(session)
                }

            // Create session through coordinator
            val sessionResult =
                sessionCoordinator.createSession(
                    sessionId = sessionId,
                    languageCode = DEFAULT_LANGUAGE_CODE,
                    clientInfo =
                        mapOf(
                            "connectionType" to "websocket",
                            "clientAddress" to (session.call.request.local.remoteHost ?: "unknown"),
                        ),
                )

            if (sessionResult.isFailure) {
                logger.error("Failed to create session: $sessionId", sessionResult.exceptionOrNull())
                sendErrorMessage(sessionId, "SESSION_CREATION_ERROR", "Failed to initialize session")
                return
            }

            sessionMutex.withLock {
                activeSessions.add(sessionId)
            }

            logger.info("WebSocket connection established: $sessionId")

            // Send connection confirmation
            sendConnectionStatus(sessionId, ConnectionStatus.CONNECTED, "Connection established")

            // Set up message routing channels
            val channels = messageRouter.createChannels()

            // Create supervised job for this session
            val sessionJob = SupervisorJob()
            val sessionScope = CoroutineScope(coroutineScope.coroutineContext + sessionJob)

            // Launch session management coroutines
            sessionScope.launch {
                try {
                    processAudioMessages(channels.audioChunkChannel.receiveAsFlow(), sessionId, sessionActive)
                } catch (e: Exception) {
                    logger.error("Audio processing failed for session $sessionId", e)
                    terminateSession(sessionId, SessionTerminationReason.UNRECOVERABLE_ERROR)
                }
            }

            sessionScope.launch {
                try {
                    processControlMessages(channels.controlMessageChannel.receiveAsFlow(), sessionId, sessionActive)
                } catch (e: Exception) {
                    logger.error("Control processing failed for session $sessionId", e)
                    terminateSession(sessionId, SessionTerminationReason.UNRECOVERABLE_ERROR)
                }
            }

            sessionScope.launch {
                try {
                    sendHeartbeat(sessionId, sessionActive)
                } catch (e: Exception) {
                    logger.error("Heartbeat failed for session $sessionId", e)
                }
            }

            try {
                // Main message handling loop
                withTimeout(SESSION_TIMEOUT_MS) {
                    for (frame in session.incoming) {
                        if (!sessionActive.get() || !sessionCoordinator.isSessionActive(sessionId)) {
                            logger.info("Session $sessionId no longer active, breaking message loop")
                            break
                        }

                        try {
                            // Validate message size
                            if (frame.data.size > MAX_MESSAGE_SIZE) {
                                logger.warn("Message too large for session $sessionId: ${frame.data.size} bytes")
                                sendErrorMessage(sessionId, "MESSAGE_TOO_LARGE", "Message exceeds maximum size limit")
                                continue
                            }

                            // Route the incoming message
                            withTimeout(10.seconds) {
                                messageRouter.routeMessage(
                                    frame = frame,
                                    sessionId = sessionId,
                                    audioChunkChannel = channels.audioChunkChannel,
                                    controlMessageChannel = channels.controlMessageChannel,
                                )
                            }

                            // Record activity
                            sessionCoordinator.recordActivity(sessionId)
                        } catch (e: TimeoutCancellationException) {
                            logger.error("Message routing timeout for session $sessionId", e)
                            terminateSession(sessionId, SessionTerminationReason.UNRECOVERABLE_ERROR)
                            break
                        } catch (e: Exception) {
                            logger.error("Error processing frame for session $sessionId", e)
                            sendErrorMessage(sessionId, "MESSAGE_PROCESSING_ERROR", e.message ?: "Unknown error")
                        }
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                logger.info("WebSocket connection closed for session $sessionId")
                terminateSession(sessionId, SessionTerminationReason.CLIENT_DISCONNECT)
            } catch (_: TimeoutCancellationException) {
                logger.warn("Session timeout for $sessionId")
                terminateSession(sessionId, SessionTerminationReason.TIMEOUT)
            } finally {
                // Mark session as inactive
                sessionActive.set(false)

                // Cancel all session jobs
                sessionJob.cancel()

                // Close channels
                channels.audioChunkChannel.close()
                channels.controlMessageChannel.close()
            }
        } catch (e: Exception) {
            logger.error("Error handling WebSocket connection for session $sessionId", e)
            sessionId?.let { terminateSession(it, SessionTerminationReason.UNRECOVERABLE_ERROR) }
        } finally {
            // Cleanup local session tracking
            sessionId?.let { id ->
                try {
                    sessionMutex.withLock {
                        activeSessions.remove(id)
                    }
                    logger.info("WebSocket session cleanup completed: $id")
                } catch (e: Exception) {
                    logger.error("Error during local session cleanup for $id", e)
                }
            }
        }
    }

    /**
     * Terminate session using coordinator
     */
    private suspend fun terminateSession(
        sessionId: String,
        reason: SessionTerminationReason,
    ) {
        logger.info("Terminating session $sessionId with reason: $reason")
        try {
            sessionCoordinator.terminateSession(sessionId, reason)
        } catch (e: Exception) {
            logger.error("Failed to terminate session $sessionId", e)
        }
    }

    /**
     * Process audio messages
     */
    private suspend fun processAudioMessages(
        audioFlow: Flow<AudioChunkData>,
        sessionId: String,
        sessionActive: AtomicBoolean,
    ) {
        var pipelineInitialized = false

        audioFlow.collect { audioChunk ->
            // Double-check session is still active
            if (!sessionActive.get() || !sessionCoordinator.isSessionActive(sessionId)) {
                logger.debug("Session $sessionId no longer active, stopping audio processing")
                return@collect
            }

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

                // Initialize services on first chunk
                if (!pipelineInitialized) {
                    val initResult = initializeStreamingServices(sessionId, audioChunk)
                    if (initResult.isFailure) {
                        logger.error("Failed to initialize streaming services for session $sessionId", initResult.exceptionOrNull())
                        terminateSession(sessionId, SessionTerminationReason.UNRECOVERABLE_ERROR)
                        return@collect
                    }
                    pipelineInitialized = true
                }

                // Forward audio chunk to streaming pipeline
                val processResult = audioStreamingPipeline.processAudioChunk(sessionId, audioChunk.audioData)
                if (processResult.isFailure) {
                    logger.warn(
                        "Failed to process audio chunk ${audioChunk.sequenceNumber} for session $sessionId",
                        processResult.exceptionOrNull(),
                    )
                } else {
                    logger.debug("Audio chunk processed successfully: ${audioChunk.sequenceNumber}")
                }
            } catch (e: Exception) {
                logger.error("Error processing audio chunk ${audioChunk.sequenceNumber} for session $sessionId", e)
                // Don't terminate for individual chunk failures
            }
        }

        logger.debug("Audio processing completed for session $sessionId")
    }

    /**
     * Initialize streaming services
     */
    private suspend fun initializeStreamingServices(
        sessionId: String,
        firstAudioChunk: AudioChunkData,
    ): Result<Unit> {
        try {
            logger.info("Initializing streaming services for session $sessionId")

            // 1. Initialize audio streaming pipeline
            val audioConfig =
                AudioStreamConfig(
                    sampleRateHertz = DEFAULT_SAMPLE_RATE_HZ,
                    chunkSizeBytes = firstAudioChunk.audioData.size,
                    bufferSizeMs = DEFAULT_BUFFER_SIZE_MS,
                )

            val pipelineResult = audioStreamingPipeline.startStreaming(sessionId, audioConfig)
            if (pipelineResult.isFailure) {
                return Result.failure(Exception("Failed to start audio streaming pipeline", pipelineResult.exceptionOrNull()))
            }

            // 2. Start conversation flow manager
            val conversationResult = conversationFlowManager.startConversationFlow(sessionId, DEFAULT_LANGUAGE_CODE)
            if (conversationResult.isFailure) {
                return Result.failure(Exception("Failed to start conversation flow", conversationResult.exceptionOrNull()))
            }

            logger.info("All streaming services initialized successfully for session $sessionId")
            return Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to initialize streaming services for session $sessionId", e)
            return Result.failure(e)
        }
    }

    /**
     * Process control messages
     */
    private suspend fun processControlMessages(
        controlFlow: Flow<ControlMessage>,
        sessionId: String,
        sessionActive: AtomicBoolean,
    ) {
        controlFlow.collect { controlMessage ->
            // Double-check session is still active
            if (!sessionActive.get() || !sessionCoordinator.isSessionActive(sessionId)) {
                logger.debug("Session $sessionId no longer active, stopping control processing")
                return@collect
            }

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
            }
        }
    }

    /**
     * Handle conversation state change messages
     */
    private suspend fun handleConversationStateMessage(message: ControlMessage) {
        logger.debug("Handling conversation state change for session ${message.sessionId}")

        try {
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
     * Handle connection status messages
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
                        terminateSession(message.sessionId, SessionTerminationReason.CLIENT_DISCONNECT)
                    }
                    ConnectionStatus.RECONNECTING -> {
                        logger.info("Client is reconnecting for session ${message.sessionId}")
                        sendConnectionStatus(
                            message.sessionId,
                            ConnectionStatus.CONNECTED,
                            "Reconnection acknowledged",
                        )
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
            terminateSession(message.sessionId, SessionTerminationReason.UNRECOVERABLE_ERROR)
        }
    }

    /**
     * Handle error messages from client
     */
    private suspend fun handleErrorMessage(message: ControlMessage) {
        logger.warn("Client reported error for session ${message.sessionId}: ${message.rawData}")

        try {
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
     * Send heartbeat
     */
    private suspend fun sendHeartbeat(
        sessionId: String,
        sessionActive: AtomicBoolean,
    ) {
        try {
            while (sessionActive.get() &&
                connectionManager.isSessionActive(sessionId) &&
                sessionCoordinator.isSessionActive(sessionId)
            ) {
                delay(HEARTBEAT_INTERVAL_MS)

                if (sessionActive.get() &&
                    connectionManager.isSessionActive(sessionId) &&
                    sessionCoordinator.isSessionActive(sessionId)
                ) {
                    val heartbeat =
                        ConnectionStatusMessage(
                            status = ConnectionStatus.CONNECTED,
                            message = "heartbeat",
                        )
                    val jsonMessage = json.encodeToString(heartbeat)
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

    // Session event handlers
    override suspend fun onSessionCreated(event: SessionCreatedEvent): Result<Unit> {
        logger.debug("Session created: ${event.sessionId}")
        return Result.success(Unit)
    }

    override suspend fun onSessionTerminated(event: SessionTerminatedEvent): Result<Unit> {
        return try {
            logger.info("Cleaning up WebSocket resources for session: ${event.sessionId}")

            // Unregister from connection manager
            connectionManager.unregisterSession(event.sessionId)

            logger.debug("WebSocket cleanup completed for session: ${event.sessionId}")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to cleanup WebSocket resources for session ${event.sessionId}", e)
            Result.failure(e)
        }
    }

    // Public methods for sending messages via WebSocket
    suspend fun sendPartialTranscript(
        sessionId: String,
        transcript: String,
        confidence: Float,
    ) {
        if (!sessionCoordinator.isSessionActive(sessionId)) {
            logger.debug("Session $sessionId not active, skipping partial transcript")
            return
        }

        try {
            val transcriptMessage =
                PartialTranscriptMessage(
                    transcript = transcript,
                    confidence = confidence,
                    timestamp = System.currentTimeMillis(),
                )
            val jsonMessage = json.encodeToString(transcriptMessage)
            connectionManager.sendToSession(sessionId, jsonMessage)
            logger.debug("Sent partial transcript to session $sessionId")
        } catch (e: Exception) {
            logger.error("Failed to send partial transcript to session $sessionId", e)
        }
    }

    suspend fun sendFinalTranscript(
        sessionId: String,
        transcript: String,
        confidence: Float,
        languageCode: String,
    ) {
        if (!sessionCoordinator.isSessionActive(sessionId)) {
            logger.debug("Session $sessionId not active, skipping final transcript")
            return
        }

        try {
            val transcriptMessage =
                FinalTranscriptMessage(
                    transcript = transcript,
                    confidence = confidence,
                    languageCode = languageCode,
                    timestamp = System.currentTimeMillis(),
                )
            val jsonMessage = json.encodeToString(transcriptMessage)
            connectionManager.sendToSession(sessionId, jsonMessage)
            logger.debug("Sent final transcript to session $sessionId")
        } catch (e: Exception) {
            logger.error("Failed to send final transcript to session $sessionId", e)
        }
    }

    suspend fun sendTTSAudio(
        sessionId: String,
        audioData: ByteArray,
    ) {
        if (!sessionCoordinator.isSessionActive(sessionId)) {
            logger.debug("Session $sessionId not active, skipping TTS audio")
            return
        }

        try {
            connectionManager.sendBinaryToSession(sessionId, audioData)
            logger.debug("Sent TTS audio to session $sessionId, size: ${audioData.size} bytes")
        } catch (e: Exception) {
            logger.error("Failed to send TTS audio to session $sessionId", e)
        }
    }

    suspend fun sendConversationState(
        sessionId: String,
        state: ConversationState,
    ) {
        if (!sessionCoordinator.isSessionActive(sessionId)) {
            logger.debug("Session $sessionId not active, skipping conversation state")
            return
        }

        try {
            val stateMessage =
                ConversationStateMessage(
                    state = state,
                    sessionId = sessionId,
                    timestamp = System.currentTimeMillis(),
                )
            val jsonMessage = json.encodeToString(stateMessage)
            connectionManager.sendToSession(sessionId, jsonMessage)
            logger.debug("Sent conversation state to session $sessionId: $state")
        } catch (e: Exception) {
            logger.error("Failed to send conversation state to session $sessionId", e)
        }
    }

    // Utility methods
    private suspend fun sendConnectionStatus(
        sessionId: String,
        status: ConnectionStatus,
        message: String? = null,
    ) {
        try {
            val statusMessage = ConnectionStatusMessage(status = status, message = message)
            val jsonMessage = json.encodeToString(statusMessage)
            connectionManager.sendToSession(sessionId, jsonMessage)
            logger.debug("Sent connection status to session $sessionId: $status")
        } catch (e: Exception) {
            logger.error("Failed to send connection status to session $sessionId", e)
        }
    }

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
            val jsonMessage = json.encodeToString(error)
            connectionManager.sendToSession(sessionId, jsonMessage)
            logger.debug("Sent error message to session $sessionId: $errorCode")
        } catch (e: Exception) {
            logger.error("Failed to send error message to session $sessionId", e)
        }
    }

    override suspend fun shutdown() {
        logger.info("Shutting down WebSocket handler...")

        sessionMutex.withLock {
            activeSessions.clear()
        }

        coroutineScope.cancel()
        logger.info("WebSocket handler shutdown complete")
    }
}
