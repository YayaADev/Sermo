package com.sermo.modules

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.sermo.clients.ConversationAIClient
import com.sermo.clients.OpenAIConversationClient
import com.sermo.services.ConversationService
import com.sermo.services.GrammarCorrectionService
import org.koin.dsl.module
import org.slf4j.LoggerFactory

val openAIModule =
    module {

        single<OpenAIClient> {
            val logger = LoggerFactory.getLogger("OpenAIModule")

            val apiKey =
                System.getenv("OPENAI_API_KEY")
                    ?: throw IllegalStateException("OPENAI_API_KEY environment variable is required")

            try {
                logger.info("Creating OpenAI client...")
                val client =
                    OpenAIOkHttpClient.builder()
                        .apiKey(apiKey)
                        .build()
                logger.info("OpenAI client created successfully")
                client
            } catch (e: Exception) {
                logger.error("Failed to create OpenAI client", e)
                throw e
            }
        }

        single<ConversationAIClient> {
            OpenAIConversationClient(get())
        }

        single<ConversationService> {
            ConversationService(get())
        }

        single<GrammarCorrectionService> {
            GrammarCorrectionService(get())
        }
    }
