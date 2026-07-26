package ru.arc.ai.llm

import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

data class ChatTurn(val role: String, val content: String)

class SimpleChatService(
    private val llm: OpenRouterLlmClient,
    private val executor: Executor,
) {
    private val log = LoggerFactory.getLogger(SimpleChatService::class.java)

    fun complete(
        model: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        maxTokens: Int,
        temperature: Double,
    ): CompletableFuture<String?> {
        if (!llm.enabled) {
            return CompletableFuture.completedFuture(null)
        }

        return try {
            CompletableFuture.supplyAsync(
                {
                try {
                    val builder =
                        ChatCompletionCreateParams.builder()
                            .model(model)
                            .maxCompletionTokens(maxTokens.toLong())
                            .temperature(temperature)

                    if (systemPrompt.isNotBlank()) {
                        builder.addMessage(
                            ChatCompletionMessageParam.ofSystem(
                                ChatCompletionSystemMessageParam.builder()
                                    .content(systemPrompt)
                                    .build(),
                            ),
                        )
                    }

                    for (turn in history) {
                        when (turn.role) {
                            "user" ->
                                builder.addMessage(
                                    ChatCompletionMessageParam.ofUser(
                                        ChatCompletionUserMessageParam.builder()
                                            .content(turn.content)
                                            .build(),
                                    ),
                                )
                            "assistant" ->
                                builder.addMessage(
                                    ChatCompletionMessageParam.ofAssistant(
                                        ChatCompletionAssistantMessageParam.builder()
                                            .content(turn.content)
                                            .build(),
                                    ),
                                )
                            else -> builder.addSystemMessage(turn.content)
                        }
                    }

                    val client = checkNotNull(llm.client) { "LLM client is enabled but unavailable" }
                    client
                        .chat()
                        .completions()
                        .create(builder.build())
                        .choices()
                        .firstOrNull()
                        ?.message()
                        ?.content()
                        ?.orElse(null)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                } catch (e: Exception) {
                    log.error("Chat completion failed", e)
                    null
                }
                },
                executor,
            )
        } catch (e: RuntimeException) {
            log.error("Failed to schedule chat completion", e)
            CompletableFuture.completedFuture(null)
        }
    }
}
