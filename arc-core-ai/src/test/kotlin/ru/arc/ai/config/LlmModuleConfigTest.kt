package ru.arc.ai.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

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
    }
})
