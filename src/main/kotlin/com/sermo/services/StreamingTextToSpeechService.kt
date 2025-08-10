package com.sermo.services

import com.sermo.clients.TextToSpeechClient
import com.sermo.exceptions.TTSException
import com.sermo.models.Constants.DEFAULT_LANGUAGE_CODE
import com.sermo.models.SynthesisResponse
import com.sermo.models.TTSAudioChunk
import com.sermo.models.TTSStreamConfig
import com.sermo.models.TTSStreamingEvent
import com.sermo.models.TTSStreamingMetrics
import com.sermo.models.TTSStreamingSession
import com.sermo.models.TTSStreamingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Service for streaming TTS audio generation and delivery
 */
interface StreamingTextToSpeechService {
    /**
     * Starts TTS streaming session for a given text
     */
    suspend fun startTTSStreaming(
        sessionId: String,
        text: String,
        languageCode: String = DEFAULT_LANGUAGE_CODE,
        voice: String? = null,
        streamConfig: TTSStreamConfig = TTSStreamConfig(),
    ): Result<Unit>

    /**
     * Gets flow of TTS audio chunks for a session
     */
    fun getTTSChunkFlow(sessionId: String): Flow<TTSAudioChunk>?

    /**
     * Gets flow of TTS streaming events
     */
    fun getTTSEventFlow(): Flow<TTSStreamingEvent>

    /**
     * Stops TTS streaming for a session
     */
    suspend fun stopTTSStreaming(sessionId: String): Result<Unit>

    /**
     * Gets streaming metrics for a session
     */
    fun getStreamingMetrics(sessionId: String): TTSStreamingMetrics?

    /**
     * Checks if streaming is active for a session
     */
    fun isStreamingActive(sessionId: String): Boolean

    /**
     * Shuts down the streaming service
     */
    suspend fun shutdown()
}

/**
 * Implementation of streaming TTS service
 */
