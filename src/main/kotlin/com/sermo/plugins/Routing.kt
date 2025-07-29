package com.sermo.plugins

import com.sermo.routes.speechToTextRoutes
import com.sermo.routes.textToSpeechRoutes
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Sermo API is running!")
        }

        get("/health") {
            call.respondText("OK")
        }

        speechToTextRoutes()
        textToSpeechRoutes()
    }
}
