package com.sermo.clients

import com.openai.client.OpenAIClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.sermo.models.ConversationResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import sermo.protocol.SermoProtocol.ConversationTurn
import sermo.protocol.SermoProtocol.Speaker
import kotlin.jvm.optionals.getOrNull

class OpenAIConversationClient(
    private val openAIClient: OpenAIClient,
) : ConversationAIClient {
    override suspend fun generateConversationResponse(
        userMessage: String,
        language: String,
        conversationHistory: List<ConversationTurn>,
    ): Result<ConversationResponse> =
        withContext(Dispatchers.IO) {
            try {
                logger.info("OpenAI conversation request - Language: $language, Message: '${userMessage.take(50)}...'")

                val messages =
                    buildList {
                        // System prompt
                        add(
                            ChatCompletionMessageParam.ofSystem(
                                ChatCompletionSystemMessageParam.builder()
                                    .content(buildSystemPrompt(language))
                                    .build(),
                            ),
                        )

                        // History: add user and assistant turns in order
                        conversationHistory
                            .filter { it.speaker == Speaker.USER || it.speaker == Speaker.AI }
                            .forEach { turn ->
                                when (turn.speaker) {
                                    Speaker.USER -> add(userMessage(turn.text))
                                    Speaker.AI -> add(assistantMessage(turn.text))
                                    else -> {} // ignore UNKNOWN or malformed entries
                                }
                            }

                        // Latest user message (the one prompting a new reply)
                        add(userMessage(userMessage))
                    }

                val request =
                    ChatCompletionCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .messages(messages)
                        .temperature(0.7)
                        .maxCompletionTokens(500L)
                        .build()

                logger.debug("Calling OpenAI API...")
                val completion: ChatCompletion = openAIClient.chat().completions().create(request)

                val message =
                    completion.choices().firstOrNull()
                        ?: error("OpenAI returned no choices")

                val response =
                    message.message().content().getOrNull()?.trim()
                        ?: error("Missing content in OpenAI response")

                logger.info("OpenAI response: '${response.take(50)}...'")
                Result.success(ConversationResponse(aiResponse = response, sessionId = null))
            } catch (e: Exception) {
                logger.error("OpenAI API failure", e)
                Result.failure(e)
            }
        }

    companion object {
        private val logger = LoggerFactory.getLogger(OpenAIConversationClient::class.java)

        private fun buildSystemPrompt(language: String): String =
            """
            You are a helpful language learning assistant for $language.
            Your role is to have natural conversations with language learners and provide helpful responses.

            Guidelines:
            - Respond in $language
            - Keep responses conversational and natural
            - Adjust complexity based on the user's proficiency level
            - Be encouraging and supportive
            - If the user makes errors, gently guide them toward correct usage
            - Ask follow-up questions to keep the conversation flowing

            Remember: You are helping someone learn $language through conversation practice.
            """.trimIndent()

        private fun userMessage(content: String) =
            ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder().content(content).build(),
            )

        private fun assistantMessage(content: String) =
            ChatCompletionMessageParam.ofAssistant(
                ChatCompletionAssistantMessageParam.builder().content(content).build(),
            )
    }
}
