package com.sermo.models

import com.sermo.models.Constants.DEFAULT_LANGUAGE_CODE
import com.sermo.websocket.ConversationState
import kotlinx.coroutines.Job
import sermo.protocol.SermoProtocol.ConversationTurn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Comprehensive session state for managing concurrent streaming operations
 */
data class StreamingSessionState(
    val sessionId: String,
    val languageCode: String,
    var conversationState: ConversationState,
    var audioStreamingState: AudioStreamingSessionState,
    var sttStreamingState: STTStreamingSessionState,
    var conversationFlowState: ConversationFlowSessionState,
    var ttsStreamingState: TTSStreamingSessionState,
    val createdAt: Long = System.currentTimeMillis(),
    var lastActivityAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /**
         * Creates initial session state for new WebSocket connection
         */
        fun createInitial(
            sessionId: String,
            languageCode: String = DEFAULT_LANGUAGE_CODE,
        ): StreamingSessionState {
            return StreamingSessionState(
                sessionId = sessionId,
                languageCode = languageCode,
                conversationState = ConversationState.IDLE,
                audioStreamingState = AudioStreamingSessionState.createInitial(sessionId),
                sttStreamingState = STTStreamingSessionState.createInitial(sessionId),
                conversationFlowState = ConversationFlowSessionState.createInitial(sessionId),
                ttsStreamingState = TTSStreamingSessionState.createInitial(sessionId),
            )
        }
    }

    /**
     * Updates last activity timestamp to current time
     */
    fun updateActivity() {
        lastActivityAt = System.currentTimeMillis()
    }

    /**
     * Gets session duration in milliseconds
     */
    fun getSessionDuration(): Duration {
        return (System.currentTimeMillis() - createdAt).milliseconds
    }

    /**
     * Gets idle time since last activity in milliseconds
     */
    fun getIdleTime(): Duration {
        return (System.currentTimeMillis() - lastActivityAt).milliseconds
    }

    /**
     * Checks if any streaming component is currently active
     */
    fun hasActiveStreams(): Boolean {
        return audioStreamingState.isActive ||
            sttStreamingState.isActive ||
            conversationFlowState.isActive ||
            ttsStreamingState.isActive
    }

    /**
     * Resets all streaming states to initial values for fresh restart
     */
    fun resetAllStreams() {
        conversationState = ConversationState.IDLE
        audioStreamingState = AudioStreamingSessionState.createInitial(sessionId)
        sttStreamingState = STTStreamingSessionState.createInitial(sessionId)
        conversationFlowState = ConversationFlowSessionState.createInitial(sessionId)
        ttsStreamingState = TTSStreamingSessionState.createInitial(sessionId)
        updateActivity()
    }
}

/**
 * Audio streaming state per session
 */
data class AudioStreamingSessionState(
    val sessionId: String,
    var isActive: Boolean = false,
    var currentState: AudioStreamState = AudioStreamState.IDLE,
    var streamConfig: AudioStreamConfig? = null,
    var totalChunksReceived: Long = 0L,
    var totalBytesReceived: Long = 0L,
    var lastChunkTimestamp: Long? = null,
    var averageChunkSize: Double = 0.0,
    var streamingJob: Job? = null,
) {
    companion object {
        fun createInitial(sessionId: String): AudioStreamingSessionState {
            return AudioStreamingSessionState(
                sessionId = sessionId,
                isActive = false,
                currentState = AudioStreamState.IDLE,
            )
        }
    }

    /**
     * Updates metrics when new audio chunk is processed
     */
    fun updateChunkMetrics(chunkSize: Int) {
        totalChunksReceived++
        totalBytesReceived += chunkSize
        lastChunkTimestamp = System.currentTimeMillis()

        // Calculate running average chunk size
        averageChunkSize =
            if (totalChunksReceived > 0) {
                totalBytesReceived.toDouble() / totalChunksReceived
            } else {
                0.0
            }
    }

    /**
     * Resets streaming state for fresh start
     */
    fun reset() {
        isActive = false
        currentState = AudioStreamState.IDLE
        streamConfig = null
        totalChunksReceived = 0L
        totalBytesReceived = 0L
        lastChunkTimestamp = null
        averageChunkSize = 0.0
        streamingJob?.cancel()
        streamingJob = null
    }
}

