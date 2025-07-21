package com.sermo.routes

import com.sermo.models.TranscriptionRequest
import com.sermo.models.ErrorResponse
import com.sermo.models.ApiError
import com.sermo.services.SpeechToTextService
import com.sermo.validation.TranscriptionRequestValidator
import com.sermo.exceptions.ValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

fun Route.speechToTextRoutes() {
    val speechToTextService by inject<SpeechToTextService>()
    val logger = LoggerFactory.getLogger("SpeechRoutes")

    route("/speech") {
        post("/transcribe") {
            try {
                val request = call.receive<TranscriptionRequest>()
                logger.info("Received transcription request - Language: ${request.language}")

                val validatedRequest = try {
                    TranscriptionRequestValidator.validate(request)
                } catch (e: ValidationException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            error = ApiError(
                                code = e.code,
                                message = e.message
                            )
                        )
                    )
                    return@post
                }

                // Call speech service
                val result = speechToTextService.transcribeAudio(
                    audioBytes = TranscriptionRequestValidator.getDecodedAudio(validatedRequest),
                    language = TranscriptionRequestValidator.getLanguageCode(validatedRequest),
                    contextPhrases = TranscriptionRequestValidator.getContextPhrases(validatedRequest)
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