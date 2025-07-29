package com.sermo.clients

import com.sermo.models.ConversationResponse
import sermo.protocol.SermoProtocol

interface ConversationAIClient {
    suspend fun generateConversationResponse(
        userMessage: String,
        language: String,
        conversationHistory: List<SermoProtocol.ConversationTurn> = emptyList(),
    ): Result<ConversationResponse>

    suspend fun correctGrammar(
        text: String,
        language: String,
    ): Result<String>
}
