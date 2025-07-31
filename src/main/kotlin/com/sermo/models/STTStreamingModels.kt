package com.sermo.models

/**
 * Represents a streaming transcript result from Google Speech-to-Text
 */
data class StreamingTranscriptResult(
    val transcript: String,
    val confidence: Float,
    val isFinal: Boolean,
    val languageCode: String,
    val alternatives: List<String> = emptyList(),
)

/**
 * Configuration for Google Speech-to-Text streaming recognition
 */
data class STTStreamConfig(
    val languageCode: String,
    val sampleRateHertz: Int = 16000,
    val encoding: AudioEncoding = AudioEncoding.LINEAR16,
    val enableAutomaticPunctuation: Boolean = true,
    val maxAlternatives: Int = 1,
    val enableInterimResults: Boolean = true,
    val singleUtterance: Boolean = false,
)

/**
 * Audio encoding formats supported by Google Speech-to-Text
 */
enum class AudioEncoding {
    LINEAR16,
    FLAC,
    MULAW,
    AMR,
    AMR_WB,
    OGG_OPUS,
    SPEEX_WITH_HEADER_BYTE,
    WEBM_OPUS,
}

/**
 * State of the STT streaming connection
 */
enum class STTStreamState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECEIVING,
    ERROR,
    RECONNECTING,
}
