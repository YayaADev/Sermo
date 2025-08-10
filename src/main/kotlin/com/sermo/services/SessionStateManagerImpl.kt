package com.sermo.services

import com.sermo.exceptions.SessionManagementException
import com.sermo.models.AudioStreamConfig
import com.sermo.models.STTStreamConfig
import com.sermo.models.SessionStateStatistics
import com.sermo.models.StreamingSessionState
import com.sermo.models.TTSStreamConfig
import com.sermo.models.TurnState
import com.sermo.websocket.ConversationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import sermo.protocol.SermoProtocol.ConversationTurn
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Implementation of session state manager
 */
class SessionStateManagerImpl {
    companion object {
        private val logger = LoggerFactory.getLogger(SessionStateManagerImpl::class.java)
        private val DEFAULT_MAX_IDLE_TIME = 30.minutes
        private val CLEANUP_INTERVAL = 5.minutes
    }

    private val sessionStates = ConcurrentHashMap<String, StreamingSessionState>()
    private val sessionMutex = Mutex()
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isShutdown = AtomicBoolean(false)

    init {
        startPeriodicCleanup()
    }

    suspend fun createSession(
        sessionId: String,
        languageCode: String,
    ): Result<StreamingSessionState> {
        if (isShutdown.get()) {
            return Result.failure(SessionManagementException("Session manager is shutdown"))
        }

        return sessionMutex.withLock {
            try {
                if (sessionStates.containsKey(sessionId)) {
                    logger.warn("Session $sessionId already exists, returning existing state")
                    return Result.success(sessionStates[sessionId]!!)
                }

                val sessionState = StreamingSessionState.createInitial(sessionId, languageCode)
                sessionStates[sessionId] = sessionState

                logger.info("Created new session state for $sessionId with language $languageCode")
                Result.success(sessionState)
            } catch (e: Exception) {
                logger.error("Failed to create session state for $sessionId", e)
                Result.failure(SessionManagementException("Failed to create session", e))
            }
        }
    }

    fun getSessionState(sessionId: String): StreamingSessionState? {
        return sessionStates[sessionId]
    }

    suspend fun updateConversationState(
        sessionId: String,
        state: ConversationState,
    ): Result<Unit> {
        return updateSessionState(sessionId) { sessionState ->
            sessionState.conversationState = state
            logger.debug("Updated conversation state for session $sessionId to $state")
        }
    }

    suspend fun updateAudioStreamingState(
        sessionId: String,
        config: AudioStreamConfig?,
        isActive: Boolean,
    ): Result<Unit> {
        return updateSessionState(sessionId) { sessionState ->
            sessionState.audioStreamingState.isActive = isActive
            sessionState.audioStreamingState.streamConfig = config

            if (isActive) {
                sessionState.audioStreamingState.currentState = com.sermo.models.AudioStreamState.STREAMING
                logger.debug("Started audio streaming for session $sessionId")
            } else {
                sessionState.audioStreamingState.currentState = com.sermo.models.AudioStreamState.IDLE
                logger.debug("Stopped audio streaming for session $sessionId")
            }
        }
    }

    suspend fun recordAudioChunkProcessed(
        sessionId: String,
        chunkSize: Int,
    ): Result<Unit> {
        return updateSessionState(sessionId) { sessionState ->
            sessionState.audioStreamingState.updateChunkMetrics(chunkSize)
            logger.debug("Recorded audio chunk for session $sessionId: $chunkSize bytes")
        }
    }

    suspend fun updateSTTStreamingState(
        sessionId: String,
        config: STTStreamConfig?,
        isActive: Boolean,
    ): Result<Unit> {
        return updateSessionState(sessionId) { sessionState ->
            sessionState.sttStreamingState.isActive = isActive
            sessionState.sttStreamingState.streamConfig = config

            if (isActive) {
                sessionState.sttStreamingState.currentState = com.sermo.models.STTStreamState.CONNECTED
                logger.debug("Started STT streaming for session $sessionId")
            } else {
                sessionState.sttStreamingState.currentState = com.sermo.models.STTStreamState.DISCONNECTED
                logger.debug("Stopped STT streaming for session $sessionId")
            }
        }
    }

