package com.sermo.plugins

import com.sermo.routes.speechToTextRoutes
import com.sermo.routes.textToSpeechRoutes
import com.sermo.websocket.WebSocketConstants
import com.sermo.websocket.WebSocketHandler
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val webSocketHandler by inject<WebSocketHandler>()

    routing {
        get("/") {
            call.respondText("Sermo API is running!")
        }

        get("/health") {
            call.respondText("OK")
        }

        webSocket(WebSocketConstants.WEBSOCKET_ENDPOINT) {
            webSocketHandler.handleConnection(this)
        }

        speechToTextRoutes()
        textToSpeechRoutes()
    }
}
