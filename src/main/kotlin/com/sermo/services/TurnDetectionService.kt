package com.sermo.services

import com.sermo.exceptions.AudioProcessingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages turn detection in conversation using custom silence-based detection
 */
interface TurnDetectionService {
    /**
     * Starts turn detection for a conversation session
     */
    suspend fun startTurnDetection(sessionId: String): Result<Unit>

    /**
     * Processes an audio chunk for turn detection
     */
    suspend fun processAudioChunk(
        sessionId: String,
        audioData: ByteArray,
    ): Result<Unit>

    /**
     * Gets a flow of conversation turn events
     */
    fun getTurnEventFlow(): Flow<ConversationTurnEvent>

    /**
     * Stops turn detection for a session
     */
    suspend fun stopTurnDetection(sessionId: String): Result<Unit>

    /**
     * Gets the current turn detection state
     */
    fun getTurnDetectionState(sessionId: String): TurnDetectionState?

    /**
     * Updates silence detection threshold
     */
    fun updateSilenceThreshold(thresholdMs: Long)

    /**
     * Shuts down the service
     */
    suspend fun shutdown()
}

/**
 * Event emitted when a conversation turn is detected
 */
data class ConversationTurnEvent(
    val sessionId: String,
    val type: TurnEventType,
    val silenceDuration: kotlin.time.Duration,
    val transcript: String?,
    val timestamp: Long,
    val confidence: Float,
)

/**
 * Types of conversation turn events
 */
enum class TurnEventType {
    TURN_STARTED,
    TURN_DETECTED,
    TURN_ENDED,
}

/**
 * Current state of turn detection for a session
 */
data class TurnDetectionState(
    val sessionId: String,
    val isActive: Boolean,
    val isInSilence: Boolean,
    val currentSilenceDuration: kotlin.time.Duration,
    val lastAudioTimestamp: Long?,
)

/**
 * Implementation of turn detection service using silence-based detection
 */
