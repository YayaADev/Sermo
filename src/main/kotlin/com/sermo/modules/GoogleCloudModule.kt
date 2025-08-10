package com.sermo.modules

import com.google.cloud.speech.v1.SpeechClient
import com.google.cloud.texttospeech.v1.TextToSpeechClient
import com.sermo.clients.GoogleStreamingSpeechToTextClient
import com.sermo.clients.StreamingSpeechToText
import org.koin.dsl.module
import org.slf4j.LoggerFactory

val googleCloudModule =
    module {
        val logger = LoggerFactory.getLogger("GoogleCloudModule")

        single<SpeechClient> {
            logger.info("Creating Google Cloud Speech client...")
            SpeechClient.create()
        }

        single<TextToSpeechClient> {
            logger.info("Creating Google Cloud Text-to-Speech client...")
            TextToSpeechClient.create()
        }

        single<StreamingSpeechToText> {
            logger.info("Creating Google Streaming Speech-to-Text client...")
            GoogleStreamingSpeechToTextClient(get())
        }
    }
