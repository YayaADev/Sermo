package com.sermo.modules

import com.sermo.clients.GoogleSpeechToTextClient
import com.sermo.clients.GoogleTextToSpeechClient
import com.sermo.clients.SpeechToText
import com.sermo.clients.TextToSpeechClient
import com.sermo.services.AudioLevelAnalyzer
import com.sermo.services.AudioLevelAnalyzerImpl
import com.sermo.services.AudioStreamingPipeline
import com.sermo.services.AudioStreamingPipelineImpl
import com.sermo.services.LanguageDetectionService
import com.sermo.services.SilenceDetector
import com.sermo.services.SilenceDetectorImpl
import com.sermo.services.SpeechToTextService
import com.sermo.services.TextToSpeechService
import com.sermo.services.TurnDetectionService
import com.sermo.services.TurnDetectionServiceImpl
import org.koin.dsl.module

val sermoModule =
    module {
        // Clients layer - external integrations
        single<SpeechToText> { GoogleSpeechToTextClient(get()) }
        single<TextToSpeechClient> { GoogleTextToSpeechClient(get()) }

        // Audio analysis layer - silence detection and turn management
        single<AudioLevelAnalyzer> { AudioLevelAnalyzerImpl() }
        single<SilenceDetector> { SilenceDetectorImpl() }
        single<TurnDetectionService> { TurnDetectionServiceImpl(get(), get()) }

        // Services layer - business logic
        single<SpeechToTextService> { SpeechToTextService(get()) }
        single<TextToSpeechService> { TextToSpeechService(get(), get()) }
        single<LanguageDetectionService> { LanguageDetectionService() }
        single<AudioStreamingPipeline> { AudioStreamingPipelineImpl(get(), get()) }
    }
