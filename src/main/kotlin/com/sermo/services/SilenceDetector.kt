package com.sermo.services

import com.sermo.exceptions.AudioProcessingException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Detects silence periods in audio stream and triggers conversation turn events
 */
interface SilenceDetector {
    /**
     * Processes audio level analysis and updates silence detection state
     */
    fun processAudioLevel(analysis: AudioLevelAnalyzer.AudioLevelAnalysis)

    /**
     * Gets a flow of silence detection events
     */
    fun getSilenceEventFlow(): Flow<SilenceDetectionEvent>

    /**
     * Gets the current silence detection state
     */
    fun getCurrentState(): SilenceDetectionState

    /**
     * Resets the silence detector for a new conversation turn
     */
    fun reset()

    /**
     * Updates the silence threshold duration
     */
    fun updateSilenceThreshold(thresholdMs: Long)

    /**
     * Enables or disables silence detection
     */
    fun setEnabled(enabled: Boolean)
}

/**
 * Event emitted when silence is detected or speech resumes
 */
data class SilenceDetectionEvent(
    val type: SilenceEventType,
    val silenceDuration: Duration,
    val timestamp: Long,
    val confidenceLevel: Float,
)

/**
 * Types of silence detection events
 */
enum class SilenceEventType {
    SILENCE_STARTED,
    SILENCE_DETECTED,
    SPEECH_RESUMED,
}

/**
 * Current state of silence detection
 */
data class SilenceDetectionState(
    val isInSilence: Boolean,
    val silenceStartTime: Long?,
    val currentSilenceDuration: Duration,
    val isEnabled: Boolean,
    val thresholdMs: Long,
)

/**
 * Implementation of silence detector with configurable threshold
 */
