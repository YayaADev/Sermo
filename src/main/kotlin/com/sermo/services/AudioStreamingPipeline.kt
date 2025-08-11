package com.sermo.services

import com.sermo.clients.StreamingSpeechToText
import com.sermo.models.AudioEncoding
import com.sermo.models.AudioStreamConfig
import com.sermo.models.Constants.DEFAULT_LANGUAGE_CODE
import com.sermo.models.STTStreamConfig
import com.sermo.session.AudioStreamingStartedEvent
import com.sermo.session.AudioStreamingStoppedEvent
import com.sermo.session.STTTranscriptReceivedEvent
import com.sermo.session.SessionAwareService
import com.sermo.session.SessionCreatedEvent
import com.sermo.session.SessionEventBus
import com.sermo.session.SessionTerminatedEvent
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Audio streaming pipeline with session event management
 */
class AudioStreamingPipeline(
    val streamingSpeechToText: StreamingSpeechToText,
    eventBus: SessionEventBus,
) : SessionAwareService(eventBus, "AudioStreamingPipeline") {
    companion object {
        private val logger = LoggerFactory.getLogger(AudioStreamingPipeline::class.java)
    }

    // Per-session state
    private val sessionConfigs = ConcurrentHashMap<String, AudioStreamConfig>()
    private val sessionStreams = ConcurrentHashMap<String, Boolean>()
    private val sessionSTTStreams = ConcurrentHashMap<String, Boolean>()

    /**
     * Start streaming for a session
     */
    suspend fun startStreaming(
        sessionId: String,
        config: AudioStreamConfig,
    ): Result<Unit> {
        return try {
            logger.info("Starting audio streaming for session: $sessionId")

            sessionConfigs[sessionId] = config
            sessionStreams[sessionId] = true

            // Start STT streaming for this session
            val sttConfig =
                STTStreamConfig(
                    languageCode = DEFAULT_LANGUAGE_CODE,
                    sampleRateHertz = config.sampleRateHertz,
                    encoding = AudioEncoding.LINEAR16,
                    enableInterimResults = true,
                    singleUtterance = true, // Enable Google's built-in turn detection
                )

            val sttResult = streamingSpeechToText.startStreaming(sttConfig)
            if (sttResult.isSuccess) {
                sessionSTTStreams[sessionId] = true

                // Subscribe to STT transcript flow for this session
                subscribeToSTTTranscripts(sessionId)

                logger.info("STT streaming started for session: $sessionId")
            } else {
                logger.error("Failed to start STT streaming for session $sessionId", sttResult.exceptionOrNull())
                return Result.failure(Exception("Failed to start STT streaming", sttResult.exceptionOrNull()))
            }

            // Publish event
            publishEvent(
                AudioStreamingStartedEvent(
                    sessionId = sessionId,
                    sampleRate = config.sampleRateHertz,
                    encoding = config.encoding.name,
                ),
            )

            logger.info("Audio streaming started for session: $sessionId")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to start audio streaming for session $sessionId", e)
            publishError(sessionId, "AUDIO_STREAMING_START_ERROR", e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Process audio chunk for a session
     */
    suspend fun processAudioChunk(
        sessionId: String,
        audioData: ByteArray,
    ): Result<Unit> {
        if (!sessionStreams.containsKey(sessionId)) {
            return Result.failure(Exception("No active audio stream for session: $sessionId"))
        }

        return try {
            // Forward audio chunk to STT
            val sttResult = streamingSpeechToText.sendAudioChunk(audioData)
            if (sttResult.isFailure) {
                logger.warn("Failed to send audio chunk to STT for session $sessionId", sttResult.exceptionOrNull())
            }

            logger.debug("Processed audio chunk for session $sessionId: ${audioData.size} bytes")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to process audio chunk for session $sessionId", e)
            publishError(sessionId, "AUDIO_CHUNK_PROCESSING_ERROR", e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    /**
     * Subscribe to STT transcripts for a session
     */
    private fun subscribeToSTTTranscripts(sessionId: String) {
        // Launch coroutine to listen for STT transcripts and forward them to conversation flow
        eventBus.eventBusScope.launch {
            streamingSpeechToText.getTranscriptFlow().collect { transcriptResult ->
                try {
                    // Forward transcript to conversation flow via event
                    publishEvent(
                        STTTranscriptReceivedEvent(
                            sessionId = sessionId,
                            transcript = transcriptResult.transcript,
                            confidence = transcriptResult.confidence,
                            isFinal = transcriptResult.isFinal,
                            languageCode = transcriptResult.languageCode,
                        ),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to process STT transcript for session $sessionId", e)
                }
            }
        }
    }

    /**
     * Stop streaming for a session
     */
    suspend fun stopStreaming(sessionId: String): Result<Unit> {
        return try {
            logger.info("Stopping audio streaming for session: $sessionId")

            // Stop STT streaming
            if (sessionSTTStreams[sessionId] == true) {
                streamingSpeechToText.stopStreaming()
                sessionSTTStreams.remove(sessionId)
            }

            sessionConfigs.remove(sessionId)
            sessionStreams.remove(sessionId)

            // Publish event
            publishEvent(AudioStreamingStoppedEvent(sessionId = sessionId))

            logger.info("Audio streaming stopped for session: $sessionId")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to stop audio streaming for session $sessionId", e)
            Result.failure(e)
        }
    }

    /**
     * Check if streaming is active for a session
     */
    fun isStreamingActive(sessionId: String): Boolean {
        return sessionStreams[sessionId] == true
    }

    // Session event handlers
    override suspend fun onSessionCreated(event: SessionCreatedEvent): Result<Unit> {
        logger.debug("Audio pipeline ready for session: ${event.sessionId}")
        return Result.success(Unit)
    }

    override suspend fun onSessionTerminated(event: SessionTerminatedEvent): Result<Unit> {
        return try {
            logger.info("Cleaning up audio streaming for session: ${event.sessionId}")

            // Stop any active streaming
            if (isStreamingActive(event.sessionId)) {
                stopStreaming(event.sessionId)
            }

            // Clean up session-specific resources
            sessionConfigs.remove(event.sessionId)
            sessionStreams.remove(event.sessionId)
            sessionSTTStreams.remove(event.sessionId)

            logger.debug("Audio streaming cleanup completed for session: ${event.sessionId}")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to cleanup audio streaming for session ${event.sessionId}", e)
            Result.failure(e)
        }
    }
}
