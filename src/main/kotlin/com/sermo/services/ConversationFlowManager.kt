package com.sermo.services

import com.sermo.models.Constants.DEFAULT_LANGUAGE_CODE
import com.sermo.models.StreamingTranscriptResult
import sermo.protocol.SermoProtocol.ConversationTurn

/**
 * Manages conversation flow by coordinating turn detection, transcript buffering, GPT responses, and TTS generation
 */
interface ConversationFlowManager {
    /**
     * Starts conversation flow management for a session
     */
    suspend fun startConversationFlow(
        sessionId: String,
        languageCode: String = DEFAULT_LANGUAGE_CODE,
    ): Result<Unit>

    /**
     * Stops conversation flow for a session
     */
    suspend fun stopConversationFlow(sessionId: String): Result<Unit>

    /**
     * Processes a transcript result and buffers it for the session
     */
    suspend fun processTranscriptResult(
        sessionId: String,
        transcriptResult: StreamingTranscriptResult,
    ): Result<Unit>

    /**
     * Gets conversation history for a session
     */
    fun getConversationHistory(sessionId: String): List<ConversationTurn>

    /**
     * Checks if conversation flow is active for a session
     */
    fun isConversationActive(sessionId: String): Boolean

    /**
     * Shuts down the conversation flow manager
     */
    suspend fun shutdown()
}
