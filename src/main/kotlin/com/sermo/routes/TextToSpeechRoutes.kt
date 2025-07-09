package com.sermo.routes

import com.sermo.models.SynthesisRequest
import com.sermo.models.ErrorResponse
import com.sermo.models.ApiError
import com.sermo.services.TextToSpeechService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

fun Route.textToSpeechRoutes() {
    val textToSpeechService by inject<TextToSpeechService>()
    val logger = LoggerFactory.getLogger("TextToSpeechRoutes")

    route("/speech") {
        post("/synthesize") {
            try {
                val request = call.receive<SynthesisRequest>()
                logger.info("Received synthesis request - Language: ${request.language}, Text length: ${request.text.length}")

                // Validate request
                if (request.text.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            error = ApiError(
                                code = "INVALID_TEXT",
                                message = "Text cannot be empty"
                            )
                        )
                    )
                    return@post
                }

                // Call text-to-speech service
                val result = textToSpeechService.synthesizeText(
                    text = request.text,
                    language = request.language ?: "en-US",
                    voice = request.voice,
                    speed = request.speed ?: 1.0,
                    pitch = request.pitch ?: 0.0
                )

                result.fold(
                    onSuccess = { synthesisResult ->
                        call.respond(HttpStatusCode.OK, synthesisResult)
                    },
                    onFailure = { error ->
                        logger.error("Synthesis failed", error)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse(
                                error = ApiError(
                                    code = "SYNTHESIS_ERROR",
                                    message = "Failed to synthesize text: ${error.message}"
                                )
                            )
                        )
                    }
                )

            } catch (e: Exception) {
                logger.error("Error processing synthesis request", e)
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