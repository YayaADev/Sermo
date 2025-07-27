package com.sermo.clients

import com.openai.client.OpenAIClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.sermo.models.ConversationResponse
import com.sermo.models.ConversationTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.jvm.optionals.getOrNull

class OpenAIConversationClient(
    private val openAIClient: OpenAIClient
) : ConversationAIClient {

    override suspend fun generateConversationResponse(
        userMessage: String,
        language: String,
        conversationHistory: List<ConversationTurn>
    ): Result<ConversationResponse> = withContext(Dispatchers.IO) {
        try {
            logger.info("OpenAI conversation request - Language: $language, Message: '${userMessage.take(50)}...'")

            val messages = buildList {
                // System prompt
                add(
                    ChatCompletionMessageParam.ofSystem(
                        ChatCompletionSystemMessageParam.builder()
                            .content(buildSystemPrompt(language))
                            .build()
                    )
                )

                // History (user/assistant pairs)
                conversationHistory.forEach { turn ->
                    add(userMessage(turn.userMessage))
                    add(assistantMessage(turn.assistantReply))
                }


                // Current user input
                add(
                    ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder()
                            .content(userMessage)
                            .build()
                    )
                )
            }

            val request = ChatCompletionCreateParams.builder()
                .model(ChatModel.GPT_4O_MINI)
                .messages(messages)
                .temperature(0.7)
                .maxCompletionTokens(500L)
                .build()

            logger.debug("Calling OpenAI API...")
            val completion: ChatCompletion = openAIClient.chat().completions().create(request)

            val message = completion.choices().firstOrNull()
                ?: error("OpenAI returned no choices")

            val response = message.message().content().getOrNull()?.trim()
                ?: error("Missing content in OpenAI response")

            logger.info("OpenAI response: '${response.take(50)}...'")
            Result.success(ConversationResponse(aiResponse = response, sessionId = null))

        } catch (e: Exception) {
            logger.error("OpenAI API failure", e)
            Result.failure(e)
        }
    }

    override suspend fun correctGrammar(
        text: String,
        language: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            logger.info("Grammar correction request - Language: $language, Text: '${text.take(50)}...'")

            val messages = listOf(
                ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder()
                        .content(buildGrammarCorrectionPrompt(language))
                        .build()
                ),
                ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                        .content(text)
                        .build()
                )
            )

            val request = ChatCompletionCreateParams.builder()
                .model(ChatModel.GPT_4O_MINI)
                .messages(messages)
                .temperature(0.2)
                .maxTokens(300)
                .build()

            val completion: ChatCompletion = openAIClient.chat().completions().create(request)

            val result = completion.choices().firstOrNull()?.message()?.content()?.getOrNull()?.trim()
                ?: error("Missing corrected text from OpenAI")

            Result.success(result)

        } catch (e: Exception) {
            logger.error("Grammar correction failed", e)
            Result.failure(e)
        }
    }


    companion object {
        private val logger = LoggerFactory.getLogger(OpenAIConversationClient::class.java)
        private fun buildSystemPrompt(language: String): String = """
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

        private fun buildGrammarCorrectionPrompt(language: String): String = """
        You are a $language grammar correction assistant.

        Task: Correct any grammar, spelling, or usage errors in the provided text.

        Rules:
        - Return ONLY the corrected text
        - Maintain the original meaning and tone
        - Fix grammar, spelling, and word order errors
        - Use natural, native-speaker $language
        - If the text is already correct, return it unchanged

        Do not add explanations, just return the corrected text.
    """.trimIndent()

        private fun userMessage(content: String) =
            ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder().content(content).build()
            )

        private fun assistantMessage(content: String) =
            ChatCompletionMessageParam.ofAssistant(
                ChatCompletionAssistantMessageParam.builder().content(content).build()
            )
    }
}