    suspend fun recordTranscriptReceived(
        sessionId: String,
        transcript: String,
        isFinal: Boolean,
    ): Result<Unit> {
        return updateSessionState(sessionId) { sessionState ->
            sessionState.sttStreamingState.updateTranscriptReceived(transcript, isFinal)
            sessionState.conversationFlowState.updateBufferedTranscript(transcript, isFinal)

            logger.debug(
                "Recorded transcript for session $sessionId: " +
                    "isFinal=$isFinal, text='${transcript.take(50)}${if (transcript.length > 50) "..." else ""}'",
            )
        }
    }

    suspend fun updateConversationFlowState(
        sessionId: String,
        isActive: Boolean,
        turnState: TurnState?,
    ): Result<Unit> {
        return updateSessionState(sessionId) { sessionState ->
            sessionState.conversationFlowState.isActive = isActive

            turnState?.let {
                sessionState.conversationFlowState.currentTurnState = it
            }

            logger.debug("Updated conversation flow for session $sessionId: active=$isActive, turnState=$turnState")
        }
    }

    suspend fun recordConversationTurn(
        sessionId: String,
        turn: ConversationTurn,
    ): Result<Unit> {
        return updateSessionState(sessionId) { sessionState ->
            sessionState.conversationFlowState.addToHistory(turn)
            logger.debug("Recorded conversation turn for session $sessionId: speaker=${turn.speaker}")
        }
    }

    suspend fun updateTTSStreamingState(
        sessionId: String,
        config: TTSStreamConfig?,
        isActive: Boolean,
    ): Result<Unit> {
        return updateSessionState(sessionId) { sessionState ->
            sessionState.ttsStreamingState.isActive = isActive
            sessionState.ttsStreamingState.streamConfig = config

            logger.debug("Updated TTS streaming for session $sessionId: active=$isActive")
        }
    }

    suspend fun recordTTSChunkGenerated(
        sessionId: String,
        chunkSize: Int,
    ): Result<Unit> {
        return updateSessionState(sessionId) { sessionState ->
            sessionState.ttsStreamingState.updateChunkGenerated(chunkSize)
            logger.debug("Recorded TTS chunk for session $sessionId: $chunkSize bytes")
        }
    }

    fun getConversationHistory(sessionId: String): List<ConversationTurn> {
        return sessionStates[sessionId]?.conversationFlowState?.conversationHistory?.toList() ?: emptyList()
    }

    suspend fun resetSessionStreams(sessionId: String): Result<Unit> {
        return updateSessionState(sessionId) { sessionState ->
            sessionState.resetAllStreams()
            logger.info("Reset all streaming states for session $sessionId")
        }
    }

