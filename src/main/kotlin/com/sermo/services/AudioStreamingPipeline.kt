package com.sermo.services

import com.sermo.models.AudioChunk
import com.sermo.models.AudioStreamConfig
import com.sermo.models.AudioStreamMetrics
import com.sermo.models.AudioStreamState
import kotlinx.coroutines.flow.Flow

/**
 * Interface for audio streaming pipeline operations
 */
interface AudioStreamingPipeline {
    /**
     * Start the audio streaming pipeline with given configuration
     */
    suspend fun startStreaming(config: AudioStreamConfig): Result<Unit>

    /**
     * Process incoming audio chunk from WebSocket
     */
    suspend fun processAudioChunk(audioData: ByteArray): Result<Unit>

    /**
     * Stop the audio streaming pipeline
     */
    suspend fun stopStreaming(): Result<Unit>

    /**
     * Get current pipeline state
     */
    fun getState(): AudioStreamState

    /**
     * Get pipeline metrics and statistics
     */
    fun getMetrics(): AudioStreamMetrics

    /**
     * Flow of processed audio chunks ready for STT
     */
    fun getProcessedAudioFlow(): Flow<AudioChunk>

    /**
     * Check if pipeline is actively streaming
     */
    fun isStreaming(): Boolean
}
