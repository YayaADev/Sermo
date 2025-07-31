package com.sermo.services

import com.sermo.clients.StreamingSpeechToText
import com.sermo.exceptions.TranscriptRelaySessionException
import com.sermo.exceptions.TranscriptRelayStartException
import com.sermo.exceptions.TranscriptRelayStopException
import com.sermo.exceptions.TranscriptWebSocketRelayException
import com.sermo.models.StreamingTranscriptResult
import com.sermo.websocket.WebSocketHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service that relays real-time transcription results from STT to WebSocket clients
 */
class TranscriptRelayService(
    private val streamingSpeechToText: StreamingSpeechToText,
    private val webSocketHandler: WebSocketHandler,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(TranscriptRelayService::class.java)
        private const val MIN_CONFIDENCE_THRESHOLD = 0.1f
        private const val MIN_TRANSCRIPT_LENGTH = 1
        private const val MAX_TRANSCRIPT_LENGTH = 5000
    }

    private val isRunning = AtomicBoolean(false)
    private val sessionMutex = Mutex()
    private val activeRelayJobs = ConcurrentHashMap<String, Job>()

    private var relayScope: CoroutineScope? = null
    private var transcriptMonitoringJob: Job? = null

    /**
     * Starts the transcript relay service for monitoring STT results
     */
    suspend fun startTranscriptRelay(): Result<Unit> =
        sessionMutex.withLock {
            return try {
                if (isRunning.get()) {
                    logger.warn("Transcript relay service is already running")
                    return Result.success(Unit)
                }

                logger.info("Starting transcript relay service")

                relayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                isRunning.set(true)

                startTranscriptMonitoring()

                logger.info("Transcript relay service started successfully")
                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("Failed to start transcript relay service", e)
                isRunning.set(false)
                Result.failure(TranscriptRelayStartException("Failed to start transcript relay service", e))
            }
        }

    /**
     * Stops the transcript relay service and cleans up resources
     */
    suspend fun stopTranscriptRelay(): Result<Unit> =
        sessionMutex.withLock {
            return try {
                if (!isRunning.get()) {
                    logger.warn("Transcript relay service is not running")
                    return Result.success(Unit)
                }

                logger.info("Stopping transcript relay service")

                isRunning.set(false)

                // Cancel all active relay jobs
                activeRelayJobs.values.forEach { job ->
                    job.cancel()
                }
                activeRelayJobs.clear()

                // Cancel monitoring jobs
                transcriptMonitoringJob?.cancel()
                transcriptMonitoringJob = null

                // Cancel relay scope
                relayScope?.cancel()
                relayScope = null

                logger.info("Transcript relay service stopped successfully")
                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("Error while stopping transcript relay service", e)
                Result.failure(TranscriptRelayStopException("Error stopping transcript relay service", e))
            }
        }

    /**
     * Registers a session for transcript relay
     */
    suspend fun registerSession(sessionId: String): Result<Unit> =
        sessionMutex.withLock {
            return try {
                if (!isRunning.get()) {
                    return Result.failure(TranscriptRelaySessionException("Transcript relay service is not running"))
                }

                if (activeRelayJobs.containsKey(sessionId)) {
                    logger.warn("Session $sessionId is already registered for transcript relay")
                    return Result.success(Unit)
                }

                logger.info("Registering session $sessionId for transcript relay")

                val relayJob = startSessionRelayJob(sessionId)
                activeRelayJobs[sessionId] = relayJob

                logger.debug("Session $sessionId registered for transcript relay")
                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("Failed to register session $sessionId for transcript relay", e)
                Result.failure(TranscriptRelaySessionException("Failed to register session for transcript relay", e))
            }
        }

    /**
     * Unregisters a session from transcript relay
     */
    suspend fun unregisterSession(sessionId: String): Result<Unit> =
        sessionMutex.withLock {
            return try {
                logger.info("Unregistering session $sessionId from transcript relay")

                val relayJob = activeRelayJobs.remove(sessionId)
                relayJob?.cancel()

                logger.debug("Session $sessionId unregistered from transcript relay")
                Result.success(Unit)
            } catch (e: Exception) {
                logger.error("Failed to unregister session $sessionId from transcript relay", e)
                Result.failure(TranscriptRelaySessionException("Failed to unregister session from transcript relay", e))
            }
        }

    /**
     * Checks if the relay service is currently running
     */
    fun isRunning(): Boolean = isRunning.get()

    /**
     * Gets the count of active relay sessions
     */
    fun getActiveSessionCount(): Int = activeRelayJobs.size

    /**
     * Starts monitoring the STT transcript flow
     */
    private fun startTranscriptMonitoring() {
        val scope = relayScope ?: throw TranscriptRelayStartException("Relay scope not initialized")

        transcriptMonitoringJob =
            scope.launch {
                try {
                    logger.debug("Starting transcript flow monitoring")

                    streamingSpeechToText.getTranscriptFlow()
                        .catch { exception ->
                            logger.error("Error in transcript flow", exception)
                            // Continue monitoring despite errors
                        }
                        .collect { transcriptResult ->
                            try {
                                processTranscriptResult(transcriptResult)
                            } catch (e: Exception) {
                                logger.error("Failed to process transcript result", e)
                                // Continue processing other results
                            }
                        }
                } catch (e: Exception) {
                    logger.error("Transcript monitoring job failed", e)
                    isRunning.set(false)
                }
            }
    }

    /**
     * Processes a transcript result and relays it to active sessions
     */
    private suspend fun processTranscriptResult(result: StreamingTranscriptResult) {
        try {
            // Validate transcript result
            if (!isValidTranscriptResult(result)) {
                logger.debug("Skipping invalid transcript result: $result")
                return
            }

            logger.debug(
                "Processing transcript result: '${result.transcript}' " +
                    "(confidence: ${String.format("%.2f", result.confidence)}, " +
                    "final: ${result.isFinal}, language: ${result.languageCode})",
            )

            // Relay to all active sessions
            val sessionIds = activeRelayJobs.keys.toList()
            sessionIds.forEach { sessionId ->
                try {
                    relayTranscriptToSession(sessionId, result)
                } catch (e: Exception) {
                    logger.error("Failed to relay transcript to session $sessionId", e)
                    // Continue relaying to other sessions
                }
            }
        } catch (e: Exception) {
            logger.error("Error processing transcript result", e)
        }
    }

    /**
     * Relays a transcript result to a specific session
     */
    private suspend fun relayTranscriptToSession(
        sessionId: String,
        result: StreamingTranscriptResult,
    ) {
        try {
            if (result.isFinal) {
                // Send final transcript
                webSocketHandler.sendFinalTranscript(
                    sessionId = sessionId,
                    transcript = result.transcript,
                    confidence = result.confidence,
                    languageCode = result.languageCode,
                )

                logger.debug("Sent final transcript to session $sessionId: '${result.transcript}'")
            } else {
                // Send partial transcript
                webSocketHandler.sendPartialTranscript(
                    sessionId = sessionId,
                    transcript = result.transcript,
                    confidence = result.confidence,
                )

                logger.debug("Sent partial transcript to session $sessionId: '${result.transcript}'")
            }
        } catch (e: Exception) {
            logger.error("Failed to relay transcript to session $sessionId", e)
            throw TranscriptWebSocketRelayException("Failed to relay transcript to session", e)
        }
    }

    /**
     * Starts a relay job for a specific session
     */
    private fun startSessionRelayJob(sessionId: String): Job {
        val scope = relayScope ?: throw TranscriptRelayStartException("Relay scope not initialized")

        return scope.launch {
            try {
                logger.debug("Starting relay job for session $sessionId")

                // The job mainly exists to track session lifecycle
                // The actual relaying is handled by the transcript monitoring job
                // This job can be used for session-specific processing if needed in the future

                while (isRunning.get() && activeRelayJobs.containsKey(sessionId)) {
                    kotlinx.coroutines.delay(1000) // Keep job alive
                }

                logger.debug("Relay job completed for session $sessionId")
            } catch (e: Exception) {
                logger.error("Relay job failed for session $sessionId", e)
            }
        }
    }

    /**
     * Validates a transcript result before relaying
     */
    private fun isValidTranscriptResult(result: StreamingTranscriptResult): Boolean {
        // Check confidence threshold
        if (result.confidence < MIN_CONFIDENCE_THRESHOLD) {
            logger.debug("Transcript confidence too low: ${result.confidence}")
            return false
        }

        // Check transcript length
        val trimmedTranscript = result.transcript.trim()
        if (trimmedTranscript.length < MIN_TRANSCRIPT_LENGTH) {
            logger.debug("Transcript too short: '$trimmedTranscript'")
            return false
        }

        if (trimmedTranscript.length > MAX_TRANSCRIPT_LENGTH) {
            logger.debug("Transcript too long: ${trimmedTranscript.length} characters")
            return false
        }

        // Check for empty or whitespace-only transcripts
        if (trimmedTranscript.isBlank()) {
            logger.debug("Transcript is blank")
            return false
        }

        // Check language code
        if (result.languageCode.isBlank()) {
            logger.debug("Missing language code")
            return false
        }

        return true
    }
}
