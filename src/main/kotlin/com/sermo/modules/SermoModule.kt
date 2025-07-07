package com.sermo.modules

import com.sermo.clients.SpeechClient
import com.sermo.clients.GoogleSpeechClient
import com.sermo.services.SpeechService
import org.koin.dsl.module

val sermoModule = module {
    // Clients layer - external integrations
    single<SpeechClient> { GoogleSpeechClient(get()) }
    
    // Services layer - business logic
    single<SpeechService> { SpeechService(get()) }
}