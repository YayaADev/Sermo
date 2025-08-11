package com.sermo.services

import com.sermo.models.StreamingTranscriptResult
import com.sermo.models.TTSStreamConfig
import com.sermo.session.ConversationFlowStartedEvent
import com.sermo.session.ConversationFlowStoppedEvent
import com.sermo.session.ConversationStateChangedEvent
import com.sermo.session.FinalTranscriptEvent
import com.sermo.session.PartialTranscriptEvent
import com.sermo.session.STTTranscriptReceivedEvent
import com.sermo.session.SessionAwareService
import com.sermo.session.SessionCreatedEvent
import com.sermo.session.SessionEventBus
import com.sermo.session.SessionTerminatedEvent
import com.sermo.websocket.ConversationState
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import sermo.protocol.SermoProtocol.ConversationTurn
import java.util.concurrent.ConcurrentHashMap

/**
 * Conversation flow manager
 */
class ConversationFlowManager(
    private val conversationService: ConversationService,
    private val streamingTextToSpeechService: StreamingTextToSpeechService,
    eventBus: SessionEventBus,
) : SessionAwareService(eventBus, "ConversationFlowManager") {
    companion object {
        private val logger = LoggerFactory.getLogger(ConversationFlowManager::class.java)
        private const val MAX_CONVERSATION_HISTORY = 20
        private const val MIN_TRANSCRIPT_LENGTH_FOR_RESPONSE = 3
    }

    // Per-session state
    private val conversationHistories = ConcurrentHashMap<String, MutableList<ConversationTurn>>()
    private val conversationSessions = ConcurrentHashMap<String, ConversationSession>()

    init {
        // Subscribe to STT transcript events
        serviceScope.launch {
            eventBus.subscribeToEventType<STTTranscriptReceivedEvent>().collect { event ->
                try {
                    val transcriptResult =
                        StreamingTranscriptResult(
                            transcript = event.transcript,
                            confidence = event.confidence,
                            isFinal = event.isFinal,
                            languageCode = event.languageCode,
                            alternatives = emptyList(),
                        )
                    processTranscriptResult(event.sessionId, transcriptResult)
                } catch (e: Exception) {
                    logger.error("Failed to process STT transcript event for session ${event.sessionId}", e)
                }
            }
        }
    }

    /**
     * Start conversation flow for a session
     */
    fun startConversationFlow(
        sessionId: String,
        languageCode: String,
    ): Result<Unit> {
        return try {
            logger.info("Starting conversation flow for session: $sessionId with language: $languageCode")

            val session =
                ConversationSession(
                    sessionId = sessionId,
                    languageCode = languageCode,
                    isActive = true,
                    startTime = System.currentTimeMillis(),
                )

            conversationSessions[sessionId] = session
            conversationHistories[sessionId] = mutableListOf()

            // Publish event
            publishEvent(
                ConversationFlowStartedEvent(
                    sessionId = sessionId,
                    languageCode = languageCode,
                ),
            )

            logger.info("Conversation flow started for session: $sessionId")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to start conversation flow for session $sessionId", e)
            publishError(sessionId, "CONVERSATION_FLOW_START_ERROR", e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Process transcript result
     */
    suspend fun processTranscriptResult(
        sessionId: String,
        transcriptResult: StreamingTranscriptResult,
    ): Result<Unit> {
        return try {
            val session = conversationSessions[sessionId]
            if (session == null || !session.isActive) {
                logger.debug("No active conversation session for transcript: $sessionId")
                return Result.success(Unit)
            }

            if (transcriptResult.isFinal) {
                session.bufferedTranscript = transcriptResult.transcript.trim()
                session.lastTranscriptTime = System.currentTimeMillis()

                logger.debug("Buffered final transcript for session $sessionId: '${session.bufferedTranscript}'")

                // With Google's singleUtterance, final transcript means end of turn
                if (session.bufferedTranscript?.isNotBlank() == true) {
                    handleUtteranceComplete(session)
                }
            } else {
                // Update partial transcript buffer
                session.partialTranscript = transcriptResult.transcript.trim()

                // Send partial transcript via event bus
                publishPartialTranscript(sessionId, session.partialTranscript!!, transcriptResult.confidence)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to process transcript result for session $sessionId", e)
            publishError(sessionId, "TRANSCRIPT_PROCESSING_ERROR", e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Handle utterance completion
     */
    private suspend fun handleUtteranceComplete(session: ConversationSession) {
        try {
            val transcript = session.bufferedTranscript
            if (transcript.isNullOrBlank() || transcript.length < MIN_TRANSCRIPT_LENGTH_FOR_RESPONSE) {
                logger.debug("No sufficient transcript for response in session ${session.sessionId}")
                return
            }

            logger.info("Utterance completed for session ${session.sessionId}: '$transcript'")

            // Send final transcript via event bus
            publishFinalTranscript(session.sessionId, transcript, 1.0f, session.languageCode)

            // Get conversation history
            val history = conversationHistories[session.sessionId] ?: mutableListOf()

            // Add user turn to history
            val userTurn =
                ConversationTurn.newBuilder()
                    .setSpeaker(sermo.protocol.SermoProtocol.Speaker.USER)
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
                logger.error("Failed to generate response for session ${session.sessionId}", responseResult.exceptionOrNull())
                publishConversationState(session.sessionId, ConversationState.ERROR)
                return
            }

            val conversationResponse = responseResult.getOrThrow()
            val aiResponseText = conversationResponse.aiResponse

            logger.info("Generated AI response for session ${session.sessionId}: '$aiResponseText'")

            // Add AI turn to history
            val aiTurn =
                ConversationTurn.newBuilder()
                    .setSpeaker(sermo.protocol.SermoProtocol.Speaker.AI)
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
            logger.error("Failed to handle utterance completion for session ${session.sessionId}", e)
            publishConversationState(session.sessionId, ConversationState.ERROR)
            publishError(session.sessionId, "UTTERANCE_PROCESSING_ERROR", e.message ?: "Unknown error")
        }
    }

    /**
     * Generate TTS response
     */
    private fun generateAndSendTTSResponse(
        session: ConversationSession,
        responseText: String,
    ) {
        try {
            logger.debug("Starting TTS streaming for session ${session.sessionId}")

            // Update conversation state via event bus
            publishConversationState(session.sessionId, ConversationState.SPEAKING)

            // Start TTS streaming
            val streamingResult =
                streamingTextToSpeechService.startTTSStreaming(
                    sessionId = session.sessionId,
                    text = responseText,
                    languageCode = session.languageCode,
                    voice = null,
                    streamConfig =
                        TTSStreamConfig(
                            chunkSizeBytes = 8192,
                            streamingEnabled = true,
                            maxBufferSizeBytes = 131072,
                            compressionEnabled = false,
                        ),
                )

            if (streamingResult.isFailure) {
                logger.error("Failed to start TTS streaming for session ${session.sessionId}", streamingResult.exceptionOrNull())
                publishConversationState(session.sessionId, ConversationState.ERROR)
                return
            }

            logger.info("TTS streaming initiated for session ${session.sessionId}")
        } catch (e: Exception) {
            logger.error("Failed to generate TTS response for session ${session.sessionId}", e)
            publishConversationState(session.sessionId, ConversationState.ERROR)
            publishError(session.sessionId, "TTS_GENERATION_ERROR", e.message ?: "Unknown error")
        }
    }

    /**
     * Get conversation history for a session
     */
    fun getConversationHistory(sessionId: String): List<ConversationTurn> {
        return conversationHistories[sessionId]?.toList() ?: emptyList()
    }

    /**
     * Check if conversation is active for a session
     */
    fun isConversationActive(sessionId: String): Boolean {
        return conversationSessions[sessionId]?.isActive == true
    }

    // Event publishing methods (instead of direct WebSocket calls)
    private fun publishPartialTranscript(
        sessionId: String,
        transcript: String,
        confidence: Float,
    ) {
        publishEvent(
            PartialTranscriptEvent(
                sessionId = sessionId,
                transcript = transcript,
                confidence = confidence,
            ),
        )
    }

    private fun publishFinalTranscript(
        sessionId: String,
        transcript: String,
        confidence: Float,
        languageCode: String,
    ) {
        publishEvent(
            FinalTranscriptEvent(
                sessionId = sessionId,
                transcript = transcript,
                confidence = confidence,
                languageCode = languageCode,
            ),
        )
    }

    private fun publishConversationState(
        sessionId: String,
        state: ConversationState,
    ) {
        publishEvent(
            ConversationStateChangedEvent(
                sessionId = sessionId,
                state = state,
            ),
        )
    }

    // Session event handlers
    override suspend fun onSessionCreated(event: SessionCreatedEvent): Result<Unit> {
        logger.debug("Conversation flow ready for session: ${event.sessionId}")
        return Result.success(Unit)
    }

    override suspend fun onSessionTerminated(event: SessionTerminatedEvent): Result<Unit> {
        return try {
            logger.info("Cleaning up conversation flow for session: ${event.sessionId}")

            // Stop any active conversation flow
            conversationSessions[event.sessionId]?.let { session ->
                session.isActive = false
                publishEvent(ConversationFlowStoppedEvent(sessionId = event.sessionId))
            }

            // Clean up session-specific resources
            conversationSessions.remove(event.sessionId)
            conversationHistories.remove(event.sessionId)

            logger.debug("Conversation flow cleanup completed for session: ${event.sessionId}")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to cleanup conversation flow for session ${event.sessionId}", e)
            Result.failure(e)
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
