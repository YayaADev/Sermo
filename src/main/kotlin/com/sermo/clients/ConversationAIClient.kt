package com.sermo.clients

import com.sermo.models.ConversationResponse
import com.sermo.models.ConversationTurn

interface ConversationAIClient {
    suspend fun generateConversationResponse(
        userMessage: String,
        language: String,
        conversationHistory: List<ConversationTurn> = emptyList()
    ): Result<ConversationResponse>
    
    suspend fun correctGrammar(
        text: String,
        language: String
    ): Result<String>
}