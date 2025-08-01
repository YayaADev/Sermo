package com.sermo.modules

import com.google.cloud.speech.v1.SpeechClient
import com.google.cloud.texttospeech.v1.TextToSpeechClient
import com.sermo.clients.GoogleStreamingSpeechToTextClient
import com.sermo.clients.StreamingSpeechToText
import org.koin.dsl.module
import org.slf4j.LoggerFactory

val googleCloudModule =
    module {

        single<SpeechClient> {
            val logger = LoggerFactory.getLogger("GoogleCloudModule")

            try {
                logger.info("Creating Google Cloud Speech client...")
                val client = SpeechClient.create()
                logger.info("Google Cloud Speech client created successfully")
                client
            } catch (e: Exception) {
                logger.error(" Failed to create Google Cloud Speech client", e)
                throw e
            }
        }

        single<TextToSpeechClient> {
            val logger = LoggerFactory.getLogger("GoogleCloudModule")

            try {
                logger.info("Creating Google Cloud Text-to-Speech client...")
                val client = TextToSpeechClient.create()
                logger.info("Google Cloud Text-to-Speech client created successfully")
                client
            } catch (e: Exception) {
                logger.error("Failed to create Google Cloud Text-to-Speech client", e)
                throw e
            }
        }

        single<StreamingSpeechToText> {
            val logger = LoggerFactory.getLogger("GoogleCloudModule")

            try {
                logger.info("Creating Google Streaming Speech-to-Text client...")
                val client = GoogleStreamingSpeechToTextClient(get())
                logger.info("Google Streaming Speech-to-Text client created successfully")
                client
            } catch (e: Exception) {
                logger.error("Failed to create Google Streaming Speech-to-Text client", e)
                throw e
            }
        }
    }
