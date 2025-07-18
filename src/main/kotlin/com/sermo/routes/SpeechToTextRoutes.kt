package com.sermo.routes

import com.sermo.models.TranscriptionRequest
import com.sermo.models.ErrorResponse
import com.sermo.models.ApiError
import com.sermo.services.SpeechToTextService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.util.Base64

fun Route.speechRoutes() {
    val speechToTextService by inject<SpeechToTextService>()
    val logger = LoggerFactory.getLogger("SpeechRoutes")

    route("/speech") {
        post("/transcribe") {
            try {
                val request = call.receive<TranscriptionRequest>()
                logger.info("Received transcription request - Language: ${request.language}")

                // Validate request
                if (request.audio.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            error = ApiError(
                                code = "INVALID_AUDIO",
                                message = "Audio data cannot be empty"
                            )
                        )
                    )
                    return@post
                }

                // Decode base64 audio
                val audioBytes = try {
                    Base64.getDecoder().decode(request.audio)
                } catch (_: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            error = ApiError(
                                code = "INVALID_BASE64",
                                message = "Invalid base64 audio data"
                            )
                        )
                    )
                    return@post
                }

                // Prepare context phrases
                val contextPhrases = request.context?.split(",")?.map { it.trim() } ?: emptyList()

                // Call speech service
                val result = speechToTextService.transcribeAudio(
                    audioBytes = audioBytes,
                    language = request.language ?: "en-US",
                    contextPhrases = contextPhrases
                )

                result.fold(
                    onSuccess = { transcriptionResult ->
                        call.respond(HttpStatusCode.OK, transcriptionResult)
                    },
                    onFailure = { error ->
                        logger.error("Transcription failed", error)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse(
                                error = ApiError(
                                    code = "TRANSCRIPTION_ERROR",
                                    message = "Failed to transcribe audio: ${error.message}"
                                )
                            )
                        )
                    }
                )

            } catch (e: Exception) {
                logger.error("Error processing transcription request", e)
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        error = ApiError(
                            code = "INVALID_REQUEST",
                            message = "Invalid request format"
                        )
                    )
                )
            }
        }
    }
}