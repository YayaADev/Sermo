package com.sermo.plugins

import com.sermo.routes.speechRoutes
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.application.call

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("🎤 Sermo API is running!")
        }

        get("/health") {
            call.respondText("OK")
        }

        // Speech API routes
        speechRoutes()
    }
}