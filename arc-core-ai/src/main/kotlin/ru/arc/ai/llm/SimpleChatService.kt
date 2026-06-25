package ru.arc.ai.llm

import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import org.slf4j.LoggerFactory
import ru.arc.ai.config.LlmModuleConfig
import java.util.Optional
import java.util.concurrent.CompletableFuture

data class ChatTurn(val role: String, val content: String)

class SimpleChatService(
    private val llm: OpenRouterLlmClient,
    private val config: LlmModuleConfig,
) {
    private val log = LoggerFactory.getLogger(SimpleChatService::class.java)

    fun complete(
        model: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        maxTokens: Int,
        temperature: Double,
    ): CompletableFuture<Optional<String>> {
        if (!llm.enabled) {
            return CompletableFuture.completedFuture(Optional.empty())
        }

        return CompletableFuture.supplyAsync {
            try {
                val builder =
                    ChatCompletionCreateParams.builder()
                        .model(model)
                        .maxTokens(maxTokens.toLong())
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

                val response = llm.client!!.chat().completions().create(builder.build())
                Optional.of(response.choices().first().message().content().orElse(""))
            } catch (e: Exception) {
                log.error("Chat completion failed", e)
                Optional.empty()
            }
        }
    }
}
