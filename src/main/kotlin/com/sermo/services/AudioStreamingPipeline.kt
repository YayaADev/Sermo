package com.sermo.services

import com.sermo.clients.StreamingSpeechToText
import com.sermo.models.AudioEncoding
import com.sermo.models.AudioStreamConfig
import com.sermo.models.Constants.DEFAULT_LANGUAGE_CODE
import com.sermo.models.STTStreamConfig
import com.sermo.session.AudioStreamingStartedEvent
import com.sermo.session.STTTranscriptReceivedEvent
import com.sermo.session.SessionAwareService
import com.sermo.session.SessionContextRegistry
import com.sermo.session.SessionCreatedEvent
import com.sermo.session.SessionEventBus
import com.sermo.session.SessionTerminatedEvent
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Refactored Audio Pipeline using centralized session context
 * No more manual state management!
 */
class AudioStreamingPipeline(
    val streamingSpeechToText: StreamingSpeechToText,
    private val contextRegistry: SessionContextRegistry,
    eventBus: SessionEventBus,
) : SessionAwareService(eventBus, "AudioStreamingPipelineV2") {
    companion object {
        private val logger = LoggerFactory.getLogger(AudioStreamingPipeline::class.java)
    }

    /**
     * Start streaming for a session - uses context instead of internal maps
     */
    suspend fun startStreaming(
        sessionId: String,
        config: AudioStreamConfig,
    ): Result<Unit> {
        return try {
            logger.info("Starting audio streaming for session: $sessionId")

            // Get session context (throws if not found)
            val context = contextRegistry.requireContext(sessionId)

            // Update context state (no more manual map management!)
            context.audioState.config = config
            context.audioState.isStreamingActive.set(true)

            // Start STT streaming
            val sttConfig =
                STTStreamConfig(
                    languageCode = DEFAULT_LANGUAGE_CODE,
                    sampleRateHertz = config.sampleRateHertz,
                    encoding = AudioEncoding.LINEAR16,
                    enableInterimResults = true,
                    singleUtterance = true,
                )

            val sttResult = streamingSpeechToText.startStreaming(sttConfig)
            if (sttResult.isSuccess) {
                context.audioState.isSTTStreamActive.set(true)
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
     * Process audio chunk - uses context
     */
    suspend fun processAudioChunk(
        sessionId: String,
        audioData: ByteArray,
    ): Result<Unit> {
        val context = contextRegistry.getContext(sessionId)
        if (context?.audioState?.isStreamingActive?.get() != true) {
            return Result.failure(Exception("No active audio stream for session: $sessionId"))
        }

        return try {
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
     * Check if streaming is active - uses context
     */
    fun isStreamingActive(sessionId: String): Boolean {
        return contextRegistry.getContext(sessionId)?.audioState?.isStreamingActive?.get() == true
    }

    private fun subscribeToSTTTranscripts(sessionId: String) {
        eventBus.eventBusScope.launch {
            streamingSpeechToText.getTranscriptFlow().collect { transcriptResult ->
                try {
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

    // Session event handlers - NO MORE MANUAL CLEANUP!
    override suspend fun onSessionCreated(event: SessionCreatedEvent): Result<Unit> {
        logger.debug("Audio pipeline ready for session: ${event.sessionId}")
        return Result.success(Unit)
    }

    override suspend fun onSessionTerminated(event: SessionTerminatedEvent): Result<Unit> {
        return try {
            logger.info("Cleaning up audio streaming for session: ${event.sessionId}")

            // The context is automatically cleaned up by the registry!
            // We only need to stop external resources like STT streams
            if (streamingSpeechToText.isStreamActive()) {
                streamingSpeechToText.stopStreaming()
            }

            logger.debug("Audio streaming cleanup completed for session: ${event.sessionId}")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to cleanup audio streaming for session ${event.sessionId}", e)
            Result.failure(e)
        }
    }
}
