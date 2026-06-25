package ru.arc.ai.llm

import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import org.slf4j.LoggerFactory
import ru.arc.ai.config.LlmModuleConfig
import java.util.Optional
import java.util.concurrent.CompletableFuture

class ModerationService(
    private val llm: OpenRouterLlmClient,
    private val config: LlmModuleConfig,
) {
    private val log = LoggerFactory.getLogger(ModerationService::class.java)

    fun moderate(
        text: String,
        extraSystemMessages: List<String> = emptyList(),
    ): CompletableFuture<Optional<ModerResult>> {
        if (!llm.enabled) {
            log.warn("Moderation skipped — LLM client disabled")
            return CompletableFuture.completedFuture(Optional.empty())
        }

        return CompletableFuture.supplyAsync {
            try {
                val system =
                    buildString {
                        append(config.moderationSystemPrompt)
                        extraSystemMessages.forEach { appendLine(it) }
                    }.trim()

                val params =
                    ChatCompletionCreateParams.builder()
                        .model(config.moderationModel)
                        .maxTokens(config.moderationMaxTokens.toLong())
                        .temperature(config.moderationTemperature)
                        .addSystemMessage(system)
                        .addMessage(
                            ChatCompletionUserMessageParam.builder()
                                .content(text)
                                .build(),
                        )
                        .build()

                val response = llm.client!!.chat().completions().create(params)
                val content = response.choices().first().message().content().orElse("")
                Optional.of(parseMarkers(content))
            } catch (e: Exception) {
                log.error("Moderation request failed", e)
                Optional.empty()
            }
        }
    }

    fun parseMarkers(content: String): ModerResult {
        val ok = config.moderationOkMarker
        val bad = config.moderationBadMarker
        val commentMarker = config.moderationCommentMarker

        val outcome =
            when {
                content.contains(ok) -> ModerationOutcome.OK
                content.contains(bad) -> ModerationOutcome.BAD
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
