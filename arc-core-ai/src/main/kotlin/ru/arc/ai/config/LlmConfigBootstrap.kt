package ru.arc.ai.config

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * Copies api-key / proxy settings from legacy `gpt.yml` and ProxyARC `assistant.yml` into `modules/llm.yml`.
 */
object LlmConfigBootstrap {

    fun mergeLegacyPaper(dataPath: Path) {
        val llmPath = dataPath.resolve("modules/llm.yml")
        if (!Files.exists(llmPath)) {
            Files.createDirectories(llmPath.parent)
            Files.copy(
                LlmConfigBootstrap::class.java.getResourceAsStream("/modules/llm.yml")!!,
                llmPath,
            )
        }
        val llm = ConfigManager.of(dataPath, "modules/llm.yml")
        val gptPath = dataPath.resolve("gpt.yml")
        if (!Files.exists(gptPath)) return

        val gpt = ConfigManager.of(dataPath, "gpt.yml")
        var changed = false

        if (llm.string("openrouter.api-key", "none") == "none") {
            val key = gpt.string("api-key", "none")
            if (key != "none") {
                llm.setString("openrouter.api-key", key)
                changed = true
            }
        }

        val moderatorSystem = gpt.stringList("ai.moderator.system", emptyList())
        if (moderatorSystem.isNotEmpty() && llm.stringList("moderation.system-messages", emptyList()).isEmpty()) {
            llm.setStringList("moderation.system-messages", moderatorSystem)
            changed = true
        }

        if (changed) {
            llm.save()
        }
    }

    fun mergeLegacyProxy(dataPath: Path) {
        val llmPath = dataPath.resolve("modules/llm.yml")
        if (!Files.exists(llmPath)) {
            Files.createDirectories(llmPath.parent)
            Files.copy(
                LlmConfigBootstrap::class.java.getResourceAsStream("/modules/llm.yml")!!,
                llmPath,
            )
        }
        val llm = ConfigManager.of(dataPath, "modules/llm.yml")
        val assistantPath = dataPath.resolve("modules/assistant.yml")
        if (!Files.exists(assistantPath)) return

        val assistant = ConfigManager.of(dataPath, "modules/assistant.yml")
        var changed = false

        if (llm.string("openrouter.api-key", "none") == "none") {
            val key = assistant.string("api-key", "none")
            if (key != "none") {
                llm.setString("openrouter.api-key", key)
                changed = true
            }
        }

        val assistantBase = assistant.string("api-base-url", "")
        if (assistantBase.isNotBlank()) {
            llm.setString("openrouter.api-base-url", assistantBase)
            changed = true
        }

        if (assistant.string("proxy.host", "").isNotBlank()) {
            llm.setString("http-proxy.host", assistant.string("proxy.host", "127.0.0.1"))
            llm.setInt("http-proxy.port", assistant.integer("proxy.port", 8888))
            llm.setBoolean("http-proxy.enabled", assistant.bool("proxy.enabled", true))
            changed = true
        }

        if (changed) {
            llm.save()
        }
    }
}