class SilenceDetectorImpl(
    initialThresholdMs: Long = DEFAULT_SILENCE_THRESHOLD_MS,
) : SilenceDetector {
    companion object {
        private val logger = LoggerFactory.getLogger(SilenceDetectorImpl::class.java)
        private const val DEFAULT_SILENCE_THRESHOLD_MS = 1500L
        private const val MIN_SILENCE_THRESHOLD_MS = 500L
        private const val MAX_SILENCE_THRESHOLD_MS = 10000L
        private const val CONFIDENCE_DECAY_FACTOR = 0.95f
        private const val MIN_SPEECH_DURATION_MS = 200L
    }

    private val silenceEventFlow =
        MutableSharedFlow<SilenceDetectionEvent>(
            replay = 0,
            extraBufferCapacity = 10,
        )

    private val isEnabled = AtomicBoolean(true)
    private val isInSilence = AtomicBoolean(false)
    private val silenceStartTime = AtomicLong(0L)
    private val lastSpeechTime = AtomicLong(0L)
    private var silenceThresholdMs = initialThresholdMs
    private var confidenceLevel = 1.0f
    private var consecutiveSilentChunks = 0
    private var consecutiveSpeechChunks = 0

    override fun processAudioLevel(analysis: AudioLevelAnalyzer.AudioLevelAnalysis) {
        if (!isEnabled.get()) {
            return
        }

        try {
            val currentTime = analysis.timestamp
            val wasSilent = isInSilence.get()

            if (analysis.isSilent) {
                handleSilentChunk(currentTime, wasSilent)
            } else {
                handleSpeechChunk(currentTime, wasSilent)
            }

            // Update confidence level based on consistency
            updateConfidenceLevel(analysis.isSilent)

            logger.debug(
                "Silence detection: silent=${analysis.isSilent}, " +
                    "inSilence=${isInSilence.get()}, " +
                    "duration=${getCurrentSilenceDuration().inWholeMilliseconds}ms, " +
                    "confidence=${String.format("%.3f", confidenceLevel)}",
            )
        } catch (e: Exception) {
            logger.error("Error processing audio level for silence detection", e)
            throw AudioProcessingException("Failed to process audio level", e)
        }
    }

    override fun getSilenceEventFlow(): Flow<SilenceDetectionEvent> = silenceEventFlow.asSharedFlow()

    override fun getCurrentState(): SilenceDetectionState {
        return SilenceDetectionState(
            isInSilence = isInSilence.get(),
            silenceStartTime = silenceStartTime.get().takeIf { it > 0L },
            currentSilenceDuration = getCurrentSilenceDuration(),
            isEnabled = isEnabled.get(),
            thresholdMs = silenceThresholdMs,
        )
    }

    override fun reset() {
        isInSilence.set(false)
        silenceStartTime.set(0L)
        lastSpeechTime.set(0L)
        confidenceLevel = 1.0f
        consecutiveSilentChunks = 0
        consecutiveSpeechChunks = 0

        logger.debug("Silence detector reset")
    }

    override fun updateSilenceThreshold(thresholdMs: Long) {
        if (thresholdMs < MIN_SILENCE_THRESHOLD_MS || thresholdMs > MAX_SILENCE_THRESHOLD_MS) {
            throw AudioProcessingException(
                "Silence threshold out of valid range: ${thresholdMs}ms " +
                    "(must be between ${MIN_SILENCE_THRESHOLD_MS}ms and ${MAX_SILENCE_THRESHOLD_MS}ms)",
            )
        }

        val oldThreshold = silenceThresholdMs
        silenceThresholdMs = thresholdMs

        logger.info("Updated silence threshold from ${oldThreshold}ms to ${thresholdMs}ms")
    }

    override fun setEnabled(enabled: Boolean) {
        val wasEnabled = isEnabled.getAndSet(enabled)

        if (wasEnabled && !enabled) {
            // Disable: reset state but don't emit events
            reset()
            logger.info("Silence detection disabled")
        } else if (!wasEnabled && enabled) {
            // Enable: reset state
            reset()
            logger.info("Silence detection enabled")
        }
    }

    /**
     * Handles processing of a silent audio chunk
     */
    private fun handleSilentChunk(
        currentTime: Long,
        wasSilent: Boolean,
    ) {
        consecutiveSilentChunks++
        consecutiveSpeechChunks = 0

        if (!wasSilent) {
            // Transition from speech to silence
            isInSilence.set(true)
            silenceStartTime.set(currentTime)

            emitSilenceEvent(
                SilenceEventType.SILENCE_STARTED,
                0.milliseconds,
                currentTime,
            )

            logger.debug("Silence started at timestamp $currentTime")
        } else {
            // Already in silence, check if threshold reached
            val silenceDuration = getCurrentSilenceDuration()

            if (silenceDuration.inWholeMilliseconds >= silenceThresholdMs) {
                // Emit silence detected event (but only once per silence period)
                if (consecutiveSilentChunks == calculateChunksForThreshold()) {
                    emitSilenceEvent(
                        SilenceEventType.SILENCE_DETECTED,
                        silenceDuration,
                        currentTime,
                    )

                    logger.info(
                        "Silence detected: duration=${silenceDuration.inWholeMilliseconds}ms, " +
                            "threshold=${silenceThresholdMs}ms, confidence=${String.format("%.3f", confidenceLevel)}",
                    )
                }
            }
        }
    }

    /**
     * Handles processing of a speech audio chunk
     */
    private fun handleSpeechChunk(
        currentTime: Long,
        wasSilent: Boolean,
    ) {
        consecutiveSpeechChunks++
        consecutiveSilentChunks = 0
        lastSpeechTime.set(currentTime)

        if (wasSilent) {
            // Transition from silence to speech
            val silenceDuration = getCurrentSilenceDuration()

            // Only emit speech resumed if we were in silence for minimum duration
            if (silenceDuration.inWholeMilliseconds >= MIN_SPEECH_DURATION_MS) {
                emitSilenceEvent(
                    SilenceEventType.SPEECH_RESUMED,
                    silenceDuration,
                    currentTime,
                )

                logger.debug(
                    "Speech resumed after ${silenceDuration.inWholeMilliseconds}ms silence " +
                        "at timestamp $currentTime",
                )
            }

            // Reset silence state
            isInSilence.set(false)
            silenceStartTime.set(0L)
        }
    }

    /**
     * Updates confidence level based on detection consistency
     */
    private fun updateConfidenceLevel(currentIsSilent: Boolean) {
        if (currentIsSilent) {
            // Increase confidence with consecutive silent chunks
            confidenceLevel = (confidenceLevel + 0.1f).coerceAtMost(1.0f)
        } else {
            // Slight decay for speech chunks to maintain some uncertainty
            confidenceLevel = (confidenceLevel * CONFIDENCE_DECAY_FACTOR).coerceAtLeast(0.1f)
        }
    }

    /**
     * Calculates the current silence duration
     */
    private fun getCurrentSilenceDuration(): Duration {
        val startTime = silenceStartTime.get()
        return if (startTime > 0L && isInSilence.get()) {
            (System.currentTimeMillis() - startTime).milliseconds
        } else {
            0.milliseconds
        }
    }

    /**
     * Calculates how many chunks should occur before threshold is reached
     */
    private fun calculateChunksForThreshold(): Int {
        // Assuming 100ms chunks (typical for real-time audio)
        val chunkDurationMs = 100L
        return (silenceThresholdMs / chunkDurationMs).toInt()
    }

    /**
     * Emits a silence detection event
     */
    private fun emitSilenceEvent(
        type: SilenceEventType,
        duration: Duration,
        timestamp: Long,
    ) {
        val event =
            SilenceDetectionEvent(
                type = type,
                silenceDuration = duration,
                timestamp = timestamp,
                confidenceLevel = confidenceLevel,
            )

        val emitted = silenceEventFlow.tryEmit(event)
        if (!emitted) {
            logger.warn("Failed to emit silence detection event: buffer full")
        } else {
            logger.debug("Emitted silence event: $type, duration=${duration.inWholeMilliseconds}ms")
        }
    }
}
