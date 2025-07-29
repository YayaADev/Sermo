package com.sermo.services

import com.sermo.clients.ConversationAIClient
import com.sermo.models.ConversationResponse
import org.slf4j.LoggerFactory
import sermo.protocol.SermoProtocol.ConversationTurn

class ConversationService(
    private val conversationAIClient: ConversationAIClient,
) {
    private val logger = LoggerFactory.getLogger(ConversationService::class.java)

    suspend fun generateResponse(
        userMessage: String,
        language: String = "en-US",
        conversationHistory: List<ConversationTurn> = emptyList(),
    ): Result<ConversationResponse> {
        logger.info("Generating conversation response - Language: $language, Message: '${userMessage.take(50)}...'")

        val result =
            conversationAIClient.generateConversationResponse(
                userMessage = userMessage,
                language = language,
                conversationHistory = conversationHistory,
            )

        result.fold(
            onSuccess = { response ->
                logger.info("Conversation response generated successfully: '${response.aiResponse.take(50)}...'")
            },
            onFailure = { error ->
                logger.error("Conversation response generation failed", error)
            },
        )

        return result
    }
}
