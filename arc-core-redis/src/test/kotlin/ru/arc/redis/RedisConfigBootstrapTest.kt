package ru.arc.redis

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class RedisConfigBootstrapTest : FreeSpec({

    "RedisConfigBootstrap" - {
        "should seed modules redis config only from an explicit current settings reader" {
            ConfigManager.clear()
            val dir = Files.createTempDirectory("arc-redis-bootstrap")
            try {
                RedisConfigBootstrap.ensure(dir) {
                    RedisConnectionSettingsSnapshot(
                        host = "current-host",
                        port = 12345,
                        username = "u",
                        password = "p",
                        serverName = "spawn",
                        mainServer = true,
                        enabled = false,
                    )
                }

                val cfg = RedisModuleConfig.load(dir)
                cfg.host shouldBe "current-host"
                cfg.port shouldBe 12345
                cfg.username shouldBe "u"
                cfg.password shouldBe "p"
                cfg.serverName shouldBe "spawn"
                cfg.mainServer shouldBe true
                cfg.enabled shouldBe false
            } finally {
                ConfigManager.clear()
            }
        }

        "should not inspect retired plugin config layouts implicitly" {
            ConfigManager.clear()
            val dir = Files.createTempDirectory("arc-redis-no-implicit-reader")
            try {
                Files.writeString(dir.resolve("misc.yml"), "redis:\n  host: retired-host\n")

                RedisConfigBootstrap.ensure(dir)

                RedisModuleConfig.load(dir).host shouldBe RedisModuleConfig.DEFAULT_HOST
            } finally {
                ConfigManager.clear()
            }
        }
    }
})
