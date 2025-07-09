package com.sermo.modules

import com.sermo.clients.SpeechClient
import com.sermo.clients.GoogleSpeechClient
import com.sermo.clients.TextToSpeechClient
import com.sermo.clients.GoogleTextToSpeechClient
import com.sermo.services.SpeechService
import com.sermo.services.TextToSpeechService
import org.koin.dsl.module

val sermoModule = module {
    // Clients layer - external integrations
    single<SpeechClient> { GoogleSpeechClient(get()) }
    single<TextToSpeechClient> { GoogleTextToSpeechClient(get()) }
    
    // Services layer - business logic
    single<SpeechService> { SpeechService(get()) }
    single<TextToSpeechService> { TextToSpeechService(get()) }
}