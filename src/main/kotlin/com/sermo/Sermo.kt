package com.sermo

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.application.call

fun main() {
    val port = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("SERVER_HOST") ?: "0.0.0.0"
    
    embeddedServer(Netty, port = port, host = host) {
        configureRouting()
    }.start(wait = true)
}

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Sermo API is running!")
        }
        
        get("/health") {
            call.respondText("OK")
        }
    }
}