package com.sermo.clients

import com.google.cloud.speech.v1.SpeechClient as GoogleCloudSpeechClient
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.RecognizeRequest
import com.google.cloud.speech.v1.RecognitionAudio
import com.google.protobuf.ByteString
import com.sermo.models.TranscriptionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class GoogleSpeechClient(
    private val googleCloudSpeechClient: GoogleCloudSpeechClient
) : SpeechClient {
    private val logger = LoggerFactory.getLogger(GoogleSpeechClient::class.java)

    override suspend fun transcribe(
        audioBytes: ByteArray,
        language: String,
        contextPhrases: List<String>
    ): Result<TranscriptionResponse> {
        return withContext(Dispatchers.IO) {
            try {
                logger.info("Google Speech API call - Language: $language, Size: ${audioBytes.size} bytes")

                val config = RecognitionConfig.newBuilder()
                    .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                    .setSampleRateHertz(48000)
                    .setLanguageCode(language)
                    .setMaxAlternatives(5)
                    .setEnableAutomaticPunctuation(true)
                    .build()

                val audio = RecognitionAudio.newBuilder()
                    .setContent(ByteString.copyFrom(audioBytes))
                    .build()

                val request = RecognizeRequest.newBuilder()
                    .setConfig(config)
                    .setAudio(audio)
                    .build()

                logger.debug("Calling Google Cloud Speech API...")
                val response = googleCloudSpeechClient.recognize(request)

                if (response.resultsCount == 0) {
                    logger.warn("No results from Google Cloud Speech API")
                    return@withContext Result.success(
                        TranscriptionResponse(
                            transcription = "",
                            confidence = 0.0,
                            detectedLanguage = language,
                            alternatives = emptyList()
                        )
                    )
                }

                val result = response.getResults(0)
                val alternative = result.getAlternatives(0)
                
                val alternatives = result.alternativesList
                    .drop(1)
                    .take(4)
                    .map { it.transcript }

                val transcriptionResult = TranscriptionResponse(
                    transcription = alternative.transcript.trim(),
                    confidence = alternative.confidence.toDouble(),
                    detectedLanguage = language,
                    alternatives = alternatives
                )

                logger.info("Google Speech API success: '${transcriptionResult.transcription}' (${String.format("%.2f", transcriptionResult.confidence)})")
                Result.success(transcriptionResult)

            } catch (e: Exception) {
                logger.error("Google Speech API call failed", e)
                Result.failure(e)
            }
        }
    }
}