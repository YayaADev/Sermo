package com.sermo.services

import com.google.cloud.speech.v1.SpeechClient
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.RecognizeRequest
import com.google.cloud.speech.v1.RecognitionAudio
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class GoogleSpeechToTextService(
    private val speechClient: SpeechClient
) : SpeechToText {
    private val logger = LoggerFactory.getLogger(GoogleSpeechToTextService::class.java)

    override suspend fun transcribe(
        audioBytes: ByteArray,
        language: String,
        contextPhrases: List<String>
    ): Result<TranscriptionResult> {
        return withContext(Dispatchers.IO) {
            try {
                logger.info("Minimal transcription - Language: $language, Size: ${audioBytes.size} bytes")

                val config = RecognitionConfig.newBuilder()
                    .setEncoding(RecognitionConfig.AudioEncoding.WEBM_OPUS) // Safe default
                    .setSampleRateHertz(48000) // Safe default
                    .setLanguageCode(language)
                    .build()

                // Create audio object
                val audio = RecognitionAudio.newBuilder()
                    .setContent(ByteString.copyFrom(audioBytes))
                    .build()

                // Create request
                val request = RecognizeRequest.newBuilder()
                    .setConfig(config)
                    .setAudio(audio)
                    .build()

                logger.info("Sending minimal request to Google Speech API...")
                val response = speechClient.recognize(request)

                // Handle response
                if (response.resultsCount == 0) {
                    logger.warn("No results from Google API")
                    return@withContext Result.success(
                        TranscriptionResult(
                            transcription = "",
                            confidence = 0.0,
                            detectedLanguage = language,
                            alternatives = emptyList()
                        )
                    )
                }

                // Get first result only
                val result = response.getResults(0)
                val alternative = result.getAlternatives(0)

                val transcriptionResult = TranscriptionResult(
                    transcription = alternative.transcript.trim(),
                    confidence = alternative.confidence.toDouble(),
                    detectedLanguage = language,
                    alternatives = emptyList()
                )

                logger.info("Success: '${transcriptionResult.transcription}' (${String.format("%.2f", transcriptionResult.confidence)})")
                Result.success(transcriptionResult)

            } catch (e: Exception) {
                logger.error("Google API call failed", e)
                Result.failure(e)
            }
        }
    }
}
