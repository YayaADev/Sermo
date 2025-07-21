package com.sermo.routes

import com.sermo.models.SynthesisRequest
import com.sermo.models.ErrorResponse
import com.sermo.models.ApiError
import com.sermo.exceptions.ValidationException
import com.sermo.services.TextToSpeechService
import com.sermo.validation.SynthesisRequestValidator
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
                val rawRequest = call.receive<SynthesisRequest>()
                val request = SynthesisRequestValidator.validate(rawRequest)

                val result = textToSpeechService.synthesizeText(
                    text = request.text,
                    language = request.language,
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
                            ErrorResponse(ApiError("SYNTHESIS_ERROR", "Failed to synthesize text: ${error.message}"))
                        )
                    }
                )
            } catch (e: ValidationException) {
                logger.warn("Validation failed: ${e.message}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(ApiError(e.code, e.message))
                )
            } catch (e: Exception) {
                logger.error("Unexpected error", e)
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(ApiError("INVALID_REQUEST", "Invalid request format"))
                )
            }
        }
    }
}