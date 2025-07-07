package com.sermo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import com.sermo.modules.appModule

fun Application.configureDI() {
    install(Koin) {
        slf4jLogger()
        modules(appModule)
    }
}