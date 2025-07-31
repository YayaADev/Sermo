package com.sermo.plugins

import com.sermo.presentation.websocket.WebSocketConstants
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import java.time.Duration

fun Application.configureWebSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofMillis(WebSocketConstants.PING_INTERVAL_MS)
        timeout = Duration.ofMillis(WebSocketConstants.CONNECTION_TIMEOUT_MS)
        maxFrameSize = WebSocketConstants.MAX_FRAME_SIZE.toLong()
        masking = false
    }
}
