package ru.arc.config

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class ConfigTest : FreeSpec({
    "Config" - {
        "should read integer and string from yaml" {
            val dir = Files.createTempDirectory("proxyarc-config-test")
            val yaml = dir.resolve("test.yml")
            Files.writeString(
                yaml,
                """
                server-name: proxy-test
                nested:
                  count: 42
                """.trimIndent(),
            )

            val config = Config(dir, "test.yml")

            config.string("server-name", "default") shouldBe "proxy-test"
            config.integer("nested.count", 0) shouldBe 42
        }

        "should inject missing keys with defaults" {
            val dir = Files.createTempDirectory("proxyarc-config-defaults")
            val yaml = dir.resolve("empty.yml")
            Files.writeString(yaml, "{}\n")

            val config = Config(dir, "empty.yml")

            config.bool("feature.enabled", true) shouldBe true
            config.string("feature.name", "ProxyARC") shouldBe "ProxyARC"

            Files.readString(yaml) shouldContain "feature"
        }
    }
})
