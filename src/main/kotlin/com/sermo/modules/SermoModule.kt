package com.sermo.modules

import com.github.pemistahl.lingua.api.LanguageDetectorBuilder
import com.sermo.clients.GoogleSpeechToTextClient
import com.sermo.clients.GoogleTextToSpeechClient
import com.sermo.clients.SpeechToText
import com.sermo.clients.TextToSpeechClient
import com.sermo.services.AudioLevelAnalyzer
import com.sermo.services.AudioStreamingPipeline
import com.sermo.services.ConversationFlowManager
import com.sermo.services.ConversationFlowManagerImpl
import com.sermo.services.LanguageDetectionService
import com.sermo.services.SessionStateManagerImpl
import com.sermo.services.SilenceDetector
import com.sermo.services.SilenceDetectorImpl
import com.sermo.services.SpeechToTextService
import com.sermo.services.TextToSpeechService
import com.sermo.services.TurnDetectionService
import com.sermo.services.TurnDetectionServiceImpl
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val sermoModule =
    module {
        // JSON
        single<Json> {
            Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
                encodeDefaults = false
            }
        }

        single {
            LanguageDetectorBuilder
                .fromAllLanguages()
                .withPreloadedLanguageModels()
                .build()
        }

        // Clients layer - external integrations
        single<SpeechToText> { GoogleSpeechToTextClient(get()) }
        single<TextToSpeechClient> { GoogleTextToSpeechClient(get()) }

        // Audio analysis layer - silence detection and turn management
        single<AudioLevelAnalyzer> { AudioLevelAnalyzer() }
        single<SilenceDetector> { SilenceDetectorImpl() }
        single<TurnDetectionService> { TurnDetectionServiceImpl(get(), get()) }

        // Services layer - business logic
        single<SpeechToTextService> { SpeechToTextService(get()) }
        single<TextToSpeechService> { TextToSpeechService(get(), get()) }
        single<LanguageDetectionService> { LanguageDetectionService(get()) }
        single<AudioStreamingPipeline> { AudioStreamingPipeline(get(), get()) }

        // Session state management - coordinates all streaming components
        single<SessionStateManagerImpl> { SessionStateManagerImpl() }

        // Conversation flow layer - integrates GPT responses with turn detection
        single<ConversationFlowManager> { ConversationFlowManagerImpl(get(), get(), get(), get()) }
    }
