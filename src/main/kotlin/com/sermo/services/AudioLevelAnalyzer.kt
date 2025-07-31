package com.sermo.services

import com.sermo.exceptions.AudioProcessingException
import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Analyzes PCM audio level data to calculate amplitude and energy metrics for silence detection
 */
interface AudioLevelAnalyzer {
    /**
     * Analyzes an audio chunk and returns level analysis results
     */
    fun analyzeAudioLevel(audioData: ByteArray): AudioLevelAnalysis

    /**
     * Gets the current audio level threshold for silence detection
     */
    fun getSilenceThreshold(): Float

    /**
     * Updates the silence threshold based on background noise analysis
     */
    fun updateSilenceThreshold(threshold: Float)

    /**
     * Resets the analyzer state for a new conversation session
     */
    fun reset()
}

/**
 * Results of audio level analysis for a single audio chunk
 */
data class AudioLevelAnalysis(
    val amplitude: Float,
    val rmsLevel: Float,
    val energyLevel: Float,
    val isSilent: Boolean,
    val timestamp: Long,
)

/**
 * Implementation of audio level analyzer for PCM 16-bit audio data
 */
class AudioLevelAnalyzerImpl : AudioLevelAnalyzer {
    companion object {
        private val logger = LoggerFactory.getLogger(AudioLevelAnalyzerImpl::class.java)
        private const val BYTES_PER_SAMPLE_16BIT = 2
        private const val MAX_16BIT_VALUE = 32767.0f
        private const val DEFAULT_SILENCE_THRESHOLD = 0.02f
        private const val MIN_SILENCE_THRESHOLD = 0.001f
        private const val MAX_SILENCE_THRESHOLD = 0.1f
        private const val NOISE_FLOOR_SAMPLES = 50
    }

    private var silenceThreshold = DEFAULT_SILENCE_THRESHOLD
    private val noiseFloorBuffer = mutableListOf<Float>()
    private var adaptiveThresholdEnabled = true

    override fun analyzeAudioLevel(audioData: ByteArray): AudioLevelAnalysis {
        if (audioData.isEmpty()) {
            throw AudioProcessingException("Audio data is empty")
        }

        if (audioData.size % BYTES_PER_SAMPLE_16BIT != 0) {
            throw AudioProcessingException("Audio data size not aligned to 16-bit samples: ${audioData.size}")
        }

        val samples = convertBytesToSamples(audioData)
        val amplitude = calculateAmplitude(samples)
        val rmsLevel = calculateRMS(samples)
        val energyLevel = calculateEnergy(samples)
        val isSilent = determineSilence(amplitude, rmsLevel)

        // Update adaptive threshold if enabled
        if (adaptiveThresholdEnabled && isSilent) {
            updateNoiseFloor(rmsLevel)
        }

        val analysis =
            AudioLevelAnalysis(
                amplitude = amplitude,
                rmsLevel = rmsLevel,
                energyLevel = energyLevel,
                isSilent = isSilent,
                timestamp = System.currentTimeMillis(),
            )

        logger.debug(
            "Audio level analysis: amplitude=${String.format("%.4f", amplitude)}, " +
                "RMS=${String.format("%.4f", rmsLevel)}, " +
                "energy=${String.format("%.4f", energyLevel)}, " +
                "silent=$isSilent, threshold=${String.format("%.4f", silenceThreshold)}",
        )

        return analysis
    }

    override fun getSilenceThreshold(): Float = silenceThreshold

    override fun updateSilenceThreshold(threshold: Float) {
        if (threshold < MIN_SILENCE_THRESHOLD || threshold > MAX_SILENCE_THRESHOLD) {
            throw AudioProcessingException(
                "Silence threshold out of valid range: $threshold " +
                    "(must be between $MIN_SILENCE_THRESHOLD and $MAX_SILENCE_THRESHOLD)",
            )
        }

        val oldThreshold = silenceThreshold
        silenceThreshold = threshold

        logger.info("Updated silence threshold from $oldThreshold to $threshold")
    }

