package ru.arc.ai.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class LlmModuleConfigTest : FreeSpec({
    "LlmModuleConfig" - {
        "should pass validation when proxy disabled" {
            val config = TestLlmModuleConfig(proxyEnabled = false)
            config.validateProxy()
        }

        "should require host when proxy enabled" {
            val config = TestLlmModuleConfig(proxyEnabled = true, proxyHost = "")
            shouldThrow<IllegalArgumentException> {
                config.validateProxy()
            }
        }

        "should treat api-key none as disabled" {
            TestLlmModuleConfig(apiKey = "none").llmEnabled shouldBe false
        }

        "should preserve the credential while applying a separate network route" {
            val dataRoot = Files.createTempDirectory("llm-network-overlay")
            try {
                val modules = Files.createDirectories(dataRoot.resolve("modules"))
                Files.writeString(
                    modules.resolve("llm.yml"),
                    """
                    openrouter:
                      api-base-url: https://openrouter.ai/api/v1
                      api-key: live-only-test-key
                    http-proxy:
                      enabled: true
                      host: 185.242.106.81
                      port: 8888
                    """.trimIndent(),
                )
                Files.writeString(
                    modules.resolve("llm-network.yml"),
                    """
                    http-proxy:
                      enabled: true
                      host: 172.29.172.1
                      port: 8888
                    """.trimIndent(),
                )
                ConfigManager.clear()

                val config = LlmModuleConfig.load(dataRoot, "llm-network.yml")

                config.apiKey shouldBe "live-only-test-key"
                config.proxyHost shouldBe "172.29.172.1"
                config.proxyPort shouldBe 8888
                config.llmEnabled shouldBe true
            } finally {
                ConfigManager.clear()
                dataRoot.toFile().deleteRecursively()
            }
        }
    }
})
