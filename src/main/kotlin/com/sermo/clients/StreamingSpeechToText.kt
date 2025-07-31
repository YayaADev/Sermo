package com.sermo.clients

import com.sermo.models.STTStreamConfig
import com.sermo.models.STTStreamState
import com.sermo.models.StreamingTranscriptResult
import kotlinx.coroutines.flow.Flow

/**
 * Interface for streaming Speech-to-Text functionality with real-time audio processing
 */
interface StreamingSpeechToText {
    /**
     * Start streaming recognition with the specified configuration
     */
    suspend fun startStreaming(config: STTStreamConfig): Result<Unit>

    /**
     * Send audio chunk to the streaming recognition service
     */
    suspend fun sendAudioChunk(audioData: ByteArray): Result<Unit>

    /**
     * Stop the current streaming session
     */
    suspend fun stopStreaming(): Result<Unit>

    /**
     * Get the current state of the streaming connection
     */
    fun getStreamState(): STTStreamState

    /**
     * Flow of streaming transcript results (partial and final)
     */
    fun getTranscriptFlow(): Flow<StreamingTranscriptResult>

    /**
     * Restart the streaming connection after timeout or error
     */
    suspend fun restartStream(config: STTStreamConfig): Result<Unit>

    /**
     * Check if the stream is currently active and receiving audio
     */
    fun isStreamActive(): Boolean
}
