package ru.arc.config

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class ConfigTest : FreeSpec({
    "Config" - {
        "should read int and string from yaml" {
            val dir = Files.createTempDirectory("arc-core-config")
            val yaml = dir.resolve("test.yml")
            Files.writeString(
                yaml,
                """
                server-name: proxy-test
                nested:
                  count: 42
                """.trimIndent(),
            )
            ConfigManager.clear()
            val config = ConfigManager.of(dir, "test.yml")
            config.load()

            config.string("server-name", "default") shouldBe "proxy-test"
            config.int("nested.count", 0) shouldBe 42
        }

        "should inject missing keys with defaults" {
            val dir = Files.createTempDirectory("arc-core-config-defaults")
            val yaml = dir.resolve("empty.yml")
            Files.writeString(yaml, "{}\n")

            ConfigManager.clear()
            val config = ConfigManager.of(dir, "empty.yml")

            config.boolean("feature.enabled", true) shouldBe true
            config.string("feature.name", "ProxyARC") shouldBe "ProxyARC"
            config.exists("feature.enabled") shouldBe true
            config.exists("feature.name") shouldBe true
        }

        "should preserve yaml comments after reload" {
            val dir = Files.createTempDirectory("arc-core-config-comments")
            val yaml = dir.resolve("comments.yml")
            Files.writeString(
                yaml,
                """
                # header comment
                key: value
                """.trimIndent(),
            )
            ConfigManager.clear()
            val config = ConfigManager.of(dir, "comments.yml")
            config.load()
            config.string("key", "") shouldBe "value"
            config.save()
            config.load()

            Files.readString(yaml) shouldContain "header comment"
        }

        "should load production stock yaml with emoji lore" {
            val dir = Files.createTempDirectory("arc-core-stock-prod")
            val resource =
                java.util.Objects.requireNonNull(
                    javaClass.classLoader.getResourceAsStream("stocks/stock-prod.yml"),
                ) { "stocks/stock-prod.yml fixture missing" }
            resource.use { Files.copy(it, dir.resolve("stock.yml")) }
            ConfigManager.clear()
            val config = ConfigManager.of(dir, "stock.yml")
            config.load()
            config.list<Map<String, Any>>("stocks").isNotEmpty() shouldBe true
        }
    }
})
