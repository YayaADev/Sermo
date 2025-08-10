package com.sermo.clients

import com.google.api.gax.rpc.BidiStreamingCallable
import com.google.api.gax.rpc.ClientStream
import com.google.api.gax.rpc.ResponseObserver
import com.google.api.gax.rpc.StreamController
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.SpeechClient
import com.google.cloud.speech.v1.StreamingRecognitionConfig
import com.google.cloud.speech.v1.StreamingRecognitionResult
import com.google.cloud.speech.v1.StreamingRecognizeRequest
import com.google.cloud.speech.v1.StreamingRecognizeResponse
import com.google.protobuf.ByteString
import com.sermo.exceptions.STTAuthenticationException
import com.sermo.exceptions.STTConfigurationException
import com.sermo.exceptions.STTConnectionException
import com.sermo.exceptions.STTProcessingException
import com.sermo.exceptions.STTStreamTimeoutException
import com.sermo.models.AudioEncoding
import com.sermo.models.STTStreamConfig
import com.sermo.models.STTStreamState
import com.sermo.models.StreamingTranscriptResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Google Cloud Speech-to-Text streaming client with gRPC streaming support
 */
class GoogleStreamingSpeechToTextClient(
    private val speechClient: SpeechClient,
) : StreamingSpeechToText {
    companion object {
        private val logger = LoggerFactory.getLogger(GoogleStreamingSpeechToTextClient::class.java)
        private val STREAM_TIMEOUT_DURATION = 5.minutes
        private val CONNECTION_TIMEOUT_DURATION = 30.seconds
        private val AUDIO_CHUNK_MAX_SIZE = 65536
    }

    private val streamState = AtomicReference(STTStreamState.DISCONNECTED)
    private val transcriptFlow = MutableSharedFlow<StreamingTranscriptResult>(replay = 0, extraBufferCapacity = 100)
    private val streamMutex = Mutex()

    private var streamingScope: CoroutineScope? = null
    private var streamingJob: Job? = null
    private var currentConfig: STTStreamConfig? = null
    private var requestObserver: ClientStream<StreamingRecognizeRequest>? = null

    override suspend fun startStreaming(config: STTStreamConfig): Result<Unit> =
        streamMutex.withLock {
            return try {
                logger.info("Starting STT streaming session with language: ${config.languageCode}")

                if (streamState.get() != STTStreamState.DISCONNECTED) {
                    logger.warn("Attempting to start stream while in state: ${streamState.get()}")
                    stopStreamingInternal()
                }

                streamState.set(STTStreamState.CONNECTING)
                currentConfig = config

                // Create new coroutine scope for this streaming session
                streamingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

                // Automatically detects when the speaker stops talking.
                // Sends a final transcript and then ends the stream.
                // Triggers a special event internally: END_OF_SINGLE_UTTERANCE
                val recognitionConfig = createRecognitionConfig(config)
                val streamingConfig =
                    StreamingRecognitionConfig.newBuilder()
                        .setConfig(recognitionConfig)
                        .setInterimResults(config.enableInterimResults)
                        .setSingleUtterance(config.singleUtterance)
                        .build()

                val responseObserver = StreamingResponseObserver()

                withTimeout(CONNECTION_TIMEOUT_DURATION) {
                    val streamingCallable: BidiStreamingCallable<StreamingRecognizeRequest, StreamingRecognizeResponse> =
                        speechClient.streamingRecognizeCallable()

                    requestObserver = streamingCallable.splitCall(responseObserver)

                    // Send initial configuration request
                    val configRequest =
                        StreamingRecognizeRequest.newBuilder()
                            .setStreamingConfig(streamingConfig)
                            .build()

                    requestObserver?.send(configRequest)
                }

                streamState.set(STTStreamState.CONNECTED)
                logger.info("STT streaming session started successfully")
                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("Failed to start STT streaming session", e)
                streamState.set(STTStreamState.ERROR)

                val exception =
                    when {
                        e.message?.contains("authentication") == true -> STTAuthenticationException("Authentication failed", e)
                        e.message?.contains("timeout") == true -> STTStreamTimeoutException("Connection timeout", e)
                        e.message?.contains("config") == true -> STTConfigurationException("Invalid configuration", e)
                        else -> STTConnectionException("Failed to establish streaming connection", e)
                    }

                Result.failure(exception)
            }
        }

    override suspend fun sendAudioChunk(audioData: ByteArray): Result<Unit> {
        if (!isStreamActive()) {
            return Result.failure(STTConnectionException("Stream is not active"))
        }

        if (audioData.size > AUDIO_CHUNK_MAX_SIZE) {
            return Result.failure(STTProcessingException("Audio chunk size exceeds maximum allowed size"))
        }

        return try {
            val audioRequest =
                StreamingRecognizeRequest.newBuilder()
                    .setAudioContent(ByteString.copyFrom(audioData))
                    .build()

            requestObserver?.send(audioRequest)
            streamState.set(STTStreamState.RECEIVING)

            logger.debug("Sent audio chunk of ${audioData.size} bytes")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to send audio chunk", e)
            streamState.set(STTStreamState.ERROR)
            Result.failure(STTProcessingException("Failed to send audio chunk", e))
        }
    }

    override suspend fun stopStreaming(): Result<Unit> =
        streamMutex.withLock {
            return try {
                logger.info("Stopping STT streaming session")
                stopStreamingInternal()
                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("Error while stopping STT streaming session", e)
                Result.failure(STTProcessingException("Error stopping stream", e))
            }
        }

    override fun getStreamState(): STTStreamState = streamState.get()

    override fun getTranscriptFlow(): Flow<StreamingTranscriptResult> = transcriptFlow.asSharedFlow()

    override suspend fun restartStream(config: STTStreamConfig): Result<Unit> {
        logger.info("Restarting STT streaming session")

        // Stop current stream if active
        stopStreaming()

        // Wait a brief moment before restarting
        kotlinx.coroutines.delay(1000)

        // Start new stream
        return startStreaming(config)
    }

    override fun isStreamActive(): Boolean {
        val state = streamState.get()
        return state == STTStreamState.CONNECTED || state == STTStreamState.RECEIVING
    }

    private suspend fun stopStreamingInternal() {
        logger.debug("Stopping streaming internal resources")

        try {
            requestObserver?.closeSend()
            requestObserver = null

            streamingJob?.cancel()
            streamingJob = null

            streamingScope?.cancel()
            streamingScope = null

            streamState.set(STTStreamState.DISCONNECTED)
            currentConfig = null
        } catch (e: Exception) {
            logger.warn("Error during stream cleanup", e)
        }
    }

    private fun createRecognitionConfig(config: STTStreamConfig): RecognitionConfig {
        val encoding =
            when (config.encoding) {
                AudioEncoding.LINEAR16 -> RecognitionConfig.AudioEncoding.LINEAR16
                AudioEncoding.FLAC -> RecognitionConfig.AudioEncoding.FLAC
                AudioEncoding.MULAW -> RecognitionConfig.AudioEncoding.MULAW
                AudioEncoding.AMR -> RecognitionConfig.AudioEncoding.AMR
                AudioEncoding.AMR_WB -> RecognitionConfig.AudioEncoding.AMR_WB
                AudioEncoding.OGG_OPUS -> RecognitionConfig.AudioEncoding.OGG_OPUS
                AudioEncoding.SPEEX_WITH_HEADER_BYTE -> RecognitionConfig.AudioEncoding.SPEEX_WITH_HEADER_BYTE
                AudioEncoding.WEBM_OPUS -> RecognitionConfig.AudioEncoding.WEBM_OPUS
            }

        return RecognitionConfig.newBuilder()
            .setEncoding(encoding)
            .setSampleRateHertz(config.sampleRateHertz)
            .setLanguageCode(config.languageCode)
            .setMaxAlternatives(config.maxAlternatives)
            .setEnableAutomaticPunctuation(config.enableAutomaticPunctuation)
            .build()
    }

    /**
     * Observer for handling streaming recognition responses
     */
    private inner class StreamingResponseObserver : ResponseObserver<StreamingRecognizeResponse> {
        override fun onStart(controller: StreamController) {
            logger.debug("STT stream observer started")
        }

        override fun onResponse(response: StreamingRecognizeResponse) {
            try {
                if (response.resultsCount > 0) {
                    val result = response.getResults(0)
                    processStreamingResult(result)
                }
            } catch (e: Exception) {
                logger.error("Error processing streaming response", e)
                streamState.set(STTStreamState.ERROR)
            }
        }

        override fun onError(throwable: Throwable) {
            logger.error("STT streaming error occurred", throwable)
            streamState.set(STTStreamState.ERROR)

            streamingScope?.launch {
                val config = currentConfig
                if (config != null) {
                    logger.info("Attempting to restart stream after error")
                    restartStream(config)
                }
            }
        }

        override fun onComplete() {
            logger.info("STT streaming completed")
            streamState.set(STTStreamState.DISCONNECTED)
        }

        private fun processStreamingResult(result: StreamingRecognitionResult) {
            if (result.alternativesCount > 0) {
                val alternative = result.getAlternatives(0)
                val transcript = alternative.transcript.trim()

                if (transcript.isNotEmpty()) {
                    val alternatives =
                        result.alternativesList
                            .drop(1)
                            .map { it.transcript.trim() }
                            .filter { it.isNotEmpty() }

                    val streamingResult =
                        StreamingTranscriptResult(
                            transcript = transcript,
                            confidence = alternative.confidence,
                            isFinal = result.isFinal,
                            languageCode = currentConfig?.languageCode ?: "unknown",
                            alternatives = alternatives,
                        )

                    logger.debug(
                        "STT result: '${streamingResult.transcript}' " +
                            "(confidence: ${String.format("%.2f", streamingResult.confidence)}, " +
                            "final: ${streamingResult.isFinal})",
                    )

                    // Emit the result to the flow
                    transcriptFlow.tryEmit(streamingResult)
                }
            }
        }
    }
}