/**
 * Speech-to-Text streaming state per session
 */
data class STTStreamingSessionState(
    val sessionId: String,
    var isActive: Boolean = false,
    var currentState: STTStreamState = STTStreamState.DISCONNECTED,
    var streamConfig: STTStreamConfig? = null,
    var gRPCConnectionJob: Job? = null,
    var lastTranscriptReceived: Long? = null,
    var totalTranscriptsReceived: Long = 0L,
    var currentPartialTranscript: String? = null,
    var lastFinalTranscript: String? = null,
    var reconnectionAttempts: Int = 0,
) {
    companion object {
        private const val MAX_RECONNECTION_ATTEMPTS = 5

        fun createInitial(sessionId: String): STTStreamingSessionState {
            return STTStreamingSessionState(
                sessionId = sessionId,
                isActive = false,
                currentState = STTStreamState.DISCONNECTED,
            )
        }
    }

    /**
     * Updates state when transcript is received
     */
    fun updateTranscriptReceived(
        transcript: String,
        isFinal: Boolean,
    ) {
        lastTranscriptReceived = System.currentTimeMillis()
        totalTranscriptsReceived++

        if (isFinal) {
            lastFinalTranscript = transcript
            currentPartialTranscript = null
        } else {
            currentPartialTranscript = transcript
        }
    }

    /**
     * Checks if reconnection should be attempted
     */
    fun shouldAttemptReconnection(): Boolean {
        return reconnectionAttempts < MAX_RECONNECTION_ATTEMPTS
    }

    /**
     * Increments reconnection attempt counter
     */
    fun incrementReconnectionAttempts() {
        reconnectionAttempts++
    }

    /**
     * Resets STT streaming state
     */
    fun reset() {
        isActive = false
        currentState = STTStreamState.DISCONNECTED
        streamConfig = null
        gRPCConnectionJob?.cancel()
        gRPCConnectionJob = null
        lastTranscriptReceived = null
        totalTranscriptsReceived = 0L
        currentPartialTranscript = null
        lastFinalTranscript = null
        reconnectionAttempts = 0
    }
}

/**
 * Conversation flow state per session
 */
data class ConversationFlowSessionState(
    val sessionId: String,
    var isActive: Boolean = false,
    var conversationHistory: MutableList<ConversationTurn> = mutableListOf(),
    var bufferedTranscript: String? = null,
    var partialTranscript: String? = null,
    var lastTranscriptTime: Long? = null,
    var isProcessingResponse: Boolean = false,
    var lastResponseGenerated: Long? = null,
    var turnDetectionActive: Boolean = false,
    var currentTurnState: TurnState = TurnState.LISTENING,
) {
    companion object {
        private const val MAX_CONVERSATION_HISTORY = 20

        fun createInitial(sessionId: String): ConversationFlowSessionState {
            return ConversationFlowSessionState(
                sessionId = sessionId,
                isActive = false,
                conversationHistory = mutableListOf(),
            )
        }
    }

    /**
     * Adds turn to conversation history and trims if necessary
     */
    fun addToHistory(turn: ConversationTurn) {
        conversationHistory.add(turn)

        // Trim history if it gets too long
        if (conversationHistory.size > MAX_CONVERSATION_HISTORY) {
            conversationHistory = conversationHistory.takeLast(MAX_CONVERSATION_HISTORY).toMutableList()
        }
    }

    /**
     * Updates buffered transcript for turn processing
     */
    fun updateBufferedTranscript(
        transcript: String,
        isFinal: Boolean,
    ) {
        lastTranscriptTime = System.currentTimeMillis()

        if (isFinal) {
            bufferedTranscript = transcript.trim()
            partialTranscript = null
        } else {
            partialTranscript = transcript.trim()
        }
    }

    /**
     * Clears buffered transcripts after processing
     */
    fun clearBufferedTranscripts() {
        bufferedTranscript = null
        partialTranscript = null
    }

    /**
     * Resets conversation flow state
     */
    fun reset() {
        isActive = false
        conversationHistory.clear()
        bufferedTranscript = null
        partialTranscript = null
        lastTranscriptTime = null
        isProcessingResponse = false
        lastResponseGenerated = null
        turnDetectionActive = false
        currentTurnState = TurnState.LISTENING
    }
}

