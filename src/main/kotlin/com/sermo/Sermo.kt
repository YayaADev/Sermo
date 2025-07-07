package com.sermo

import com.sermo.plugins.configureDI
import com.sermo.plugins.configureMonitoring
import com.sermo.plugins.configureRouting
import com.sermo.plugins.configureSerialization
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.koin.core.context.stopKoin
import org.slf4j.LoggerFactory

fun main() {
    val port = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("SERVER_HOST") ?: "0.0.0.0"

    println("🚀 Starting Sermo API server on $host:$port")

    embeddedServer(Netty, port = port, host = host, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    configureDI()
    configureSerialization()
    configureMonitoring()
    configureRouting()
    
    environment.monitor.subscribe(ApplicationStopped) {
        val logger = LoggerFactory.getLogger("Application")
        logger.info("Application stopping - cleaning up all Koin resources...")
        stopKoin()
        logger.info("Koin stopped - all resources cleaned up")
    }
}