    suspend fun removeSession(sessionId: String): Result<Unit> {
        return sessionMutex.withLock {
            try {
                val sessionState = sessionStates.remove(sessionId)

                if (sessionState != null) {
                    // Cancel any active jobs
                    sessionState.audioStreamingState.streamingJob?.cancel()
                    sessionState.sttStreamingState.gRPCConnectionJob?.cancel()
                    sessionState.ttsStreamingState.streamingJob?.cancel()

                    logger.info("Removed session state for $sessionId")
                    Result.success(Unit)
                } else {
                    logger.warn("Attempted to remove non-existent session: $sessionId")
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                logger.error("Failed to remove session $sessionId", e)
                Result.failure(SessionManagementException("Failed to remove session", e))
            }
        }
    }

    fun getSessionStatistics(sessionId: String): SessionStateStatistics? {
        val sessionState = sessionStates[sessionId] ?: return null

        return SessionStateStatistics(
            sessionId = sessionId,
            sessionDuration = sessionState.getSessionDuration(),
            idleTime = sessionState.getIdleTime(),
            audioStats =
                SessionStateStatistics.AudioStreamingStats(
                    totalChunksReceived = sessionState.audioStreamingState.totalChunksReceived,
                    totalBytesReceived = sessionState.audioStreamingState.totalBytesReceived,
                    averageChunkSize = sessionState.audioStreamingState.averageChunkSize,
                    isActive = sessionState.audioStreamingState.isActive,
                ),
            sttStats =
                SessionStateStatistics.STTStreamingStats(
                    totalTranscriptsReceived = sessionState.sttStreamingState.totalTranscriptsReceived,
                    reconnectionAttempts = sessionState.sttStreamingState.reconnectionAttempts,
                    isActive = sessionState.sttStreamingState.isActive,
                    currentState = sessionState.sttStreamingState.currentState,
                ),
            conversationStats =
                SessionStateStatistics.ConversationStats(
                    totalTurns = sessionState.conversationFlowState.conversationHistory.size,
                    isProcessingResponse = sessionState.conversationFlowState.isProcessingResponse,
                    turnDetectionActive = sessionState.conversationFlowState.turnDetectionActive,
                    currentTurnState = sessionState.conversationFlowState.currentTurnState,
                ),
            ttsStats =
                SessionStateStatistics.TTSStreamingStats(
                    totalChunksGenerated = sessionState.ttsStreamingState.totalChunksGenerated,
                    totalBytesGenerated = sessionState.ttsStreamingState.totalBytesGenerated,
                    isCurrentlyStreaming = sessionState.ttsStreamingState.isCurrentlyStreaming,
                    isActive = sessionState.ttsStreamingState.isActive,
                ),
        )
    }

    fun getActiveSessionIds(): List<String> {
        return sessionStates.keys.toList()
    }

    fun hasActiveStreams(sessionId: String): Boolean {
        return sessionStates[sessionId]?.hasActiveStreams() ?: false
    }

    suspend fun cleanupIdleSessions(maxIdleTime: Duration) {
        sessionMutex.withLock {
            try {
                val currentTime = System.currentTimeMillis()
                val idleSessions =
                    sessionStates.values.filter { session ->
                        session.getIdleTime() > maxIdleTime && !session.hasActiveStreams()
                    }

                idleSessions.forEach { session ->
                    sessionStates.remove(session.sessionId)

                    // Cancel any remaining jobs
                    session.audioStreamingState.streamingJob?.cancel()
                    session.sttStreamingState.gRPCConnectionJob?.cancel()
                    session.ttsStreamingState.streamingJob?.cancel()

                    logger.info(
                        "Cleaned up idle session ${session.sessionId} " +
                            "(idle for ${session.getIdleTime().inWholeMinutes} minutes)",
                    )
                }

                if (idleSessions.isNotEmpty()) {
                    logger.info("Cleaned up ${idleSessions.size} idle sessions")
                }
            } catch (e: Exception) {
                logger.error("Error during session cleanup", e)
            }
        }
    }

    suspend fun shutdown() {
        if (isShutdown.getAndSet(true)) {
            return
        }

        logger.info("Shutting down session state manager...")

        try {
            sessionMutex.withLock {
                // Clean up all sessions
                sessionStates.values.forEach { session ->
                    session.audioStreamingState.streamingJob?.cancel()
                    session.sttStreamingState.gRPCConnectionJob?.cancel()
                    session.ttsStreamingState.streamingJob?.cancel()
                }

                sessionStates.clear()
                logger.info("Cleared all session states")
            }
        } catch (e: Exception) {
            logger.error("Error during session state manager shutdown", e)
        } finally {
            managerScope.cancel()
            logger.info("Session state manager shutdown complete")
        }
    }

    /**
     * Helper function to safely update session state
     */
    private suspend fun updateSessionState(
        sessionId: String,
        updateAction: (StreamingSessionState) -> Unit,
    ): Result<Unit> {
        if (isShutdown.get()) {
            return Result.failure(SessionManagementException("Session manager is shutdown"))
        }

        return try {
            val sessionState =
                sessionStates[sessionId]
                    ?: return Result.failure(SessionManagementException("Session not found: $sessionId"))

            updateAction(sessionState)
            sessionState.updateActivity()

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to update session state for $sessionId", e)
            Result.failure(SessionManagementException("Failed to update session state", e))
        }
    }

    /**
     * Starts periodic cleanup of idle sessions
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (!isShutdown.get()) {
                try {
                    kotlinx.coroutines.delay(CLEANUP_INTERVAL.inWholeMilliseconds)

                    if (!isShutdown.get()) {
                        cleanupIdleSessions(DEFAULT_MAX_IDLE_TIME)
                    }
                } catch (e: Exception) {
                    if (!isShutdown.get()) {
                        logger.error("Error in periodic session cleanup", e)
                    }
                }
            }
        }
    }
}
