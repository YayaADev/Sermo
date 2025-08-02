package com.sermo.models

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Configuration for TTS streaming pipeline
 */
data class TTSStreamConfig(
    val chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE_BYTES,
    val streamingEnabled: Boolean = true,
    val maxBufferSizeBytes: Int = MAX_BUFFER_SIZE_BYTES,
    val compressionEnabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_CHUNK_SIZE_BYTES = 8192 // 8KB chunks for smooth streaming
        const val MAX_BUFFER_SIZE_BYTES = 131072 // 128KB max buffer
        const val MIN_CHUNK_SIZE_BYTES = 1024 // 1KB minimum
        const val MAX_CHUNK_SIZE_BYTES = 32768 // 32KB maximum
    }
}

/**
 * Represents a chunk of TTS audio data for streaming
 */
data class TTSAudioChunk(
    val audioData: ByteArray,
    val sequenceNumber: Long,
    val isLast: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val chunkDurationMs: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TTSAudioChunk

        if (!audioData.contentEquals(other.audioData)) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (isLast != other.isLast) return false
        if (timestamp != other.timestamp) return false
        if (chunkDurationMs != other.chunkDurationMs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = audioData.contentHashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + isLast.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (chunkDurationMs?.hashCode() ?: 0)
        return result
    }
}

/**
 * TTS streaming session information
 */
data class TTSStreamingSession(
    val sessionId: String,
    val languageCode: String,
    val voiceId: String? = null,
    val streamConfig: TTSStreamConfig,
    val startTime: Long = System.currentTimeMillis(),
    var isActive: Boolean = true,
    var totalChunksSent: Long = 0L,
    var totalBytesSent: Long = 0L,
)

/**
 * State of TTS streaming operation
 */
enum class TTSStreamingState {
    IDLE,
    INITIALIZING,
    GENERATING,
    STREAMING,
    COMPLETED,
    ERROR,
    CANCELLED,
}

/**
 * Event emitted during TTS streaming process
 */
data class TTSStreamingEvent(
    val sessionId: String,
    val state: TTSStreamingState,
    val chunkSequence: Long? = null,
    val totalChunks: Long? = null,
    val bytesStreamed: Long = 0L,
    val estimatedDuration: Duration? = null,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Metrics for TTS streaming performance monitoring
 */
data class TTSStreamingMetrics(
    val sessionId: String,
    val state: TTSStreamingState,
    val totalChunksGenerated: Long,
    val totalBytesGenerated: Long,
    val streamingDuration: Duration,
    val averageChunkSize: Double,
    val throughputBytesPerSecond: Double,
    val generationLatency: Duration,
    val streamingLatency: Duration,
    val errorCount: Long = 0L,
) {
    companion object {
        fun initial(sessionId: String): TTSStreamingMetrics =
            TTSStreamingMetrics(
                sessionId = sessionId,
                state = TTSStreamingState.IDLE,
                totalChunksGenerated = 0L,
                totalBytesGenerated = 0L,
                streamingDuration = 0.milliseconds,
                averageChunkSize = 0.0,
                throughputBytesPerSecond = 0.0,
                generationLatency = 0.milliseconds,
                streamingLatency = 0.milliseconds,
            )
    }
}

/**
 * Configuration for TTS audio chunking
 */
data class TTSChunkingConfig(
    val targetChunkDurationMs: Long = DEFAULT_CHUNK_DURATION_MS,
    val maxChunkSizeBytes: Int = TTSStreamConfig.MAX_CHUNK_SIZE_BYTES,
    val overlapMs: Long = 0L, // No overlap by default
    val fadeInMs: Long = 0L, // No fade effects by default
    val fadeOutMs: Long = 0L,
) {
    companion object {
        const val DEFAULT_CHUNK_DURATION_MS = 250L // 250ms chunks for smooth playback
        const val MIN_CHUNK_DURATION_MS = 100L
        const val MAX_CHUNK_DURATION_MS = 1000L
    }
}
