package com.sermo.models

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Configuration for audio streaming pipeline
 */
data class AudioStreamConfig(
    val sampleRateHertz: Int = 16000,
    val encoding: AudioEncoding = AudioEncoding.LINEAR16,
    val chunkSizeBytes: Int = 4096,
    val bufferSizeMs: Int = 100,
    val maxBufferSizeBytes: Int = 65536,
)

/**
 * State of the audio streaming pipeline
 */
enum class AudioStreamState {
    IDLE,
    ERROR,
}

/**
 * Represents an audio chunk in the streaming pipeline
 */
data class AudioChunk(
    val data: ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
    val sequenceNumber: Long = 0L,
) {
    override fun equals(other: kotlin.Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioChunk

        if (!data.contentEquals(other.data)) return false
        if (timestamp != other.timestamp) return false
        if (sequenceNumber != other.sequenceNumber) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        return result
    }
}

/**
 * Audio buffer statistics for monitoring pipeline performance
 */
data class AudioBufferStats(
    val currentBufferSize: Int,
    val maxBufferSize: Int,
    val totalChunksProcessed: Long,
    val averageChunkSize: Double,
    val bufferUtilization: Double,
    val processingLatency: Duration,
) {
    companion object {
        fun empty(): AudioBufferStats =
            AudioBufferStats(
                currentBufferSize = 0,
                maxBufferSize = 0,
                totalChunksProcessed = 0L,
                averageChunkSize = 0.0,
                bufferUtilization = 0.0,
                processingLatency = 0.milliseconds,
            )
    }
}

/**
 * Audio streaming pipeline metrics
 */
data class AudioStreamMetrics(
    val state: AudioStreamState,
    val bufferStats: AudioBufferStats,
    val throughputBytesPerSecond: Double,
    val chunksPerSecond: Double,
    val errorCount: Long,
    val lastErrorTime: Long?,
) {
    companion object {
        fun initial(): AudioStreamMetrics =
            AudioStreamMetrics(
                state = AudioStreamState.IDLE,
                bufferStats = AudioBufferStats.empty(),
                throughputBytesPerSecond = 0.0,
                chunksPerSecond = 0.0,
                errorCount = 0L,
                lastErrorTime = null,
            )
    }
}