/**
 * Text-to-Speech streaming state per session
 */
data class TTSStreamingSessionState(
    val sessionId: String,
    var isActive: Boolean = false,
    var streamConfig: TTSStreamConfig? = null,
    var streamingJob: Job? = null,
    var currentRequestId: String? = null,
    var totalChunksGenerated: Long = 0L,
    var totalBytesGenerated: Long = 0L,
    var lastChunkSent: Long? = null,
    var isCurrentlyStreaming: Boolean = false,
) {
    companion object {
        fun createInitial(sessionId: String): TTSStreamingSessionState {
            return TTSStreamingSessionState(
                sessionId = sessionId,
                isActive = false,
            )
        }
    }

    /**
     * Updates metrics when TTS chunk is generated
     */
    fun updateChunkGenerated(chunkSize: Int) {
        totalChunksGenerated++
        totalBytesGenerated += chunkSize
        lastChunkSent = System.currentTimeMillis()
    }

    /**
     * Marks TTS streaming as started for a request
     */
    fun startStreaming(requestId: String) {
        currentRequestId = requestId
        isCurrentlyStreaming = true
    }

    /**
     * Marks TTS streaming as completed
     */
    fun completeStreaming() {
        currentRequestId = null
        isCurrentlyStreaming = false
    }

    /**
     * Resets TTS streaming state
     */
    fun reset() {
        isActive = false
        streamConfig = null
        streamingJob?.cancel()
        streamingJob = null
        currentRequestId = null
        totalChunksGenerated = 0L
        totalBytesGenerated = 0L
        lastChunkSent = null
        isCurrentlyStreaming = false
    }
}

/**
 * Enumeration of conversation turn states
 */
enum class TurnState {
    LISTENING,
    PROCESSING_SPEECH,
    GENERATING_RESPONSE,
    SPEAKING,
    WAITING_FOR_SILENCE,
}

/**
 * Session state statistics for monitoring
 */
data class SessionStateStatistics(
    val sessionId: String,
    val sessionDuration: Duration,
    val idleTime: Duration,
    val audioStats: AudioStreamingStats,
    val sttStats: STTStreamingStats,
    val conversationStats: ConversationStats,
    val ttsStats: TTSStreamingStats,
) {
    /**
     * Audio streaming statistics
     */
    data class AudioStreamingStats(
        val totalChunksReceived: Long,
        val totalBytesReceived: Long,
        val averageChunkSize: Double,
        val isActive: Boolean,
    )

    /**
     * STT streaming statistics
     */
    data class STTStreamingStats(
        val totalTranscriptsReceived: Long,
        val reconnectionAttempts: Int,
        val isActive: Boolean,
        val currentState: STTStreamState,
    )

    /**
     * Conversation statistics
     */
    data class ConversationStats(
        val totalTurns: Int,
        val isProcessingResponse: Boolean,
        val turnDetectionActive: Boolean,
        val currentTurnState: TurnState,
    )

    /**
     * TTS streaming statistics
     */
    data class TTSStreamingStats(
        val totalChunksGenerated: Long,
        val totalBytesGenerated: Long,
        val isCurrentlyStreaming: Boolean,
        val isActive: Boolean,
    )
}
