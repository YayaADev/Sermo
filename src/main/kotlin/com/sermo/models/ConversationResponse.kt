package com.sermo.models

import kotlinx.serialization.Serializable

@Serializable
data class ConversationResponse(
    val aiResponse: String,
    val sessionId: String? = null,
)
