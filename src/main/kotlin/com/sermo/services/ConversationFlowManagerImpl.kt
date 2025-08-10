package com.sermo.services

import com.sermo.exceptions.ConversationFlowException
import com.sermo.models.StreamingTranscriptResult
import com.sermo.models.TTSStreamConfig
import com.sermo.websocket.ConversationState
import com.sermo.websocket.WebSocketHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import sermo.protocol.SermoProtocol.ConversationTurn
import sermo.protocol.SermoProtocol.Speaker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Implementation of conversation flow manager
 */
class ConversationFlowManagerImpl(
    private val conversationService: ConversationService,
    private val streamingTextToSpeechService: StreamingTextToSpeechService,
    private val turnDetectionService: TurnDetectionService,
    private val webSocketHandler: WebSocketHandler,
) : ConversationFlowManager {
    companion object {
        private val logger = LoggerFactory.getLogger(ConversationFlowManagerImpl::class.java)
        private const val MAX_CONVERSATION_HISTORY = 20
        private const val MIN_TRANSCRIPT_LENGTH_FOR_RESPONSE = 3
        private const val TRANSCRIPT_BUFFER_TIMEOUT_MS = 5000L
    }

    private val isRunning = AtomicBoolean(false)
    private val flowMutex = Mutex()
    private val activeSessions = ConcurrentHashMap<String, ConversationSession>()
    private val conversationHistories = ConcurrentHashMap<String, MutableList<ConversationTurn>>()

    private var flowScope: CoroutineScope? = null
    private var turnDetectionJob: Job? = null

    override suspend fun startConversationFlow(
        sessionId: String,
        languageCode: String,
    ): Result<Unit> =
        flowMutex.withLock {
            return try {
                logger.info("Starting conversation flow for session $sessionId with language $languageCode")

                if (activeSessions.containsKey(sessionId)) {
                    logger.warn("Conversation flow already active for session $sessionId")
                    return Result.success(Unit)
                }

                // Initialize flow scope if not running
                if (!isRunning.get()) {
                    flowScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                    isRunning.set(true)
                    startTurnDetectionMonitoring()
                }

                // Create conversation session
                val conversationSession =
                    ConversationSession(
                        sessionId = sessionId,
                        languageCode = languageCode,
                        isActive = true,
                        startTime = System.currentTimeMillis(),
                    )

                activeSessions[sessionId] = conversationSession
                conversationHistories[sessionId] = mutableListOf()

                // Start turn detection for this session
                val turnResult = turnDetectionService.startTurnDetection(sessionId)
                if (turnResult.isFailure) {
                    logger.error("Failed to start turn detection for session $sessionId", turnResult.exceptionOrNull())
                    activeSessions.remove(sessionId)
                    conversationHistories.remove(sessionId)
                    return Result.failure(
                        ConversationFlowException(
                            "Failed to start turn detection",
                            turnResult.exceptionOrNull(),
                        ),
                    )
                }

                logger.info("Conversation flow started successfully for session $sessionId")
                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("Failed to start conversation flow for session $sessionId", e)
                Result.failure(ConversationFlowException("Failed to start conversation flow", e))
            }
        }

    override suspend fun stopConversationFlow(sessionId: String): Result<Unit> =
        flowMutex.withLock {
            return try {
                logger.info("Stopping conversation flow for session $sessionId")

                val session = activeSessions.remove(sessionId)
                if (session == null) {
                    logger.warn("No active conversation flow found for session $sessionId")
                    return Result.success(Unit)
                }

                // Stop turn detection for this session
                val turnResult = turnDetectionService.stopTurnDetection(sessionId)
                if (turnResult.isFailure) {
                    logger.warn("Failed to stop turn detection for session $sessionId", turnResult.exceptionOrNull())
                }

                // Clear conversation history
                conversationHistories.remove(sessionId)

                // If no more active sessions, shutdown flow monitoring
                if (activeSessions.isEmpty()) {
                    turnDetectionJob?.cancel()
                    turnDetectionJob = null
                    flowScope?.cancel()
                    flowScope = null
                    isRunning.set(false)
                }

                logger.info("Conversation flow stopped for session $sessionId")
                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("Failed to stop conversation flow for session $sessionId", e)
                Result.failure(ConversationFlowException("Failed to stop conversation flow", e))
            }
        }

    override suspend fun processTranscriptResult(
        sessionId: String,
        transcriptResult: StreamingTranscriptResult,
    ): Result<Unit> {
        return try {
            val session = activeSessions[sessionId]
            if (session == null) {
                logger.debug("No active conversation session for transcript: $sessionId")
                return Result.success(Unit)
            }

            if (!session.isActive) {
                logger.debug("Conversation session not active for transcript: $sessionId")
                return Result.success(Unit)
            }

            // Buffer the transcript
            if (transcriptResult.isFinal) {
                session.bufferedTranscript = transcriptResult.transcript.trim()
                session.lastTranscriptTime = System.currentTimeMillis()

                logger.debug(
                    "Buffered final transcript for session $sessionId: '${session.bufferedTranscript}'",
                )
            } else {
                // Update partial transcript buffer
                session.partialTranscript = transcriptResult.transcript.trim()
                logger.debug(
                    "Updated partial transcript for session $sessionId: '${session.partialTranscript}'",
                )
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to process transcript result for session $sessionId", e)
            Result.failure(ConversationFlowException("Failed to process transcript result", e))
        }
    }

    override fun getConversationHistory(sessionId: String): List<ConversationTurn> {
        return conversationHistories[sessionId]?.toList() ?: emptyList()
    }

    override fun isConversationActive(sessionId: String): Boolean {
        return activeSessions[sessionId]?.isActive == true
    }

    override suspend fun shutdown() {
        logger.info("Shutting down conversation flow manager...")

        flowMutex.withLock {
            isRunning.set(false)

            // Stop all active sessions
            activeSessions.keys.toList().forEach { sessionId ->
                stopConversationFlow(sessionId)
            }

            // Clear all data
            activeSessions.clear()
            conversationHistories.clear()

            // Cancel monitoring jobs
            turnDetectionJob?.cancel()
            turnDetectionJob = null
            flowScope?.cancel()
            flowScope = null
        }

        logger.info("Conversation flow manager shutdown complete")
    }

    /**
     * Starts monitoring turn detection events to trigger conversation responses
     */
    private fun startTurnDetectionMonitoring() {
        val scope = flowScope ?: throw ConversationFlowException("Flow scope not initialized")

        turnDetectionJob =
            scope.launch {
                try {
                    logger.debug("Starting turn detection event monitoring")

                    turnDetectionService.getTurnEventFlow()
                        .catch { exception ->
                            logger.error("Error in turn detection flow", exception)
                        }
                        .collect { turnEvent ->
                            try {
                                processTurnEvent(turnEvent)
                            } catch (e: Exception) {
                                logger.error("Failed to process turn event", e)
                            }
                        }
                } catch (e: Exception) {
                    logger.error("Turn detection monitoring failed", e)
                }
            }
    }

    /**
     * Processes turn detection events and triggers conversation responses
     */
    private suspend fun processTurnEvent(turnEvent: ConversationTurnEvent) {
        try {
            val session = activeSessions[turnEvent.sessionId]
            if (session == null || !session.isActive) {
                logger.debug("No active session for turn event: ${turnEvent.sessionId}")
                return
            }

            logger.debug(
                "Processing turn event for session ${turnEvent.sessionId}: " +
                    "type=${turnEvent.type}, silence=${turnEvent.silenceDuration.inWholeMilliseconds}ms",
            )

            when (turnEvent.type) {
                TurnEventType.TURN_DETECTED -> {
                    // Silence detected - trigger conversation response
                    handleTurnDetected(session)
                }
                TurnEventType.TURN_STARTED -> {
                    // User started speaking - clear any processing state
                    logger.debug("User started speaking in session ${session.sessionId}")
                }
                TurnEventType.TURN_ENDED -> {
                    // User resumed speaking after silence - cancel any pending processing
                    logger.debug("User resumed speaking in session ${session.sessionId}")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to process turn event: ${turnEvent.type} for session ${turnEvent.sessionId}", e)
        }
    }

    /**
     * Handles turn detection event and generates conversation response
     */
    private suspend fun handleTurnDetected(session: ConversationSession) {
        try {
            val transcript = session.bufferedTranscript
            if (transcript.isNullOrBlank() || transcript.length < MIN_TRANSCRIPT_LENGTH_FOR_RESPONSE) {
                logger.debug("No sufficient transcript for response in session ${session.sessionId}")
                return
            }

            logger.info("Generating conversation response for session ${session.sessionId}: '$transcript'")

            // Get conversation history
            val history = conversationHistories[session.sessionId] ?: mutableListOf()

            // Add user turn to history
            val userTurn =
                ConversationTurn.newBuilder()
                    .setSpeaker(Speaker.USER)
                    .setText(transcript)
                    .setTimestamp(System.currentTimeMillis())
                    .build()

            history.add(userTurn)

            // Generate AI response
            val responseResult =
                conversationService.generateResponse(
                    userMessage = transcript,
                    language = session.languageCode,
                    conversationHistory = history.toList(),
                )

            if (responseResult.isFailure) {
                logger.error(
                    "Failed to generate conversation response for session ${session.sessionId}",
                    responseResult.exceptionOrNull(),
                )
                // Send error to client
                webSocketHandler.sendConversationState(session.sessionId, ConversationState.ERROR)
                return
            }

            val conversationResponse = responseResult.getOrThrow()
            val aiResponseText = conversationResponse.aiResponse

            logger.info("Generated AI response for session ${session.sessionId}: '$aiResponseText'")

            // Add AI turn to history
            val aiTurn =
                ConversationTurn.newBuilder()
                    .setSpeaker(Speaker.AI)
                    .setText(aiResponseText)
                    .setTimestamp(System.currentTimeMillis())
                    .build()

            history.add(aiTurn)

            // Trim conversation history if too long
            if (history.size > MAX_CONVERSATION_HISTORY) {
                val trimmed = history.takeLast(MAX_CONVERSATION_HISTORY).toMutableList()
                conversationHistories[session.sessionId] = trimmed
            }

            // Generate TTS audio for AI response
            generateAndSendTTSResponse(session, aiResponseText)

            // Clear buffered transcript
            session.bufferedTranscript = null
            session.partialTranscript = null
        } catch (e: Exception) {
            logger.error("Failed to handle turn detection for session ${session.sessionId}", e)
            webSocketHandler.sendConversationState(session.sessionId, ConversationState.ERROR)
        }
    }

    /**
     * Generates TTS audio and streams it to the client
     */
    private suspend fun generateAndSendTTSResponse(
        session: ConversationSession,
        responseText: String,
    ) {
        try {
            logger.debug("Starting TTS streaming for session ${session.sessionId}")

            // Update conversation state to indicate AI is speaking
            webSocketHandler.sendConversationState(session.sessionId, ConversationState.SPEAKING)

            // Configure TTS streaming with optimized settings for real-time conversation
            val streamConfig =
                TTSStreamConfig(
                    chunkSizeBytes = 8192, // 8KB chunks for smooth streaming
                    streamingEnabled = true,
                    maxBufferSizeBytes = 131072, // 128KB buffer
                    compressionEnabled = false,
                )

            // Start TTS streaming
            val streamingResult =
                streamingTextToSpeechService.startTTSStreaming(
                    sessionId = session.sessionId,
                    text = responseText,
                    languageCode = session.languageCode,
                    voice = null, // Use default voice
                    streamConfig = streamConfig,
                )

            if (streamingResult.isFailure) {
                logger.error(
                    "Failed to start TTS streaming for session ${session.sessionId}",
                    streamingResult.exceptionOrNull(),
                )
                webSocketHandler.sendConversationState(session.sessionId, ConversationState.ERROR)
                return
            }

            // Get the TTS chunk flow and relay chunks to WebSocket
            val chunkFlow = streamingTextToSpeechService.getTTSChunkFlow(session.sessionId)
            if (chunkFlow == null) {
                logger.error("Failed to get TTS chunk flow for session ${session.sessionId}")
                webSocketHandler.sendConversationState(session.sessionId, ConversationState.ERROR)
                return
            }

            // Start monitoring the flow scope if needed
            val scope = flowScope ?: throw ConversationFlowException("Flow scope not initialized")

            // Launch coroutine to relay TTS chunks via WebSocket
            scope.launch {
                try {
                    chunkFlow.collect { ttsChunk ->
                        if (session.isActive) {
                            // Send audio chunk via WebSocket
                            webSocketHandler.sendTTSAudio(session.sessionId, ttsChunk.audioData)

                            logger.debug(
                                "Relayed TTS chunk ${ttsChunk.sequenceNumber} " +
                                    "to session ${session.sessionId}: ${ttsChunk.audioData.size} bytes",
                            )

                            // Check if this is the last chunk
                            if (ttsChunk.isLast) {
                                logger.info("TTS streaming completed for session ${session.sessionId}")
                                // Update conversation state back to listening
                                webSocketHandler.sendConversationState(session.sessionId, ConversationState.LISTENING)
                            }
                        } else {
                            logger.debug("Session ${session.sessionId} no longer active, stopping TTS relay")
                            return@collect
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to relay TTS chunks for session ${session.sessionId}", e)
                    webSocketHandler.sendConversationState(session.sessionId, ConversationState.ERROR)
                }
            }

            logger.info("TTS streaming initiated for session ${session.sessionId}")
        } catch (e: Exception) {
            logger.error("Failed to generate and stream TTS response for session ${session.sessionId}", e)
            webSocketHandler.sendConversationState(session.sessionId, ConversationState.ERROR)
        }
    }
}

/**
 * Represents an active conversation session
 */
private data class ConversationSession(
    val sessionId: String,
    val languageCode: String,
    var isActive: Boolean,
    val startTime: Long,
    var bufferedTranscript: String? = null,
    var partialTranscript: String? = null,
    var lastTranscriptTime: Long? = null,
)
