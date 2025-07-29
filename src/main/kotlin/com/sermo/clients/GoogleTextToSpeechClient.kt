package com.sermo.clients

import com.google.cloud.texttospeech.v1.AudioConfig
import com.google.cloud.texttospeech.v1.AudioEncoding
import com.google.cloud.texttospeech.v1.SsmlVoiceGender
import com.google.cloud.texttospeech.v1.SynthesisInput
import com.google.cloud.texttospeech.v1.SynthesizeSpeechRequest
import com.google.cloud.texttospeech.v1.VoiceSelectionParams
import com.sermo.models.SynthesisResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Base64
import com.google.cloud.texttospeech.v1.TextToSpeechClient as GoogleCloudTextToSpeechClient

class GoogleTextToSpeechClient(
    private val googleCloudTextToSpeechClient: GoogleCloudTextToSpeechClient,
) : TextToSpeechClient {
    override suspend fun synthesize(
        text: String,
        language: String,
        voice: String?,
        speed: Double,
        pitch: Double,
    ): Result<SynthesisResponse> {
        return withContext(Dispatchers.IO) {
            try {
                logger.info("Google Text-to-Speech API call - Language: $language, Text length: ${text.length}")

                val input =
                    SynthesisInput.newBuilder()
                        .setText(text)
                        .build()

                val voiceParams =
                    VoiceSelectionParams.newBuilder()
                        .setLanguageCode(language)
                        .setSsmlGender(SsmlVoiceGender.NEUTRAL)
                        .apply {
                            voice?.let { name = it }
                        }
                        .build()

                val audioConfig =
                    AudioConfig.newBuilder()
                        .setAudioEncoding(AudioEncoding.MP3)
                        .setSpeakingRate(speed)
                        .setPitch(pitch)
                        .build()

                val request =
                    SynthesizeSpeechRequest.newBuilder()
                        .setInput(input)
                        .setVoice(voiceParams)
                        .setAudioConfig(audioConfig)
                        .build()

                logger.debug("Calling Google Cloud Text-to-Speech API with input {}", request)
                val response = googleCloudTextToSpeechClient.synthesizeSpeech(request)

                // Convert audio content to base64
                val audioBase64 = Base64.getEncoder().encodeToString(response.audioContent.toByteArray())

                val synthesisResult =
                    SynthesisResponse(
                        audio = audioBase64,
                        audioFormat = SynthesisResponse.AudioFormat.mp3,
                        detectedLanguage = language,
                        voiceUsed = voice ?: "$language-Standard-A",
                    )

                logger.info("Google Text-to-Speech API success - Audio size: ${response.audioContent.size()} bytes")
                Result.success(synthesisResult)
            } catch (e: Exception) {
                logger.error("Google Text-to-Speech API call failed", e)
                Result.failure(e)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(GoogleTextToSpeechClient::class.java)
    }
}
