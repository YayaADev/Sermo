package com.sermo.modules

import com.sermo.clients.SpeechToText
import com.sermo.clients.GoogleSpeechToTextClient
import com.sermo.clients.TextToSpeechClient
import com.sermo.clients.GoogleTextToSpeechClient
import com.sermo.services.SpeechService
import com.sermo.services.TextToSpeechService
import org.koin.dsl.module

val sermoModule = module {
    // Clients layer - external integrations
    single<SpeechToText> { GoogleSpeechToTextClient(get()) }
    single<TextToSpeechClient> { GoogleTextToSpeechClient(get()) }
    
    // Services layer - business logic
    single<SpeechService> { SpeechService(get()) }
    single<TextToSpeechService> { TextToSpeechService(get()) }
}