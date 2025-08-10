package com.sermo.services

import com.sermo.clients.StreamingSpeechToText
import com.sermo.exceptions.AudioBufferException
import com.sermo.exceptions.AudioChunkSizeException
import com.sermo.exceptions.AudioConfigurationException
import com.sermo.exceptions.AudioProcessingException
import com.sermo.exceptions.AudioStateException
import com.sermo.models.AudioBufferStats
import com.sermo.models.AudioChunk
import com.sermo.models.AudioStreamConfig
import com.sermo.models.AudioStreamMetrics
import com.sermo.models.AudioStreamState
import com.sermo.models.Constants.DEFAULT_LANGUAGE_CODE
import com.sermo.models.STTStreamConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * Audio streaming pipeline implementation for processing WebSocket audio chunks
 */
class AudioStreamingPipeline(
    private val streamingSpeechToText: StreamingSpeechToText,
    private val turnDetectionService: TurnDetectionService? = null,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AudioStreamingPipeline::class.java)
        private const val AUDIO_BUFFER_CHANNEL_CAPACITY = 1000
        private const val METRICS_UPDATE_INTERVAL_MS = 1000L
        private const val MIN_CHUNK_SIZE_BYTES = 160
        private const val MAX_CHUNK_SIZE_BYTES = 65536
        private const val BYTES_PER_SAMPLE_16BIT = 2
    }

    private val streamState = AtomicReference(AudioStreamState.IDLE)
    private val sequenceCounter = AtomicLong(0L)
    private val totalChunksProcessed = AtomicLong(0L)
    private val totalBytesProcessed = AtomicLong(0L)
    private val errorCount = AtomicLong(0L)
    private val lastErrorTime = AtomicReference<Long?>(null)

    private val streamMutex = Mutex()
    private val audioBuffer = Channel<AudioChunk>(capacity = AUDIO_BUFFER_CHANNEL_CAPACITY)
    private val processedAudioFlow = MutableSharedFlow<AudioChunk>(replay = 0, extraBufferCapacity = 100)

    private var currentConfig: AudioStreamConfig? = null
    private var streamingScope: CoroutineScope? = null
    private var bufferingJob: Job? = null
    private var processingJob: Job? = null
    private var metricsJob: Job? = null

    private var bufferStartTime = 0L
    private var lastMetricsUpdate = 0L
    private var currentBufferSize = 0
    private var maxBufferSize = 0

    suspend fun startStreaming(config: AudioStreamConfig): Result<Unit> =
        streamMutex.withLock {
            return try {
                logger.info("Starting audio streaming pipeline with config: $config")

                if (streamState.get() != AudioStreamState.IDLE) {
                    logger.warn("Attempting to start streaming while in state: ${streamState.get()}")
                    stopStreamingInternal()
                }

                validateConfig(config)
                streamState.set(AudioStreamState.STREAMING)
                currentConfig = config

                // Create new coroutine scope for streaming operations
                streamingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

                // Initialize metrics tracking
                resetMetrics()

                // Start audio processing jobs
                startAudioProcessingJobs(config)

                logger.info("Audio streaming pipeline started successfully")
                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("Failed to start audio streaming pipeline", e)
                streamState.set(AudioStreamState.ERROR)
                errorCount.incrementAndGet()
                lastErrorTime.set(System.currentTimeMillis())

                val exception =
                    when (e) {
                        is AudioConfigurationException -> e
                        else -> AudioProcessingException("Failed to start audio streaming pipeline", e)
                    }
                Result.failure(exception)
            }
        }

    fun processAudioChunk(audioData: ByteArray): Result<Unit> {
        val currentState = streamState.get()
        if (currentState != AudioStreamState.STREAMING && currentState != AudioStreamState.BUFFERING) {
            return Result.failure(AudioStateException("Pipeline not in streaming state: $currentState"))
        }

        return try {
            validateAudioChunk(audioData)

            val audioChunk =
                AudioChunk(
                    data = audioData,
                    timestamp = System.currentTimeMillis(),
                    sequenceNumber = sequenceCounter.incrementAndGet(),
                )

            // Try to send to buffer channel (non-blocking)
            val sent = audioBuffer.trySend(audioChunk)
            if (sent.isFailure) {
                logger.warn("Audio buffer full, dropping chunk ${audioChunk.sequenceNumber}")
                return Result.failure(AudioBufferException("Audio buffer overflow"))
            }

            totalChunksProcessed.incrementAndGet()
            totalBytesProcessed.addAndGet(audioData.size.toLong())

            logger.debug("Processed audio chunk: ${audioData.size} bytes, sequence: ${audioChunk.sequenceNumber}")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to process audio chunk", e)
            errorCount.incrementAndGet()
            lastErrorTime.set(System.currentTimeMillis())
            Result.failure(AudioProcessingException("Failed to process audio chunk", e))
        }
    }

    suspend fun stopStreaming(): Result<Unit> =
        streamMutex.withLock {
            return try {
                logger.info("Stopping audio streaming pipeline")
                stopStreamingInternal()
                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("Error while stopping audio streaming pipeline", e)
                Result.failure(AudioProcessingException("Error stopping streaming", e))
            }
        }

    fun getState(): AudioStreamState = streamState.get()

    fun getMetrics(): AudioStreamMetrics {
        val currentTime = System.currentTimeMillis()
        val timeDeltaSeconds = (currentTime - lastMetricsUpdate) / 1000.0

        val throughput =
            if (timeDeltaSeconds > 0) {
                totalBytesProcessed.get() / timeDeltaSeconds
            } else {
                0.0
            }

        val chunksPerSecond =
            if (timeDeltaSeconds > 0) {
                totalChunksProcessed.get() / timeDeltaSeconds
            } else {
                0.0
            }

        val bufferStats =
            AudioBufferStats(
                currentBufferSize = currentBufferSize,
                maxBufferSize = maxBufferSize,
                totalChunksProcessed = totalChunksProcessed.get(),
                averageChunkSize =
                    if (totalChunksProcessed.get() > 0) {
                        totalBytesProcessed.get().toDouble() / totalChunksProcessed.get()
                    } else {
                        0.0
                    },
                bufferUtilization =
                    if (maxBufferSize > 0) {
                        currentBufferSize.toDouble() / maxBufferSize
                    } else {
                        0.0
                    },
                processingLatency =
                    if (bufferStartTime > 0) {
                        (currentTime - bufferStartTime).milliseconds
                    } else {
                        0.milliseconds
                    },
            )

        return AudioStreamMetrics(
            state = streamState.get(),
            bufferStats = bufferStats,
            throughputBytesPerSecond = throughput,
            chunksPerSecond = chunksPerSecond,
            errorCount = errorCount.get(),
            lastErrorTime = lastErrorTime.get(),
        )
    }

    fun getProcessedAudioFlow(): Flow<AudioChunk> = processedAudioFlow.asSharedFlow()

    fun isStreaming(): Boolean {
        val currentState = streamState.get()
        return currentState == AudioStreamState.STREAMING || currentState == AudioStreamState.BUFFERING
    }

    private suspend fun stopStreamingInternal() {
        logger.debug("Stopping streaming internal resources")

        try {
            streamState.set(AudioStreamState.STOPPING)

            // Cancel processing jobs
            processingJob?.cancel()
            bufferingJob?.cancel()
            metricsJob?.cancel()

            // Cancel streaming scope
            streamingScope?.cancel()

            // Clear audio buffer
            while (audioBuffer.tryReceive().isSuccess) {
                // Drain the buffer
            }

            // Reset state
            streamState.set(AudioStreamState.IDLE)
            currentConfig = null
            resetMetrics()

            logger.debug("Audio streaming pipeline stopped")
        } catch (e: Exception) {
            logger.warn("Error during streaming cleanup", e)
            streamState.set(AudioStreamState.ERROR)
        }
    }

    private fun validateConfig(config: AudioStreamConfig) {
        if (config.sampleRateHertz <= 0) {
            throw AudioConfigurationException("Invalid sample rate: ${config.sampleRateHertz}")
        }
        if (config.chunkSizeBytes < MIN_CHUNK_SIZE_BYTES || config.chunkSizeBytes > MAX_CHUNK_SIZE_BYTES) {
            throw AudioConfigurationException("Invalid chunk size: ${config.chunkSizeBytes}")
        }
        if (config.bufferSizeMs <= 0) {
            throw AudioConfigurationException("Invalid buffer size: ${config.bufferSizeMs}")
        }
    }

    private fun validateAudioChunk(audioData: ByteArray) {
        if (audioData.isEmpty()) {
            throw AudioChunkSizeException("Audio chunk is empty")
        }
        if (audioData.size > MAX_CHUNK_SIZE_BYTES) {
            throw AudioChunkSizeException("Audio chunk too large: ${audioData.size} bytes")
        }
        if (audioData.size < MIN_CHUNK_SIZE_BYTES) {
            throw AudioChunkSizeException("Audio chunk too small: ${audioData.size} bytes")
        }

        // Validate chunk size is multiple of sample size for 16-bit audio
        val config = currentConfig
        if (config != null && audioData.size % BYTES_PER_SAMPLE_16BIT != 0) {
            throw AudioChunkSizeException("Audio chunk size not aligned to sample boundary: ${audioData.size}")
        }
    }

    private fun startAudioProcessingJobs(config: AudioStreamConfig) {
        val scope = streamingScope ?: throw AudioProcessingException("Streaming scope not initialized")

        // Start buffering job - reads from channel and buffers audio
        bufferingJob =
            scope.launch {
                try {
                    bufferAudioChunks(config)
                } catch (e: Exception) {
                    logger.error("Audio buffering job failed", e)
                    streamState.set(AudioStreamState.ERROR)
                }
            }

        // Start processing job - forwards buffered audio to STT
        processingJob =
            scope.launch {
                try {
                    processBufferedAudio(config)
                } catch (e: Exception) {
                    logger.error("Audio processing job failed", e)
                    streamState.set(AudioStreamState.ERROR)
                }
            }

        // Start metrics update job
        metricsJob =
            scope.launch {
                try {
                    updateMetricsPeriodically()
                } catch (e: Exception) {
                    logger.error("Metrics update job failed", e)
                }
            }
    }

    private suspend fun bufferAudioChunks(config: AudioStreamConfig) {
        logger.debug("Starting audio buffering with buffer size: ${config.bufferSizeMs}ms")
        streamState.set(AudioStreamState.BUFFERING)

        audioBuffer.receiveAsFlow().collect { audioChunk ->
            try {
                currentBufferSize += audioChunk.data.size
                maxBufferSize = maxOf(maxBufferSize, currentBufferSize)

                if (bufferStartTime == 0L) {
                    bufferStartTime = audioChunk.timestamp
                }

                // Process audio chunk for turn detection if service is available
                turnDetectionService?.let { turnService ->
                    try {
                        // Use a dummy session ID for now - this should be passed from WebSocket handler
                        val sessionId = "default-session"
                        val turnResult = turnService.processAudioChunk(sessionId, audioChunk.data)
                        if (turnResult.isFailure) {
                            logger.warn("Turn detection failed for chunk: ${turnResult.exceptionOrNull()?.message}")
                        }
                    } catch (e: Exception) {
                        logger.warn("Error in turn detection processing", e)
                    }
                }

                // Forward to processed audio flow
                processedAudioFlow.tryEmit(audioChunk)

                logger.debug("Buffered audio chunk: ${audioChunk.data.size} bytes, buffer size: $currentBufferSize")
            } catch (e: Exception) {
                logger.error("Error buffering audio chunk", e)
                throw AudioBufferException("Failed to buffer audio chunk", e)
            }
        }
    }

    private suspend fun processBufferedAudio(config: AudioStreamConfig) {
        logger.debug("Starting audio processing to STT")

        // Start STT streaming with matching configuration
        val sttConfig =
            STTStreamConfig(
                languageCode = DEFAULT_LANGUAGE_CODE,
                sampleRateHertz = config.sampleRateHertz,
                encoding = config.encoding,
                enableInterimResults = true,
            )

        val sttResult = streamingSpeechToText.startStreaming(sttConfig)
        if (sttResult.isFailure) {
            logger.error("Failed to start STT streaming: ${sttResult.exceptionOrNull()}")
            throw AudioProcessingException("Failed to start STT streaming", sttResult.exceptionOrNull())
        }

        // Process audio chunks from the flow
        processedAudioFlow.collect { audioChunk ->
            try {
                val processingTime =
                    measureTime {
                        val sendResult = streamingSpeechToText.sendAudioChunk(audioChunk.data)
                        if (sendResult.isFailure) {
                            logger.warn("Failed to send audio chunk to STT: ${sendResult.exceptionOrNull()}")
                        }
                    }

                currentBufferSize = maxOf(0, currentBufferSize - audioChunk.data.size)

                logger.debug(
                    "Forwarded audio chunk to STT: ${audioChunk.data.size} bytes, processing time: ${processingTime.inWholeMilliseconds}ms",
                )
            } catch (e: Exception) {
                logger.error("Error processing audio chunk for STT", e)
                throw AudioProcessingException("Failed to process audio for STT", e)
            }
        }
    }

    private suspend fun updateMetricsPeriodically() {
        while (streamState.get() == AudioStreamState.STREAMING || streamState.get() == AudioStreamState.BUFFERING) {
            try {
                delay(METRICS_UPDATE_INTERVAL_MS)
                lastMetricsUpdate = System.currentTimeMillis()

                val metrics = getMetrics()
                logger.debug(
                    "Pipeline metrics: throughput=${String.format("%.1f", metrics.throughputBytesPerSecond)} B/s, " +
                        "chunks/s=${String.format("%.1f", metrics.chunksPerSecond)}, " +
                        "buffer=${metrics.bufferStats.currentBufferSize}/${metrics.bufferStats.maxBufferSize} bytes",
                )
            } catch (e: Exception) {
                logger.warn("Error updating metrics", e)
            }
        }
    }

    private fun resetMetrics() {
        totalChunksProcessed.set(0L)
        totalBytesProcessed.set(0L)
        errorCount.set(0L)
        lastErrorTime.set(null)
        lastMetricsUpdate = System.currentTimeMillis()
        bufferStartTime = 0L
        currentBufferSize = 0
        maxBufferSize = 0
    }
}
