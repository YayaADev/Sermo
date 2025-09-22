package com.sermo.services

import com.sermo.clients.TextToSpeechClient
import com.sermo.models.SynthesisResponse
import com.sermo.models.TTSAudioChunk
import com.sermo.models.TTSStreamConfig
import com.sermo.models.TTSStreamingEvent
import com.sermo.models.TTSStreamingMetrics
import com.sermo.models.TTSStreamingState
import com.sermo.session.SessionAwareService
import com.sermo.session.SessionContext
import com.sermo.session.SessionContextRegistry
import com.sermo.session.SessionCreatedEvent
import com.sermo.session.SessionEventBus
import com.sermo.session.SessionTerminatedEvent
import com.sermo.session.TTSAudioChunkEvent
import com.sermo.session.TTSSessionInfo
import com.sermo.session.TTSStreamingStartedEvent
import com.sermo.session.TTSStreamingStoppedEvent
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds

/**
 * Refactored TTS service using centralized session context for websocket
 */
class StreamingTextToSpeechService(
    private val textToSpeechClient: TextToSpeechClient,
    private val contextRegistry: SessionContextRegistry,
    eventBus: SessionEventBus,
) : SessionAwareService(eventBus, "StreamingTextToSpeechServiceV2") {
    companion object {
        private val logger = LoggerFactory.getLogger(StreamingTextToSpeechService::class.java)
        private const val CHUNK_SIZE_BYTES = 8192 // 8KB chunks
    }

    // Global TTS event flow (not per-session)
    private val ttsEventFlow =
        MutableSharedFlow<TTSStreamingEvent>(
            replay = 0,
            extraBufferCapacity = 20,
        )

    fun startTTSStreaming(
        sessionId: String,
        text: String,
        languageCode: String,
        voice: String?,
        streamConfig: TTSStreamConfig,
    ): Result<Unit> {
        return try {
            logger.info("Starting TTS streaming for session: $sessionId")

            if (text.isBlank()) {
                return Result.failure(Exception("Empty text provided for TTS"))
            }

            val context = contextRegistry.requireContext(sessionId)
            val ttsState = context.ttsState

            // Create TTS session info in context
            val ttsInfo =
                TTSSessionInfo(
                    sessionId = sessionId,
                    text = text,
                    languageCode = languageCode,
                    voice = voice,
                    startTime = System.currentTimeMillis(),
                )

            ttsState.activeTTSSessions[sessionId] = ttsInfo

            // Create chunk flow in context
            val chunkFlow =
                MutableSharedFlow<TTSAudioChunk>(
                    replay = 0,
                    extraBufferCapacity = 50,
                )
            ttsState.sessionChunkFlows[sessionId] = chunkFlow

            // Initialize metrics in context
            val metrics =
                TTSStreamingMetrics(
                    sessionId = sessionId,
                    state = TTSStreamingState.INITIALIZING,
                    totalChunksGenerated = 0L,
                    totalBytesGenerated = 0L,
                    streamingDuration = 0.milliseconds,
                    averageChunkSize = 0.0,
                    throughputBytesPerSecond = 0.0,
                    generationLatency = 0.milliseconds,
                    streamingLatency = 0.milliseconds,
                    errorCount = 0L,
                )
            ttsState.sessionMetrics[sessionId] = metrics

            publishEvent(TTSStreamingStartedEvent(sessionId = sessionId, text = text, languageCode = languageCode))

            ttsEventFlow.tryEmit(TTSStreamingEvent(sessionId = sessionId, state = TTSStreamingState.INITIALIZING))

            // Start TTS processing asynchronously
            serviceScope.launch {
                try {
                    performTTSStreaming(sessionId, text, languageCode, voice)
                } catch (e: Exception) {
                    logger.error("TTS streaming failed for session $sessionId", e)
                    handleTTSError(sessionId, e)
                }
            }

            logger.info("TTS streaming started for session: $sessionId")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to start TTS streaming for session $sessionId", e)
            publishError(sessionId, "TTS_STREAMING_START_ERROR", e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Perform actual TTS generation and streaming using context
     */
    private suspend fun performTTSStreaming(
        sessionId: String,
        text: String,
        languageCode: String,
        voice: String?,
    ) {
        val startTime = System.currentTimeMillis()

        try {
            val context = contextRegistry.requireContext(sessionId)

            updateMetricsState(context, TTSStreamingState.GENERATING)
            ttsEventFlow.tryEmit(TTSStreamingEvent(sessionId = sessionId, state = TTSStreamingState.GENERATING))

            logger.debug("Generating TTS audio for session $sessionId")

            val ttsResult =
                textToSpeechClient.synthesize(
                    text = text,
                    language = languageCode,
                    voice = voice,
                )

            if (ttsResult.isFailure) {
                throw Exception("TTS generation failed", ttsResult.exceptionOrNull())
            }

            val synthesisResponse = ttsResult.getOrThrow()
            val generationTime = System.currentTimeMillis() - startTime

            logger.info("TTS generation completed for session $sessionId in ${generationTime}ms")

            val audioData = Base64.getDecoder().decode(synthesisResponse.audio)
            streamAudioChunks(context, audioData, synthesisResponse)
        } catch (e: Exception) {
            logger.error("TTS processing failed for session $sessionId", e)
            handleTTSError(sessionId, e)
        }
    }

    /**
     * Stream audio data as chunks using context
     */
    private suspend fun streamAudioChunks(
        context: SessionContext,
        audioData: ByteArray,
        synthesisResponse: SynthesisResponse,
    ) {
        try {
            val sessionId = context.sessionInfo.sessionId
            val ttsState = context.ttsState
            val chunkFlow = ttsState.sessionChunkFlows[sessionId]

            if (chunkFlow == null) {
                logger.warn("No chunk flow found for session $sessionId")
                return
            }

            logger.debug("Starting audio chunk streaming for session $sessionId, total bytes: ${audioData.size}")

            updateMetricsState(context, TTSStreamingState.STREAMING)
            ttsEventFlow.tryEmit(TTSStreamingEvent(sessionId = sessionId, state = TTSStreamingState.STREAMING, bytesStreamed = 0L))

            val totalChunks = (audioData.size + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES
            var bytesSent = 0L
            var chunksSent = 0L

            for (i in 0 until totalChunks) {
                val startIndex = i * CHUNK_SIZE_BYTES
                val endIndex = kotlin.math.min(startIndex + CHUNK_SIZE_BYTES, audioData.size)
                val chunkData = audioData.sliceArray(startIndex until endIndex)

                val audioChunk =
                    TTSAudioChunk(
                        audioData = chunkData,
                        sequenceNumber = ttsState.sequenceNumberGenerator.incrementAndGet(),
                        isLast = (i == totalChunks - 1),
                        chunkDurationMs = calculateChunkDuration(chunkData.size, synthesisResponse),
                    )

                val emitted = chunkFlow.tryEmit(audioChunk)
                if (!emitted) {
                    logger.warn("Failed to emit audio chunk ${audioChunk.sequenceNumber} for session $sessionId")
                }

                bytesSent += chunkData.size
                chunksSent++
                updateSessionMetrics(context, chunksSent, bytesSent, TTSStreamingState.STREAMING)

                publishEvent(
                    TTSAudioChunkEvent(
                        sessionId = sessionId,
                        audioData = chunkData,
                        sequenceNumber = audioChunk.sequenceNumber,
                        isLast = audioChunk.isLast,
                    ),
                )

                logger.debug("Sent audio chunk ${audioChunk.sequenceNumber} for session $sessionId: ${chunkData.size} bytes")

                kotlinx.coroutines.delay(10)
            }

            updateMetricsState(context, TTSStreamingState.COMPLETED)
            ttsEventFlow.tryEmit(
                TTSStreamingEvent(
                    sessionId = sessionId,
                    state = TTSStreamingState.COMPLETED,
                    chunkSequence = chunksSent,
                    totalChunks = chunksSent,
                    bytesStreamed = bytesSent,
                ),
            )

            logger.info("TTS streaming completed for session $sessionId: $chunksSent chunks, $bytesSent bytes")

            // Auto-cleanup after completion
            stopTTSStreaming(sessionId)
        } catch (e: Exception) {
            logger.error("Failed to stream audio chunks for session ${context.sessionInfo.sessionId}", e)
            handleTTSError(context.sessionInfo.sessionId, e)
        }
    }

    private fun calculateChunkDuration(
        chunkSizeBytes: Int,
        synthesisResponse: SynthesisResponse,
    ): Long? {
        return synthesisResponse.durationSeconds?.let { totalDuration ->
            val totalBytes = Base64.getDecoder().decode(synthesisResponse.audio).size
            val proportion = chunkSizeBytes.toDouble() / totalBytes
            (totalDuration * proportion * 1000).toLong()
        }
    }

    private fun updateSessionMetrics(
        context: SessionContext,
        chunksGenerated: Long,
        bytesGenerated: Long,
        state: TTSStreamingState,
    ) {
        val sessionId = context.sessionInfo.sessionId
        val ttsState = context.ttsState
        val session = ttsState.activeTTSSessions[sessionId] ?: return
        val currentTime = System.currentTimeMillis()
        val streamingDuration = (currentTime - session.startTime).milliseconds

        val updatedMetrics =
            TTSStreamingMetrics(
                sessionId = sessionId,
                state = state,
                totalChunksGenerated = chunksGenerated,
                totalBytesGenerated = bytesGenerated,
                streamingDuration = streamingDuration,
                averageChunkSize = if (chunksGenerated > 0) bytesGenerated.toDouble() / chunksGenerated else 0.0,
                throughputBytesPerSecond =
                    if (streamingDuration.inWholeMilliseconds > 0) {
                        (bytesGenerated * 1000.0) / streamingDuration.inWholeMilliseconds
                    } else {
                        0.0
                    },
                generationLatency = 0.milliseconds,
                streamingLatency = streamingDuration,
                errorCount = 0L,
            )

        ttsState.sessionMetrics[sessionId] = updatedMetrics
    }

    private fun updateMetricsState(
        context: SessionContext,
        state: TTSStreamingState,
    ) {
        val sessionId = context.sessionInfo.sessionId
        val ttsState = context.ttsState
        ttsState.sessionMetrics[sessionId]?.let { currentMetrics ->
            ttsState.sessionMetrics[sessionId] = currentMetrics.copy(state = state)
        }
    }

    private fun handleTTSError(
        sessionId: String,
        error: Exception,
    ) {
        try {
            val context = contextRegistry.getContext(sessionId)
            if (context != null) {
                updateMetricsState(context, TTSStreamingState.ERROR)
            }

            ttsEventFlow.tryEmit(
                TTSStreamingEvent(
                    sessionId = sessionId,
                    state = TTSStreamingState.ERROR,
                    errorMessage = error.message ?: "Unknown TTS error",
                ),
            )

            publishError(sessionId, "TTS_STREAMING_ERROR", error.message ?: "Unknown error")
            stopTTSStreaming(sessionId)
        } catch (e: Exception) {
            logger.error("Failed to handle TTS error for session $sessionId", e)
        }
    }

    fun stopTTSStreaming(sessionId: String): Result<Unit> {
        return try {
            logger.info("Stopping TTS streaming for session: $sessionId")

            val context = contextRegistry.getContext(sessionId)
            if (context != null) {
                // Clear session-specific TTS state in context
                val ttsState = context.ttsState
                ttsState.activeTTSSessions.remove(sessionId)
                ttsState.sessionChunkFlows.remove(sessionId)
                ttsState.sessionMetrics.remove(sessionId)
            }

            publishEvent(TTSStreamingStoppedEvent(sessionId = sessionId))
            ttsEventFlow.tryEmit(TTSStreamingEvent(sessionId = sessionId, state = TTSStreamingState.CANCELLED))

            logger.info("TTS streaming stopped for session: $sessionId")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to stop TTS streaming for session $sessionId", e)
            Result.failure(e)
        }
    }

    fun isStreamingActive(sessionId: String): Boolean {
        return contextRegistry.getContext(sessionId)?.ttsState?.activeTTSSessions?.containsKey(sessionId) == true
    }

    override suspend fun shutdown() {
        logger.info("Shutting down TTS service...")

        // Stop all active sessions - context registry will handle cleanup
        contextRegistry.getActiveSessions().forEach { context ->
            if (isStreamingActive(context.sessionInfo.sessionId)) {
                stopTTSStreaming(context.sessionInfo.sessionId)
            }
        }

        serviceScope.cancel()
        super.shutdown()
        logger.info("TTS service shutdown complete")
    }

    // Session event handlers - MINIMAL CLEANUP!
    override suspend fun onSessionCreated(event: SessionCreatedEvent): Result<Unit> {
        logger.debug("TTS service ready for session: ${event.sessionId}")
        return Result.success(Unit)
    }

    override suspend fun onSessionTerminated(event: SessionTerminatedEvent): Result<Unit> {
        return try {
            logger.info("Cleaning up TTS streaming for session: ${event.sessionId}")

            // Context registry automatically cleans up the TTSStreamingState!
            // We only need to stop any active external processes
            if (isStreamingActive(event.sessionId)) {
                stopTTSStreaming(event.sessionId)
            }

            logger.debug("TTS streaming cleanup completed for session: ${event.sessionId}")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to cleanup TTS streaming for session ${event.sessionId}", e)
            Result.failure(e)
        }
    }
}