    override fun reset() {
        silenceThreshold = DEFAULT_SILENCE_THRESHOLD
        noiseFloorBuffer.clear()
        logger.debug("Audio level analyzer reset")
    }

    /**
     * Converts PCM 16-bit byte array to normalized float samples
     */
    private fun convertBytesToSamples(audioData: ByteArray): FloatArray {
        val sampleCount = audioData.size / BYTES_PER_SAMPLE_16BIT
        val samples = FloatArray(sampleCount)

        for (i in 0 until sampleCount) {
            val byteIndex = i * BYTES_PER_SAMPLE_16BIT

            // Convert little-endian 16-bit signed integer to float
            val sample16 =
                (audioData[byteIndex].toInt() and 0xFF) or
                    ((audioData[byteIndex + 1].toInt() and 0xFF) shl 8)

            // Convert to signed 16-bit value
            val signedSample = if (sample16 > 32767) sample16 - 65536 else sample16

            // Normalize to [-1.0, 1.0] range
            samples[i] = signedSample / MAX_16BIT_VALUE
        }

        return samples
    }

    /**
     * Calculates the peak amplitude (maximum absolute value) of audio samples
     */
    private fun calculateAmplitude(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0.0f

        var maxAmplitude = 0.0f
        for (sample in samples) {
            val absSample = abs(sample)
            if (absSample > maxAmplitude) {
                maxAmplitude = absSample
            }
        }

        return maxAmplitude
    }

    /**
     * Calculates the Root Mean Square (RMS) level of audio samples
     */
    private fun calculateRMS(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0.0f

        var sumOfSquares = 0.0
        for (sample in samples) {
            sumOfSquares += sample.toDouble().pow(2.0)
        }

        val meanSquare = sumOfSquares / samples.size
        return sqrt(meanSquare).toFloat()
    }

    /**
     * Calculates the energy level (sum of squares) of audio samples
     */
    private fun calculateEnergy(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0.0f

        var energy = 0.0
        for (sample in samples) {
            energy += sample.toDouble().pow(2.0)
        }

        return energy.toFloat()
    }

    /**
     * Determines if the audio chunk represents silence based on amplitude and RMS
     */
    private fun determineSilence(
        amplitude: Float,
        rmsLevel: Float,
    ): Boolean {
        // Use both amplitude and RMS for more accurate silence detection
        val amplitudeThreshold = silenceThreshold
        val rmsThreshold = silenceThreshold * 0.7f // RMS threshold slightly lower than amplitude

        return amplitude < amplitudeThreshold && rmsLevel < rmsThreshold
    }

    /**
     * Updates the noise floor estimation for adaptive threshold adjustment
     */
    private fun updateNoiseFloor(rmsLevel: Float) {
        noiseFloorBuffer.add(rmsLevel)

        // Keep only recent samples for noise floor calculation
        if (noiseFloorBuffer.size > NOISE_FLOOR_SAMPLES) {
            noiseFloorBuffer.removeAt(0)
        }

        // Update threshold based on noise floor every 10 samples
        if (noiseFloorBuffer.size >= 10 && noiseFloorBuffer.size % 10 == 0) {
            val averageNoiseFloor = noiseFloorBuffer.average().toFloat()
            val adaptiveThreshold =
                (averageNoiseFloor * 2.5f).coerceIn(
                    MIN_SILENCE_THRESHOLD,
                    MAX_SILENCE_THRESHOLD,
                )

            if (abs(adaptiveThreshold - silenceThreshold) > 0.005f) {
                logger.debug(
                    "Adaptive threshold update: ${String.format("%.4f", silenceThreshold)} -> " +
                        "${String.format("%.4f", adaptiveThreshold)} (noise floor: ${String.format("%.4f", averageNoiseFloor)})",
                )
                silenceThreshold = adaptiveThreshold
            }
        }
    }
}
