package com.sermo

import com.sermo.plugins.configureCORS
import com.sermo.plugins.configureDI
import com.sermo.plugins.configureMonitoring
import com.sermo.plugins.configureRouting
import com.sermo.plugins.configureSerialization
import com.sermo.plugins.configureWebSockets
import com.sermo.session.SessionCoordinator
import com.sermo.session.SessionEventBus
import com.sermo.websocket.WebSocketEventRelay
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.koin.core.context.stopKoin
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

fun main() {
    val logger = LoggerFactory.getLogger("Sermo")

    val port = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("SERVER_HOST") ?: "0.0.0.0"

    logger.info("Starting Sermo API server on $host:$port")

    embeddedServer(Netty, port = port, host = host, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val logger = LoggerFactory.getLogger("Application")

    configureDI()
    configureCORS()
    configureSerialization()
    configureWebSockets()
    configureMonitoring()
    configureRouting()

    environment.monitor.subscribe(ApplicationStopped) {
        logger.info("Application stopping - cleaning up session management and Koin resources...")

        try {
            val webSocketEventRelay by inject<WebSocketEventRelay>()
            val sessionCoordinator by inject<SessionCoordinator>()
            val eventBus by inject<SessionEventBus>()

            runBlocking {
                webSocketEventRelay.shutdown()
                sessionCoordinator.shutdown()
                eventBus.shutdown()
            }

            logger.info("Session management cleanup completed")
        } catch (e: Exception) {
            logger.error("Error during session management cleanup", e)
        } finally {
            stopKoin()
            logger.info("Koin stopped - all resources cleaned up")
        }
    }
}
