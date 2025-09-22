package com.sermo.clients

import com.sermo.models.ConversationResponse
import sermo.protocol.SermoProtocol

/**
 * Generates the response with AI, simulating real conversation explanation
 */
interface ConversationAIClient {
    suspend fun generateConversationResponse(
        userMessage: String,
        language: String,
        conversationHistory: List<SermoProtocol.ConversationTurn> = emptyList(),
    ): Result<ConversationResponse>
}
