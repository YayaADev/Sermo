package com.sermo.services

import com.sermo.clients.TextToSpeechClient
import com.sermo.models.SynthesisResponse
import com.sermo.models.TTSAudioChunk
import com.sermo.models.TTSStreamConfig
import com.sermo.models.TTSStreamingEvent
import com.sermo.models.TTSStreamingMetrics
import com.sermo.models.TTSStreamingState
import com.sermo.session.SessionAwareService
import com.sermo.session.SessionCreatedEvent
import com.sermo.session.SessionEventBus
import com.sermo.session.SessionTerminatedEvent
import com.sermo.session.TTSAudioChunkEvent
import com.sermo.session.TTSStreamingStartedEvent
import com.sermo.session.TTSStreamingStoppedEvent
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 *  TTS service implementation with session event management
 */
class StreamingTextToSpeechService(
    private val textToSpeechClient: TextToSpeechClient,
    private val languageDetectionService: LanguageDetectionService,
    eventBus: SessionEventBus,
) : SessionAwareService(eventBus, "StreamingTextToSpeechService") {
    companion object {
        private val logger = LoggerFactory.getLogger(StreamingTextToSpeechService::class.java)
        private const val CHUNK_SIZE_BYTES = 8192 // 8KB chunks
    }

    private val sequenceNumberGenerator = AtomicLong(0L)

    // Per-session state
    private val activeTTSSessions = ConcurrentHashMap<String, TTSSessionInfo>()
    private val sessionChunkFlows = ConcurrentHashMap<String, MutableSharedFlow<TTSAudioChunk>>()
    private val sessionMetrics = ConcurrentHashMap<String, TTSStreamingMetrics>()

    // Global TTS event flow
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

            // Validate input
            if (text.isBlank()) {
                return Result.failure(Exception("Empty text provided for TTS"))
            }

            // Create TTS session info
            val ttsInfo =
                TTSSessionInfo(
                    sessionId = sessionId,
                    text = text,
                    languageCode = languageCode,
                    voice = voice,
                    startTime = System.currentTimeMillis(),
                )

            activeTTSSessions[sessionId] = ttsInfo

            // Create chunk flow for this session
            val chunkFlow =
                MutableSharedFlow<TTSAudioChunk>(
                    replay = 0,
                    extraBufferCapacity = 50,
                )
            sessionChunkFlows[sessionId] = chunkFlow

            // Initialize metrics
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
            sessionMetrics[sessionId] = metrics

            // Publish event
            publishEvent(
                TTSStreamingStartedEvent(
                    sessionId = sessionId,
                    text = text,
                    languageCode = languageCode,
                ),
            )

            // Emit TTS event
            ttsEventFlow.tryEmit(
                TTSStreamingEvent(
                    sessionId = sessionId,
                    state = TTSStreamingState.INITIALIZING,
                ),
            )

            // Start TTS processing asynchronously
            serviceScope.launch {
                try {
                    performTTSStreaming(sessionId, text, languageCode, voice, streamConfig)
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
     * Perform actual TTS generation and streaming
     */
    private suspend fun performTTSStreaming(
        sessionId: String,
        text: String,
        languageCode: String,
        voice: String?,
        streamConfig: TTSStreamConfig,
    ) {
        val startTime = System.currentTimeMillis()

        try {
            // Update state to generating
            updateMetricsState(sessionId, TTSStreamingState.GENERATING)
            ttsEventFlow.tryEmit(
                TTSStreamingEvent(
                    sessionId = sessionId,
                    state = TTSStreamingState.GENERATING,
                ),
            )

            logger.debug("Generating TTS audio for session $sessionId")

            // Generate TTS audio
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

            // Decode base64 audio data
            val audioData = Base64.getDecoder().decode(synthesisResponse.audio)

            // Start streaming the audio chunks
            streamAudioChunks(sessionId, audioData, synthesisResponse)
        } catch (e: Exception) {
            logger.error("TTS processing failed for session $sessionId", e)
            handleTTSError(sessionId, e)
        }
    }

    /**
     * Stream audio data as chunks
     */
    private suspend fun streamAudioChunks(
        sessionId: String,
        audioData: ByteArray,
        synthesisResponse: SynthesisResponse,
    ) {
        try {
            val chunkFlow = sessionChunkFlows[sessionId]
            if (chunkFlow == null) {
                logger.warn("No chunk flow found for session $sessionId")
                return
            }

            val session = activeTTSSessions[sessionId]
            if (session == null) {
                logger.warn("Session $sessionId not found during streaming")
                return
            }

            logger.debug("Starting audio chunk streaming for session $sessionId, total bytes: ${audioData.size}")

            // Update state to streaming
            updateMetricsState(sessionId, TTSStreamingState.STREAMING)
            ttsEventFlow.tryEmit(
                TTSStreamingEvent(
                    sessionId = sessionId,
                    state = TTSStreamingState.STREAMING,
                    bytesStreamed = 0L,
                ),
            )

            val totalChunks = (audioData.size + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES
            var bytesSent = 0L
            var chunksSent = 0L

            // Stream audio in chunks
            for (i in 0 until totalChunks) {
                val startIndex = i * CHUNK_SIZE_BYTES
                val endIndex = kotlin.math.min(startIndex + CHUNK_SIZE_BYTES, audioData.size)
                val chunkData = audioData.sliceArray(startIndex until endIndex)

                val audioChunk =
                    TTSAudioChunk(
                        audioData = chunkData,
                        sequenceNumber = sequenceNumberGenerator.incrementAndGet(),
                        isLast = (i == totalChunks - 1),
                        chunkDurationMs = calculateChunkDuration(chunkData.size, synthesisResponse),
                    )

                // Emit chunk
                val emitted = chunkFlow.tryEmit(audioChunk)
                if (!emitted) {
                    logger.warn("Failed to emit audio chunk ${audioChunk.sequenceNumber} for session $sessionId")
                }

                // Update metrics
                bytesSent += chunkData.size
                chunksSent++
                updateSessionMetrics(sessionId, chunksSent, bytesSent, TTSStreamingState.STREAMING)

                // Also send via WebSocket by publishing event
                publishEvent(
                    TTSAudioChunkEvent(
                        sessionId = sessionId,
                        audioData = chunkData,
                        sequenceNumber = audioChunk.sequenceNumber,
                        isLast = audioChunk.isLast,
                    ),
                )

                logger.debug("Sent audio chunk ${audioChunk.sequenceNumber} for session $sessionId: ${chunkData.size} bytes")

                // Small delay between chunks
                kotlinx.coroutines.delay(10)
            }

            // Mark as completed
            updateMetricsState(sessionId, TTSStreamingState.COMPLETED)
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
            logger.error("Failed to stream audio chunks for session $sessionId", e)
            handleTTSError(sessionId, e)
        }
    }

    /**
     * Calculate chunk duration
     */
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

    /**
     * Update session metrics
     */
    private fun updateSessionMetrics(
        sessionId: String,
        chunksGenerated: Long,
        bytesGenerated: Long,
        state: TTSStreamingState,
    ) {
        val session = activeTTSSessions[sessionId] ?: return
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

        sessionMetrics[sessionId] = updatedMetrics
    }

    /**
     * Update metrics state only
     */
    private fun updateMetricsState(
        sessionId: String,
        state: TTSStreamingState,
    ) {
        sessionMetrics[sessionId]?.let { currentMetrics ->
            sessionMetrics[sessionId] = currentMetrics.copy(state = state)
        }
    }

    /**
     * Handle TTS errors
     */
    private suspend fun handleTTSError(
        sessionId: String,
        error: Exception,
    ) {
        try {
            updateMetricsState(sessionId, TTSStreamingState.ERROR)

            ttsEventFlow.tryEmit(
                TTSStreamingEvent(
                    sessionId = sessionId,
                    state = TTSStreamingState.ERROR,
                    errorMessage = error.message ?: "Unknown TTS error",
                ),
            )

            publishError(sessionId, "TTS_STREAMING_ERROR", error.message ?: "Unknown error")

            // Clean up session
            stopTTSStreaming(sessionId)
        } catch (e: Exception) {
            logger.error("Failed to handle TTS error for session $sessionId", e)
        }
    }

    fun stopTTSStreaming(sessionId: String): Result<Unit> {
        return try {
            logger.info("Stopping TTS streaming for session: $sessionId")

            activeTTSSessions.remove(sessionId)
            sessionChunkFlows.remove(sessionId)
            sessionMetrics.remove(sessionId)

            // Publish event
            publishEvent(TTSStreamingStoppedEvent(sessionId = sessionId))

            ttsEventFlow.tryEmit(
                TTSStreamingEvent(
                    sessionId = sessionId,
                    state = TTSStreamingState.CANCELLED,
                ),
            )

            logger.info("TTS streaming stopped for session: $sessionId")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to stop TTS streaming for session $sessionId", e)
            Result.failure(e)
        }
    }

    fun getTTSChunkFlow(sessionId: String): Flow<TTSAudioChunk>? {
        return sessionChunkFlows[sessionId]?.asSharedFlow()
    }

    fun getTTSEventFlow(): Flow<TTSStreamingEvent> {
        return ttsEventFlow.asSharedFlow()
    }

    fun getStreamingMetrics(sessionId: String): TTSStreamingMetrics? {
        return sessionMetrics[sessionId]
    }

    fun isStreamingActive(sessionId: String): Boolean {
        return activeTTSSessions.containsKey(sessionId)
    }

    override suspend fun shutdown() {
        logger.info("Shutting down TTS service...")

        // Stop all active sessions
        activeTTSSessions.keys.toList().forEach { sessionId ->
            stopTTSStreaming(sessionId)
        }

        serviceScope.cancel()
        super.shutdown()

        logger.info("TTS service shutdown complete")
    }

    // Session event handlers
    override suspend fun onSessionCreated(event: SessionCreatedEvent): Result<Unit> {
        logger.debug("TTS service ready for session: ${event.sessionId}")
        return Result.success(Unit)
    }

    override suspend fun onSessionTerminated(event: SessionTerminatedEvent): Result<Unit> {
        return try {
            logger.info("Cleaning up TTS streaming for session: ${event.sessionId}")

            // Stop any active TTS streaming
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

data class TTSSessionInfo(
    val sessionId: String,
    val text: String,
    val languageCode: String,
    val voice: String?,
    val startTime: Long,
)
