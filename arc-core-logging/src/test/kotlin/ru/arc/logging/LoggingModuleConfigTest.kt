package ru.arc.logging

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class LoggingModuleConfigTest : FreeSpec({

    "TestLoggingModuleConfig" - {
        "should expose defaults matching bundled logging.yml" {
            val cfg = TestLoggingModuleConfig()
            cfg.enabled shouldBe false
            cfg.host shouldBe "localhost"
            cfg.port shouldBe 3100
            cfg.level shouldBe LogLevel.INFO
            cfg.lokiLevel shouldBe LogLevel.INFO
            cfg.lokiFormat shouldBe "json"
            cfg.serviceName shouldBe LoggingModuleConfig.DEFAULT_SERVICE_NAME
            cfg.debugQuietSources shouldBe emptyList()
        }

        "should parse quiet sources" {
            TestLoggingModuleConfig(
                debugQuietSources = listOf("ru.arc.sync", "ru.arc.repository"),
            ).debugQuietSources shouldBe listOf("ru.arc.sync", "ru.arc.repository")
        }
    }
})
