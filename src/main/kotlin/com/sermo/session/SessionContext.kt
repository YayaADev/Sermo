package com.sermo.session

import com.sermo.models.AudioStreamConfig
import com.sermo.models.TTSAudioChunk
import com.sermo.models.TTSStreamingMetrics
import kotlinx.coroutines.flow.MutableSharedFlow
import sermo.protocol.SermoProtocol.ConversationTurn
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Centralized session context containing all per-session state

 */
data class SessionContext(
    val sessionInfo: SessionInfo,
    val audioState: AudioPipelineState = AudioPipelineState(),
    val conversationState: ConversationFlowState = ConversationFlowState(),
    val ttsState: TTSStreamingState = TTSStreamingState(),
    val sttState: STTStreamingState = STTStreamingState(),
    val isActive: AtomicBoolean = AtomicBoolean(true),
    val createdAt: Long = System.currentTimeMillis(),
    var lastActivity: Long = System.currentTimeMillis(),
) {
    fun updateActivity() {
        lastActivity = System.currentTimeMillis()
    }

    fun getDuration(): Long = System.currentTimeMillis() - createdAt

    fun getIdleTime(): Long = System.currentTimeMillis() - lastActivity
}

/**
 * Audio pipeline state for a session
 */
data class AudioPipelineState(
    var config: AudioStreamConfig? = null,
    val isStreamingActive: AtomicBoolean = AtomicBoolean(false),
    val isSTTStreamActive: AtomicBoolean = AtomicBoolean(false),
)

/**
 * Conversation flow state for a session
 */
data class ConversationFlowState(
    val conversationHistory: MutableList<ConversationTurn> = mutableListOf(),
    var session: ConversationSession? = null,
    var bufferedTranscript: String? = null,
    var partialTranscript: String? = null,
    var lastTranscriptTime: Long? = null,
)

/**
 * TTS streaming state for a session
 */
data class TTSStreamingState(
    val sequenceNumberGenerator: AtomicLong = AtomicLong(0L),
    val activeTTSSessions: ConcurrentHashMap<String, TTSSessionInfo> = ConcurrentHashMap(),
    val sessionChunkFlows: ConcurrentHashMap<String, MutableSharedFlow<TTSAudioChunk>> = ConcurrentHashMap(),
    val sessionMetrics: ConcurrentHashMap<String, TTSStreamingMetrics> = ConcurrentHashMap(),
)

/**
 * STT streaming state for a session
 */
data class STTStreamingState(
    val isActive: AtomicBoolean = AtomicBoolean(false),
    var transcriptBuffer: String? = null,
    var lastTranscriptReceived: Long? = null,
)

data class ConversationSession(
    val sessionId: String,
    val languageCode: String,
    var isActive: Boolean,
    val startTime: Long,
)

/**
 * TTS session information - moved from service to shared models
 */
data class TTSSessionInfo(
    val sessionId: String,
    val text: String,
    val languageCode: String,
    val voice: String?,
    val startTime: Long,
)
