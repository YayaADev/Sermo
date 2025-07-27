package com.sermo.models

/**
 * Represents one exchange in the conversation.
 * `userMessage` is what the learner said.
 * `assistantReply` is the AI’s previous response.
 */
data class ConversationTurn(
    val userMessage: String,
    val assistantReply: String
)