class StreamingTextToSpeechServiceImpl(
    private val textToSpeechClient: TextToSpeechClient,
    private val languageDetectionService: LanguageDetectionService,
) : StreamingTextToSpeechService {
    companion object {
        private val logger = LoggerFactory.getLogger(StreamingTextToSpeechServiceImpl::class.java)
        private const val CHUNK_FLOW_BUFFER_SIZE = 50
        private const val EVENT_FLOW_BUFFER_SIZE = 20
        private const val MAX_CONCURRENT_SESSIONS = 10
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isShutdown = AtomicBoolean(false)
    private val sessionMutex = Mutex()

    private val activeSessions = ConcurrentHashMap<String, TTSStreamingSession>()
    private val sessionChunkFlows = ConcurrentHashMap<String, MutableSharedFlow<TTSAudioChunk>>()
    private val sessionMetrics = ConcurrentHashMap<String, TTSStreamingMetrics>()

    private val eventFlow =
        MutableSharedFlow<TTSStreamingEvent>(
            replay = 0,
            extraBufferCapacity = EVENT_FLOW_BUFFER_SIZE,
        )

    private val sequenceNumberGenerator = AtomicLong(0L)

    override suspend fun startTTSStreaming(
        sessionId: String,
        text: String,
        languageCode: String,
        voice: String?,
        streamConfig: TTSStreamConfig,
    ): Result<Unit> {
        if (isShutdown.get()) {
            return Result.failure(TTSException("Service is shutdown"))
        }

        return sessionMutex.withLock {
            try {
                // Check concurrent session limit
                if (activeSessions.size >= MAX_CONCURRENT_SESSIONS) {
                    logger.warn("Maximum concurrent TTS sessions reached: $MAX_CONCURRENT_SESSIONS")
                    return Result.failure(TTSException("Maximum concurrent sessions reached"))
                }

                // Stop any existing session for this sessionId
                if (activeSessions.containsKey(sessionId)) {
                    logger.info("Stopping existing TTS session for $sessionId")
                    stopTTSStreaming(sessionId)
                }

                // Validate input
                if (text.isBlank()) {
                    return Result.failure(TTSException("Empty text provided for TTS"))
                }

                // Detect language if not provided
                val detectionResult = languageDetectionService.detectLanguageForTTS(text)
                val effectiveLanguage = if (languageCode == "auto") detectionResult.language else languageCode

                logger.info("Starting TTS streaming for session $sessionId: text length=${text.length}, language=$effectiveLanguage")

                // Create streaming session
                val streamingSession =
                    TTSStreamingSession(
                        sessionId = sessionId,
                        languageCode = effectiveLanguage,
                        voiceId = voice,
                        streamConfig = streamConfig,
                    )

                // Create chunk flow for this session
                val chunkFlow =
                    MutableSharedFlow<TTSAudioChunk>(
                        replay = 0,
                        extraBufferCapacity = CHUNK_FLOW_BUFFER_SIZE,
                    )

                // Initialize metrics
                val metrics = TTSStreamingMetrics.initial(sessionId)

                // Store session data
                activeSessions[sessionId] = streamingSession
                sessionChunkFlows[sessionId] = chunkFlow
                sessionMetrics[sessionId] = metrics

                // Emit initialization event
                emitStreamingEvent(
                    TTSStreamingEvent(
                        sessionId = sessionId,
                        state = TTSStreamingState.INITIALIZING,
                    ),
                )

                // Start streaming process asynchronously
                serviceScope.launch {
                    try {
                        performTTSStreaming(sessionId, text, effectiveLanguage, voice, streamingSession)
                    } catch (e: Exception) {
                        logger.error("TTS streaming failed for session $sessionId", e)
                        handleStreamingError(sessionId, e)
                    }
                }

                logger.info("TTS streaming session started successfully: $sessionId")
                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("Failed to start TTS streaming for session $sessionId", e)
                Result.failure(TTSException("Failed to start TTS streaming", e))
            }
        }
    }

    override fun getTTSChunkFlow(sessionId: String): Flow<TTSAudioChunk>? {
        return sessionChunkFlows[sessionId]?.asSharedFlow()
    }

    override fun getTTSEventFlow(): Flow<TTSStreamingEvent> = eventFlow.asSharedFlow()

    override suspend fun stopTTSStreaming(sessionId: String): Result<Unit> {
        return sessionMutex.withLock {
            try {
                val session = activeSessions.remove(sessionId)
                if (session == null) {
                    logger.debug("No active TTS session found for $sessionId")
                    return Result.success(Unit)
                }

                // Mark session as inactive
                session.isActive = false

                // Close chunk flow
                sessionChunkFlows.remove(sessionId)?.let { flow ->
                    // Send final empty chunk to signal completion
                    val finalChunk =
                        TTSAudioChunk(
                            audioData = ByteArray(0),
                            sequenceNumber = sequenceNumberGenerator.incrementAndGet(),
                            isLast = true,
                        )
                    flow.tryEmit(finalChunk)
                }

                // Remove metrics
                sessionMetrics.remove(sessionId)

                // Emit completion event
                emitStreamingEvent(
                    TTSStreamingEvent(
                        sessionId = sessionId,
                        state = TTSStreamingState.CANCELLED,
                    ),
                )

                logger.info("TTS streaming stopped for session $sessionId")
                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("Failed to stop TTS streaming for session $sessionId", e)
                Result.failure(TTSException("Failed to stop TTS streaming", e))
            }
        }
    }

    override fun getStreamingMetrics(sessionId: String): TTSStreamingMetrics? {
        return sessionMetrics[sessionId]
    }

    override fun isStreamingActive(sessionId: String): Boolean {
        return activeSessions[sessionId]?.isActive == true
    }

    override suspend fun shutdown() {
        if (isShutdown.getAndSet(true)) {
            return
        }

        logger.info("Shutting down streaming TTS service...")

        sessionMutex.withLock {
            // Stop all active sessions
            activeSessions.keys.toList().forEach { sessionId ->
                stopTTSStreaming(sessionId)
            }

            // Clear all data
            activeSessions.clear()
            sessionChunkFlows.clear()
            sessionMetrics.clear()
        }

        serviceScope.cancel()
        logger.info("Streaming TTS service shutdown complete")
    }

    /**
     * Performs the actual TTS generation and streaming
     */
    private suspend fun performTTSStreaming(
        sessionId: String,
        text: String,
        languageCode: String,
        voice: String?,
        session: TTSStreamingSession,
    ) {
        val startTime = System.currentTimeMillis()

        try {
            logger.debug("Generating TTS audio for session $sessionId")

            // Emit generation started event
            emitStreamingEvent(
                TTSStreamingEvent(
                    sessionId = sessionId,
                    state = TTSStreamingState.GENERATING,
                ),
            )

            // Generate TTS audio
            val ttsResult =
                textToSpeechClient.synthesize(
                    text = text,
                    language = languageCode,
                    voice = voice,
                )

            if (ttsResult.isFailure) {
                throw TTSException("TTS generation failed", ttsResult.exceptionOrNull())
            }

            val synthesisResponse = ttsResult.getOrThrow()
            val generationTime = System.currentTimeMillis() - startTime

            logger.info("TTS generation completed for session $sessionId in ${generationTime}ms")

            // Decode base64 audio data
            val audioData = Base64.getDecoder().decode(synthesisResponse.audio)

            // Start streaming the audio chunks
            streamAudioChunks(sessionId, audioData, session, synthesisResponse)
        } catch (e: Exception) {
            logger.error("TTS streaming failed for session $sessionId", e)
            throw e
        }
    }

    /**
     * Streams audio data as chunks to the client
     */
    private suspend fun streamAudioChunks(
        sessionId: String,
        audioData: ByteArray,
        session: TTSStreamingSession,
        synthesisResponse: SynthesisResponse,
    ) {
        try {
            val chunkFlow = sessionChunkFlows[sessionId]
            if (chunkFlow == null || !session.isActive) {
                logger.warn("Session $sessionId no longer active, stopping streaming")
                return
            }

            logger.debug("Starting audio chunk streaming for session $sessionId, total bytes: ${audioData.size}")

            // Emit streaming started event
            emitStreamingEvent(
                TTSStreamingEvent(
                    sessionId = sessionId,
                    state = TTSStreamingState.STREAMING,
                    bytesStreamed = 0L,
                    estimatedDuration = synthesisResponse.durationSeconds?.let { (it * 1000).toLong().milliseconds },
                ),
            )

            val chunkSize = session.streamConfig.chunkSizeBytes
            val totalChunks = (audioData.size + chunkSize - 1) / chunkSize // Ceiling division

            var bytesSent = 0L
            var chunksSent = 0L

            // Stream audio in chunks
            for (i in 0 until totalChunks) {
                if (!session.isActive) {
                    logger.debug("Session $sessionId became inactive, stopping chunk streaming")
                    break
                }

                val startIndex = i * chunkSize
                val endIndex = kotlin.math.min(startIndex + chunkSize, audioData.size)
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
                    logger.warn("Failed to emit audio chunk ${audioChunk.sequenceNumber} for session $sessionId: buffer full")
                    // Continue with next chunk rather than failing completely
                }

                // Update session statistics
                bytesSent += chunkData.size
                chunksSent++
                session.totalBytesSent = bytesSent
                session.totalChunksSent = chunksSent

                // Update metrics
                updateStreamingMetrics(sessionId, chunksSent, bytesSent, TTSStreamingState.STREAMING)

                logger.debug("Sent audio chunk ${audioChunk.sequenceNumber} for session $sessionId: ${chunkData.size} bytes")

                // Small delay between chunks to prevent overwhelming the client
                kotlinx.coroutines.delay(10) // 10ms delay
            }

            // Emit completion event
            emitStreamingEvent(
                TTSStreamingEvent(
                    sessionId = sessionId,
                    state = TTSStreamingState.COMPLETED,
                    chunkSequence = chunksSent,
                    totalChunks = chunksSent,
                    bytesStreamed = bytesSent,
                ),
            )

            // Update final metrics
            updateStreamingMetrics(sessionId, chunksSent, bytesSent, TTSStreamingState.COMPLETED)

            logger.info("TTS streaming completed for session $sessionId: $chunksSent chunks, $bytesSent bytes")
        } catch (e: Exception) {
            logger.error("Failed to stream audio chunks for session $sessionId", e)
            throw TTSException("Audio chunk streaming failed", e)
        }
    }

    /**
     * Calculates estimated duration for an audio chunk
     */
    private fun calculateChunkDuration(
        chunkSizeBytes: Int,
        synthesisResponse: SynthesisResponse,
    ): Long? {
        return synthesisResponse.durationSeconds?.let { totalDuration ->
            // Estimate based on the proportion of total audio data
            val proportion = chunkSizeBytes.toDouble() / Base64.getDecoder().decode(synthesisResponse.audio).size
            (totalDuration * proportion * 1000).toLong() // Convert to milliseconds
        }
    }

    /**
     * Updates streaming metrics for a session
     */
    private fun updateStreamingMetrics(
        sessionId: String,
        chunksGenerated: Long,
        bytesGenerated: Long,
        state: TTSStreamingState,
    ) {
        val session = activeSessions[sessionId] ?: return
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
                generationLatency = 0.milliseconds, // TODO: Track actual generation latency
                streamingLatency = streamingDuration,
            )

        sessionMetrics[sessionId] = updatedMetrics
    }

    /**
     * Handles streaming errors and cleanup
     */
    private suspend fun handleStreamingError(
        sessionId: String,
        error: Exception,
    ) {
        try {
            // Mark session as error state
            sessionMetrics[sessionId]?.let { metrics ->
                sessionMetrics[sessionId] =
                    metrics.copy(
                        state = TTSStreamingState.ERROR,
                        errorCount = metrics.errorCount + 1,
                    )
            }

            // Emit error event
            emitStreamingEvent(
                TTSStreamingEvent(
                    sessionId = sessionId,
                    state = TTSStreamingState.ERROR,
                    errorMessage = error.message ?: "Unknown TTS streaming error",
                ),
            )

            // Clean up session
            stopTTSStreaming(sessionId)
        } catch (e: Exception) {
            logger.error("Failed to handle streaming error for session $sessionId", e)
        }
    }

    /**
     * Emits a TTS streaming event
     */
    private fun emitStreamingEvent(event: TTSStreamingEvent) {
        val emitted = eventFlow.tryEmit(event)
        if (!emitted) {
            logger.warn("Failed to emit TTS streaming event: buffer full")
        } else {
            logger.debug("Emitted TTS streaming event: ${event.state} for session ${event.sessionId}")
        }
    }
}
