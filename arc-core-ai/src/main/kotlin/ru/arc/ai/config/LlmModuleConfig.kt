package ru.arc.ai.config

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import java.nio.file.Path

open class LlmModuleConfig(
    private val config: Config,
    private val dataPath: Path = Path.of("."),
) {

    open val llmEnabled: Boolean
        get() = apiKey != "none"

    open val apiBaseUrl: String
        get() = config.string("openrouter.api-base-url", "https://openrouter.ai/api/v1")

    open val apiKey: String
        get() = config.string("openrouter.api-key", "none")

    open val timeoutSeconds: Long
        get() = config.integer("openrouter.timeout-seconds", 30).toLong()

    open val proxyEnabled: Boolean
        get() = config.bool("http-proxy.enabled", true)

    open val proxyHost: String
        get() = config.string("http-proxy.host", "")

    open val proxyPort: Int
        get() = config.integer("http-proxy.port", 8888)

    open val moderationModel: String
        get() = config.string("moderation.model", "openai/gpt-4o-mini")

    open val moderationMaxTokens: Int
        get() = config.integer("moderation.max-tokens", 250)

    open val moderationTemperature: Double
        get() = config.real("moderation.temperature", 0.2)

    open val moderationOkMarker: String
        get() = config.string("moderation.ok-marker", "OK")

    open val moderationBadMarker: String
        get() = config.string("moderation.bad-marker", "BAD")

    open val moderationCommentMarker: String
        get() = config.string("moderation.comment-marker", "COMMENT:")

    open val moderationSystemMessages: List<String>
        get() = config.stringList("moderation.system-messages", emptyList())

    /** Board / text moderation prompt — prefer `prompts/moderation.txt` over YAML list. */
    open val moderationSystemPrompt: String
        get() =
            PromptFiles.readText(dataPath, "prompts/moderation.txt")
                ?: moderationSystemMessages.joinToString("\n").trim()

    open val toolInvokeChannel: String
        get() = config.string("tools.invoke-channel", "arc.ai.tools.invoke")

    open val toolResultChannel: String
        get() = config.string("tools.result-channel", "arc.ai.tools.result")

    open val toolDefaultTimeoutMs: Long
        get() = config.integer("tools.default-timeout-ms", 30_000).toLong()

    open val npcChatRequestChannel: String
        get() = config.string("npc-chat.request-channel", "arc.ai.npc.request")

    open val npcChatResponseChannel: String
        get() = config.string("npc-chat.response-channel", "arc.ai.npc.response")

    open val npcChatTimeoutMs: Long
        get() = config.integer("npc-chat.timeout-ms", 18_000).toLong()

    fun validateProxy() {
        if (proxyEnabled) {
            require(proxyHost.isNotBlank()) { "http-proxy.host is required when http-proxy.enabled is true" }
            require(proxyPort in 1..65535) { "http-proxy.port must be 1..65535" }
        }
    }

    companion object {
        const val RESOURCE = "llm.yml"

        fun load(dataPath: Path): LlmModuleConfig {
            Config.copyDefaultConfig(ConfigManager.bundledModuleResource(RESOURCE), dataPath, replace = false)
            val cfg = LlmModuleConfig(ConfigManager.ofModule(dataPath, RESOURCE), dataPath)
            cfg.validateProxy()
            return cfg
        }
    }
}

class TestLlmModuleConfig(
    override val apiBaseUrl: String = "https://openrouter.ai/api/v1",
    override val apiKey: String = "test-key",
    override val timeoutSeconds: Long = 30,
    override val proxyEnabled: Boolean = true,
    override val proxyHost: String = "10.255.0.1",
    override val proxyPort: Int = 8888,
    override val moderationModel: String = "openai/gpt-4o-mini",
    override val moderationMaxTokens: Int = 250,
    override val moderationTemperature: Double = 0.2,
    override val moderationOkMarker: String = "OK",
    override val moderationBadMarker: String = "BAD",
    override val moderationCommentMarker: String = "COMMENT:",
    override val moderationSystemMessages: List<String> = emptyList(),
    override val moderationSystemPrompt: String = "test moderation prompt",
    override val toolInvokeChannel: String = "arc.ai.tools.invoke",
    override val toolResultChannel: String = "arc.ai.tools.result",
    override val toolDefaultTimeoutMs: Long = 30_000,
    override val npcChatRequestChannel: String = "arc.ai.npc.request",
    override val npcChatResponseChannel: String = "arc.ai.npc.response",
    override val npcChatTimeoutMs: Long = 18_000,
) : LlmModuleConfig(EmptyConfig)
