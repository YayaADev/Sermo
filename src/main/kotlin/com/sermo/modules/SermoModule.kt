package com.sermo.modules

import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder
import com.sermo.clients.GoogleSpeechToTextClient
import com.sermo.clients.GoogleTextToSpeechClient
import com.sermo.clients.SpeechToText
import com.sermo.clients.TextToSpeechClient
import com.sermo.services.AudioStreamingPipeline
import com.sermo.services.ConversationFlowManager
import com.sermo.services.ConversationService
import com.sermo.services.LanguageDetectionService
import com.sermo.services.SpeechToTextService
import com.sermo.services.StreamingTextToSpeechService
import com.sermo.services.TextToSpeechService
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
                .fromLanguages(
                    Language.ENGLISH,
                    Language.SPANISH,
                    Language.ARABIC,
                    Language.TURKISH,
                    Language.GERMAN,
                    Language.ITALIAN,
                )
                .withLowAccuracyMode()
                .build()
        }

        // Clients layer - external integrations
        single<SpeechToText> { GoogleSpeechToTextClient(get()) }
        single<TextToSpeechClient> { GoogleTextToSpeechClient(get()) }

        // Services layer - business logic (simplified)
        single<SpeechToTextService> { SpeechToTextService(get()) }
        single<TextToSpeechService> { TextToSpeechService(get(), get()) }
        single<LanguageDetectionService> { LanguageDetectionService(get()) }
        single<AudioStreamingPipeline> { AudioStreamingPipeline(get(), get()) }
        single<ConversationService> { ConversationService(get()) }
        single { ConversationFlowManager(get(), get(), get()) } // conversationService, ttsService, eventBus
        single<StreamingTextToSpeechService> {
            StreamingTextToSpeechService(get(), get(), get()) // ttsClient, languageDetection, eventBus
        }
    }
