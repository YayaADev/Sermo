package com.sermo.session

import com.sermo.websocket.ConversationState

/**
 * Session information model
 */
data class SessionInfo(
    val sessionId: String,
    val languageCode: String,
    val clientInfo: Map<String, String>,
    var state: SessionState,
    val createdAt: Long,
    var lastActivity: Long = System.currentTimeMillis(),
    var terminatedAt: Long? = null,
) {
    fun isActive(): Boolean = state == SessionState.ACTIVE

    fun getDuration(): Long = System.currentTimeMillis() - createdAt

    fun getIdleTime(): Long = System.currentTimeMillis() - lastActivity
}

data class PartialTranscriptEvent(
    override val sessionId: String,
    val transcript: String,
    val confidence: Float,
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent()

data class FinalTranscriptEvent(
    override val sessionId: String,
    val transcript: String,
    val confidence: Float,
    val languageCode: String,
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent()

data class ConversationStateChangedEvent(
    override val sessionId: String,
    val state: ConversationState,
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent()

data class STTTranscriptReceivedEvent(
    override val sessionId: String,
    val transcript: String,
    val confidence: Float,
    val isFinal: Boolean,
    val languageCode: String,
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent()

data class TTSAudioChunkEvent(
    override val sessionId: String,
    val audioData: ByteArray,
    val sequenceNumber: Long,
    val isLast: Boolean,
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TTSAudioChunkEvent
        if (sessionId != other.sessionId) return false
        if (!audioData.contentEquals(other.audioData)) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (isLast != other.isLast) return false
        if (timestamp != other.timestamp) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + audioData.contentHashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + isLast.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/**
 * Session states
 */
enum class SessionState {
    ACTIVE,
    TERMINATED,
    ERROR,
}

/**
 * Session termination reasons
 */
enum class SessionTerminationReason {
    CLIENT_DISCONNECT,
    UNRECOVERABLE_ERROR,
    TIMEOUT,
    SHUTDOWN,
    RESOURCE_LIMIT_EXCEEDED,
    INACTIVITY,
}
