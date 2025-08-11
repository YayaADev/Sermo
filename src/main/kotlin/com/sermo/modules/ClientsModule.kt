package com.sermo.modules

import com.google.cloud.speech.v1.SpeechClient
import com.google.cloud.texttospeech.v1.TextToSpeechClient
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.sermo.clients.ConversationAIClient
import com.sermo.clients.GoogleStreamingSpeechToTextClient
import com.sermo.clients.OpenAIConversationClient
import com.sermo.clients.StreamingSpeechToText
import org.koin.dsl.module
import org.slf4j.LoggerFactory

private val openAIApiKey: String =
    System.getenv("OPENAI_API_KEY")
        ?: throw IllegalStateException("OPENAI_API_KEY environment variable is required")

val clientsModule =
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

        single<OpenAIClient> {
            logger.info("Creating OpenAI client...")
            val client =
                OpenAIOkHttpClient.builder()
                    .apiKey(openAIApiKey)
                    .build()
            logger.info("OpenAI client created successfully")
            client
        }

        single<ConversationAIClient> {
            OpenAIConversationClient(get())
        }
    }
