package ru.arc.redis

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class RedisModuleConfigTest : FreeSpec({

    "TestRedisModuleConfig" - {
        "should expose defaults matching bundled redis.yml" {
            val cfg = TestRedisModuleConfig()
            cfg.enabled shouldBe true
            cfg.host shouldBe RedisModuleConfig.DEFAULT_HOST
            cfg.port shouldBe RedisModuleConfig.DEFAULT_PORT
            cfg.username shouldBe RedisModuleConfig.DEFAULT_USERNAME
            cfg.password shouldBe RedisModuleConfig.DEFAULT_PASSWORD
            cfg.serverName shouldBe RedisModuleConfig.DEFAULT_SERVER_NAME
            cfg.mainServer shouldBe false
        }

        "should build connection from accessors" {
            TestRedisModuleConfig(
                host = "redis.example",
                port = 6380,
                username = "user",
                password = "secret",
            ).connection() shouldBe RedisConnection("redis.example", 6380, "user", "secret")
        }
    }
})
