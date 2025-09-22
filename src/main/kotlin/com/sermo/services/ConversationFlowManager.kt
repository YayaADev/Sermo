package com.sermo.services

import com.sermo.models.StreamingTranscriptResult
import com.sermo.models.TTSStreamConfig
import com.sermo.session.ConversationFlowStartedEvent
import com.sermo.session.ConversationFlowStoppedEvent
import com.sermo.session.ConversationSession
import com.sermo.session.ConversationStateChangedEvent
import com.sermo.session.FinalTranscriptEvent
import com.sermo.session.PartialTranscriptEvent
import com.sermo.session.STTTranscriptReceivedEvent
import com.sermo.session.SessionAwareService
import com.sermo.session.SessionContext
import com.sermo.session.SessionContextRegistry
import com.sermo.session.SessionCreatedEvent
import com.sermo.session.SessionEventBus
import com.sermo.session.SessionTerminatedEvent
import com.sermo.websocket.ConversationState
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import sermo.protocol.SermoProtocol.ConversationTurn

/**
 * Refactored Conversation Flow Manager using centralized session context
 */
class ConversationFlowManager(
    private val conversationService: ConversationService,
    private val streamingTextToSpeechService: StreamingTextToSpeechService,
    private val contextRegistry: SessionContextRegistry,
    eventBus: SessionEventBus,
) : SessionAwareService(eventBus, "ConversationFlowManagerV2") {
    companion object {
        private val logger = LoggerFactory.getLogger(ConversationFlowManager::class.java)
        private const val MAX_CONVERSATION_HISTORY = 20
        private const val MIN_TRANSCRIPT_LENGTH_FOR_RESPONSE = 3
    }

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
     * Start conversation flow - uses context
     */
    fun startConversationFlow(
        sessionId: String,
        languageCode: String,
    ): Result<Unit> {
        return try {
            logger.info("Starting conversation flow for session: $sessionId")

            val context = contextRegistry.requireContext(sessionId)

            // Create conversation session in context
            context.conversationState.session =
                ConversationSession(
                    sessionId = sessionId,
                    languageCode = languageCode,
                    isActive = true,
                    startTime = System.currentTimeMillis(),
                )

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
     * Process transcript result - uses context
     */
    suspend fun processTranscriptResult(
        sessionId: String,
        transcriptResult: StreamingTranscriptResult,
    ): Result<Unit> {
        return try {
            val context = contextRegistry.getContext(sessionId)
            val session = context?.conversationState?.session

            if (session == null || !session.isActive) {
                logger.debug("No active conversation session for transcript: $sessionId")
                return Result.success(Unit)
            }

            if (transcriptResult.isFinal) {
                // Update conversation state in context
                context.conversationState.bufferedTranscript = transcriptResult.transcript.trim()
                context.conversationState.lastTranscriptTime = System.currentTimeMillis()

                logger.debug("Buffered final transcript for session $sessionId: '${context.conversationState.bufferedTranscript}'")

                if (context.conversationState.bufferedTranscript?.isNotBlank() == true) {
                    handleUtteranceComplete(context)
                }
            } else {
                context.conversationState.partialTranscript = transcriptResult.transcript.trim()
                publishPartialTranscript(sessionId, context.conversationState.partialTranscript!!, transcriptResult.confidence)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to process transcript result for session $sessionId", e)
            publishError(sessionId, "TRANSCRIPT_PROCESSING_ERROR", e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Handle utterance completion - uses context
     */
    private suspend fun handleUtteranceComplete(context: SessionContext) {
        try {
            val transcript = context.conversationState.bufferedTranscript
            val session = context.conversationState.session!!

            if (transcript.isNullOrBlank() || transcript.length < MIN_TRANSCRIPT_LENGTH_FOR_RESPONSE) {
                logger.debug("No sufficient transcript for response in session ${session.sessionId}")
                return
            }

            logger.info("Utterance completed for session ${session.sessionId}: '$transcript'")

            publishFinalTranscript(session.sessionId, transcript, 1.0f, session.languageCode)

            // Get conversation history from context
            val history = context.conversationState.conversationHistory

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
                val trimmed = history.takeLast(MAX_CONVERSATION_HISTORY)
                history.clear()
                history.addAll(trimmed)
            }

            // Generate TTS response
            generateAndSendTTSResponse(context, aiResponseText)

            // Clear buffered transcript
            context.conversationState.bufferedTranscript = null
            context.conversationState.partialTranscript = null
        } catch (e: Exception) {
            logger.error("Failed to handle utterance completion for session ${context.sessionInfo.sessionId}", e)
            publishConversationState(context.sessionInfo.sessionId, ConversationState.ERROR)
            publishError(context.sessionInfo.sessionId, "UTTERANCE_PROCESSING_ERROR", e.message ?: "Unknown error")
        }
    }

    private fun generateAndSendTTSResponse(
        context: SessionContext,
        responseText: String,
    ) {
        try {
            val session = context.conversationState.session!!
            logger.debug("Starting TTS streaming for session ${session.sessionId}")

            publishConversationState(session.sessionId, ConversationState.SPEAKING)

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
            logger.error("Failed to generate TTS response for session ${context.sessionInfo.sessionId}", e)
            publishConversationState(context.sessionInfo.sessionId, ConversationState.ERROR)
            publishError(context.sessionInfo.sessionId, "TTS_GENERATION_ERROR", e.message ?: "Unknown error")
        }
    }

    /**
     * Get conversation history - uses context
     */
    fun getConversationHistory(sessionId: String): List<ConversationTurn> {
        return contextRegistry.getContext(sessionId)?.conversationState?.conversationHistory?.toList() ?: emptyList()
    }

    /**
     * Check if conversation is active - uses context
     */
    fun isConversationActive(sessionId: String): Boolean {
        return contextRegistry.getContext(sessionId)?.conversationState?.session?.isActive == true
    }

    private fun publishPartialTranscript(
        sessionId: String,
        transcript: String,
        confidence: Float,
    ) {
        publishEvent(PartialTranscriptEvent(sessionId = sessionId, transcript = transcript, confidence = confidence))
    }

    private fun publishFinalTranscript(
        sessionId: String,
        transcript: String,
        confidence: Float,
        languageCode: String,
    ) {
        publishEvent(
            FinalTranscriptEvent(sessionId = sessionId, transcript = transcript, confidence = confidence, languageCode = languageCode),
        )
    }

    private fun publishConversationState(
        sessionId: String,
        state: ConversationState,
    ) {
        publishEvent(ConversationStateChangedEvent(sessionId = sessionId, state = state))
    }

    override suspend fun onSessionCreated(event: SessionCreatedEvent): Result<Unit> {
        logger.debug("Conversation flow ready for session: ${event.sessionId}")
        return Result.success(Unit)
    }

    override suspend fun onSessionTerminated(event: SessionTerminatedEvent): Result<Unit> {
        return try {
            logger.info("Cleaning up conversation flow for session: ${event.sessionId}")

            // We only mark conversation as inactive in case there are any race conditions
            contextRegistry.getContext(event.sessionId)?.conversationState?.session?.isActive = false

            publishEvent(ConversationFlowStoppedEvent(sessionId = event.sessionId))

            logger.debug("Conversation flow cleanup completed for session: ${event.sessionId}")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to cleanup conversation flow for session ${event.sessionId}", e)
            Result.failure(e)
        }
    }
}
