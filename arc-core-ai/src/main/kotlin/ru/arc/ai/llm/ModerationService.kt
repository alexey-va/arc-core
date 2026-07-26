package ru.arc.ai.llm

import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import org.slf4j.LoggerFactory
import ru.arc.ai.config.LlmModuleConfig
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class ModerationService(
    private val llm: OpenRouterLlmClient,
    private val config: LlmModuleConfig,
    private val executor: Executor,
) {
    private val log = LoggerFactory.getLogger(ModerationService::class.java)

    fun moderate(
        text: String,
        extraSystemMessages: List<String> = emptyList(),
    ): CompletableFuture<ModerResult?> {
        if (!llm.enabled) {
            log.warn("Moderation skipped — LLM client disabled")
            return CompletableFuture.completedFuture(null)
        }

        return try {
            CompletableFuture.supplyAsync(
                {
                try {
                    val system =
                        buildString {
                            append(config.moderationSystemPrompt)
                            extraSystemMessages.forEach { appendLine(it) }
                        }.trim()

                    val params =
                        ChatCompletionCreateParams.builder()
                            .model(config.moderationModel)
                            .maxCompletionTokens(config.moderationMaxTokens.toLong())
                            .temperature(config.moderationTemperature)
                            .addSystemMessage(system)
                            .addMessage(
                                ChatCompletionUserMessageParam.builder()
                                    .content(text)
                                    .build(),
                            )
                            .build()

                    val client = checkNotNull(llm.client) { "LLM client is enabled but unavailable" }
                    val response = client.chat().completions().create(params)
                    val content = response.choices().firstOrNull()?.message()?.content()?.orElse("") ?: ""
                    parseMarkers(content)
                } catch (e: Exception) {
                    log.error("Moderation request failed", e)
                    null
                }
                },
                executor,
            )
        } catch (e: RuntimeException) {
            log.error("Failed to schedule moderation request", e)
            CompletableFuture.completedFuture(null)
        }
    }

    fun parseMarkers(content: String): ModerResult {
        val ok = config.moderationOkMarker
        val bad = config.moderationBadMarker
        val commentMarker = config.moderationCommentMarker

        val outcome =
            when {
                content.contains(ok) && !content.contains(bad) -> ModerationOutcome.OK
                content.contains(bad) && !content.contains(ok) -> ModerationOutcome.BAD
                else -> ModerationOutcome.UNKNOWN
            }

        val comment =
            if (content.contains(commentMarker)) {
                content.substringAfter(commentMarker).trim()
            } else {
                ""
            }

        return ModerResult(outcome, comment)
    }
}
