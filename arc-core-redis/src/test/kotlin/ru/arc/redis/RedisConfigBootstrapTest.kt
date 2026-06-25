package ru.arc.redis

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class RedisConfigBootstrapTest : FreeSpec({

    "RedisConfigBootstrap" - {
        "should migrate legacy misc.yml redis section into modules/redis.yml" {
            ConfigManager.clear()
            val dir = Files.createTempDirectory("arc-redis-bootstrap")
            try {
                Files.writeString(
                    dir.resolve("misc.yml"),
                    """
                    redis:
                      ip: legacy-host
                      port: 12345
                      username: u
                      password: p
                      server-name: spawn
                      main-server: true
                      enabled: false
                    """.trimIndent(),
                )

                RedisConfigBootstrap.ensure(dir)

                val cfg = RedisModuleConfig.load(dir)
                cfg.host shouldBe "legacy-host"
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
    }
})