class TurnDetectionServiceImpl(
    private val audioLevelAnalyzer: AudioLevelAnalyzer,
    private val silenceDetector: SilenceDetector,
) : TurnDetectionService {
    companion object {
        private val logger = LoggerFactory.getLogger(TurnDetectionServiceImpl::class.java)
        private const val DEFAULT_SILENCE_THRESHOLD_MS = 1500L
    }

    private val turnEventFlow =
        MutableSharedFlow<ConversationTurnEvent>(
            replay = 0,
            extraBufferCapacity = 20,
        )

    private val activeSessionStates = mutableMapOf<String, SessionTurnState>()
    private val serviceMutex = kotlinx.coroutines.sync.Mutex()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isShutdown = AtomicBoolean(false)

    init {
        // Subscribe to silence detection events
        serviceScope.launch {
            silenceDetector.getSilenceEventFlow().collect { silenceEvent ->
                handleSilenceEvent(silenceEvent)
            }
        }
    }

    override suspend fun startTurnDetection(sessionId: String): Result<Unit> {
        if (isShutdown.get()) {
            return Result.failure(AudioProcessingException("Service is shutdown"))
        }

        return try {
            serviceMutex.lock()
            try {
                if (activeSessionStates.containsKey(sessionId)) {
                    logger.warn("Turn detection already active for session $sessionId")
                    return Result.success(Unit)
                }

                val sessionState =
                    SessionTurnState(
                        sessionId = sessionId,
                        isActive = true,
                        startTime = System.currentTimeMillis(),
                    )

                activeSessionStates[sessionId] = sessionState

                // Reset analyzers for new session
                audioLevelAnalyzer.reset()
                silenceDetector.reset()

                logger.info("Started turn detection for session $sessionId")
                Result.success(Unit)
            } finally {
                serviceMutex.unlock()
            }
        } catch (e: Exception) {
            logger.error("Failed to start turn detection for session $sessionId", e)
            Result.failure(AudioProcessingException("Failed to start turn detection", e))
        }
    }

    override suspend fun processAudioChunk(
        sessionId: String,
        audioData: ByteArray,
    ): Result<Unit> {
        if (isShutdown.get()) {
            return Result.failure(AudioProcessingException("Service is shutdown"))
        }

        return try {
            val sessionState =
                activeSessionStates[sessionId]
                    ?: return Result.failure(AudioProcessingException("Session not active: $sessionId"))

            if (!sessionState.isActive) {
                return Result.failure(AudioProcessingException("Turn detection not active for session: $sessionId"))
            }

            // Analyze audio level
            val audioAnalysis = audioLevelAnalyzer.analyzeAudioLevel(audioData)

            // Process with silence detector
            silenceDetector.processAudioLevel(audioAnalysis)

            // Update session state
            sessionState.lastAudioTimestamp = audioAnalysis.timestamp
            sessionState.totalChunksProcessed++

            logger.debug(
                "Processed audio chunk for session $sessionId: " +
                    "amplitude=${String.format("%.4f", audioAnalysis.amplitude)}, " +
                    "silent=${audioAnalysis.isSilent}",
            )

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to process audio chunk for session $sessionId", e)
            Result.failure(AudioProcessingException("Failed to process audio chunk", e))
        }
    }

    override fun getTurnEventFlow(): Flow<ConversationTurnEvent> = turnEventFlow.asSharedFlow()

    override suspend fun stopTurnDetection(sessionId: String): Result<Unit> {
        return try {
            serviceMutex.lock()
            try {
                val sessionState = activeSessionStates.remove(sessionId)
                if (sessionState == null) {
                    logger.warn("No active turn detection found for session $sessionId")
                    return Result.success(Unit)
                }

                sessionState.isActive = false

                // If this was the last active session, reset detectors
                if (activeSessionStates.isEmpty()) {
                    silenceDetector.reset()
                    audioLevelAnalyzer.reset()
                }

                logger.info("Stopped turn detection for session $sessionId")
                Result.success(Unit)
            } finally {
                serviceMutex.unlock()
            }
        } catch (e: Exception) {
            logger.error("Failed to stop turn detection for session $sessionId", e)
            Result.failure(AudioProcessingException("Failed to stop turn detection", e))
        }
    }

    override fun getTurnDetectionState(sessionId: String): TurnDetectionState? {
        val sessionState = activeSessionStates[sessionId] ?: return null
        val silenceState = silenceDetector.getCurrentState()

        return TurnDetectionState(
            sessionId = sessionId,
            isActive = sessionState.isActive,
            isInSilence = silenceState.isInSilence,
            currentSilenceDuration = silenceState.currentSilenceDuration,
            lastAudioTimestamp = sessionState.lastAudioTimestamp,
        )
    }

    override fun updateSilenceThreshold(thresholdMs: Long) {
        try {
            silenceDetector.updateSilenceThreshold(thresholdMs)
            logger.info("Updated silence threshold to ${thresholdMs}ms for all sessions")
        } catch (e: Exception) {
            logger.error("Failed to update silence threshold", e)
        }
    }

    override suspend fun shutdown() {
        if (isShutdown.getAndSet(true)) {
            return
        }

        logger.info("Shutting down turn detection service...")

        try {
            serviceMutex.lock()
            try {
                // Stop all active sessions
                activeSessionStates.keys.toList().forEach { sessionId ->
                    stopTurnDetection(sessionId)
                }

                // Reset detectors
                silenceDetector.reset()
                audioLevelAnalyzer.reset()

                logger.info("Turn detection service shutdown complete")
            } finally {
                serviceMutex.unlock()
            }
        } catch (e: Exception) {
            logger.error("Error during turn detection service shutdown", e)
        } finally {
            serviceScope.cancel()
        }
    }

    /**
     * Handles silence detection events and converts them to conversation turn events
     */
    private suspend fun handleSilenceEvent(silenceEvent: SilenceDetectionEvent) {
        try {
            // Find the active session (in this simple implementation, we assume single session)
            val activeSession = activeSessionStates.values.firstOrNull { it.isActive }
            if (activeSession == null) {
                logger.debug("No active session found for silence event: ${silenceEvent.type}")
                return
            }

            val turnEvent =
                when (silenceEvent.type) {
                    SilenceEventType.SILENCE_STARTED -> {
                        ConversationTurnEvent(
                            sessionId = activeSession.sessionId,
                            type = TurnEventType.TURN_STARTED,
                            silenceDuration = silenceEvent.silenceDuration,
                            transcript = null,
                            timestamp = silenceEvent.timestamp,
                            confidence = silenceEvent.confidenceLevel,
                        )
                    }
                    SilenceEventType.SILENCE_DETECTED -> {
                        ConversationTurnEvent(
                            sessionId = activeSession.sessionId,
                            type = TurnEventType.TURN_DETECTED,
                            silenceDuration = silenceEvent.silenceDuration,
                            transcript = null, // Will be filled by conversation service
                            timestamp = silenceEvent.timestamp,
                            confidence = silenceEvent.confidenceLevel,
                        )
                    }
                    SilenceEventType.SPEECH_RESUMED -> {
                        ConversationTurnEvent(
                            sessionId = activeSession.sessionId,
                            type = TurnEventType.TURN_ENDED,
                            silenceDuration = silenceEvent.silenceDuration,
                            transcript = null,
                            timestamp = silenceEvent.timestamp,
                            confidence = silenceEvent.confidenceLevel,
                        )
                    }
                }

            // Emit turn event
            val emitted = turnEventFlow.tryEmit(turnEvent)
            if (!emitted) {
                logger.warn("Failed to emit turn event: buffer full")
            } else {
                logger.info(
                    "Emitted turn event: session=${turnEvent.sessionId}, " +
                        "type=${turnEvent.type}, duration=${turnEvent.silenceDuration.inWholeMilliseconds}ms",
                )
            }
        } catch (e: Exception) {
            logger.error("Error handling silence event", e)
        }
    }

    /**
     * Internal state for tracking turn detection per session
     */
    private data class SessionTurnState(
        val sessionId: String,
        var isActive: Boolean,
        val startTime: Long,
        var lastAudioTimestamp: Long? = null,
        var totalChunksProcessed: Int = 0,
    )
}
