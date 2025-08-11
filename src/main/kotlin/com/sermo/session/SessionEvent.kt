package com.sermo.session

import kotlin.time.Duration

/**
 * Base sealed class for all session events
 */
sealed class SessionEvent {
    abstract val sessionId: String
    abstract val timestamp: Long
}

/**
 * Session lifecycle events
 */
data class SessionCreatedEvent(
    override val sessionId: String,
    val languageCode: String,
    val clientInfo: Map<String, String> = emptyMap(),
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent()

data class SessionTerminatedEvent(
    override val sessionId: String,
    val reason: SessionTerminationReason,
    val duration: Duration,
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent()

/**
 * Service-specific events
 */
data class AudioStreamingStartedEvent(
    override val sessionId: String,
    val sampleRate: Int,
    val encoding: String,
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent()

data class AudioStreamingStoppedEvent(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent()

data class STTStreamingStartedEvent(
    override val sessionId: String,
    val languageCode: String,
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent()

data class STTStreamingStoppedEvent(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent()

data class ConversationFlowStartedEvent(
    override val sessionId: String,
    val languageCode: String,
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent()

data class ConversationFlowStoppedEvent(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent()

data class TTSStreamingStartedEvent(
    override val sessionId: String,
    val text: String,
    val languageCode: String,
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent()

data class TTSStreamingStoppedEvent(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent()

/**
 * Error events
 */
data class SessionErrorEvent(
    override val sessionId: String,
    val errorType: String,
    val errorMessage: String,
    val serviceName: String,
    override val timestamp: Long = System.currentTimeMillis(),
) : SessionEvent()
