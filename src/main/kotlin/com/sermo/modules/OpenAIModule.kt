package com.sermo.modules

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.sermo.clients.ConversationAIClient
import com.sermo.clients.OpenAIConversationClient
import com.sermo.services.ConversationService
import org.koin.dsl.module
import org.slf4j.LoggerFactory

private val openAIApiKey: String =
    System.getenv("OPENAI_API_KEY")
        ?: throw IllegalStateException("OPENAI_API_KEY environment variable is required")

val openAIModule =
    module {
        val logger = LoggerFactory.getLogger("OpenAIModule")

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

        single<ConversationService> {
            ConversationService(get())
        }
    }
