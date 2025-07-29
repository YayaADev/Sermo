package com.sermo.services

import com.sermo.clients.ConversationAIClient
import org.slf4j.LoggerFactory

class GrammarCorrectionService(
    private val conversationAIClient: ConversationAIClient,
) {
    private val logger = LoggerFactory.getLogger(GrammarCorrectionService::class.java)

    suspend fun correctText(
        text: String,
        language: String = "en-US",
    ): Result<String> {
        logger.info("Correcting grammar - Language: $language, Text: '${text.take(50)}...'")

        val result =
            conversationAIClient.correctGrammar(
                text = text,
                language = language,
            )

        result.fold(
            onSuccess = { correctedText ->
                logger.info("Grammar correction completed successfully")
            },
            onFailure = { error ->
                logger.error("Grammar correction failed", error)
            },
        )

        return result
    }
}
