package ru.arc.redis

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger

class InMemoryRedisTest : FreeSpec({

    "hash operations" - {
        "should save and load map entries" {
            val redis = InMemoryRedis()
            redis.saveMapEntries("test:hash", "a", "1", "b", "2").get()
            redis.getHash("test:hash") shouldBe mapOf("a" to "1", "b" to "2")
        }

        "should delete entry when value is null" {
            val redis = InMemoryRedis()
            redis.setHash("test:hash", mapOf("a" to "1", "b" to "2"))
            redis.saveMapEntries("test:hash", "a", null).get()
            redis.getHash("test:hash") shouldBe mapOf("b" to "2")
        }
    }

    "pub/sub" - {
        "should deliver published message to listener" {
            val redis = InMemoryRedis(ServerIdentity { "spawn" })
            val received = AtomicInteger(0)
            redis.registerChannelUnique("ch") { _, msg, origin ->
                msg shouldBe "hello"
                origin shouldBe "spawn"
                received.incrementAndGet()
            }
            redis.publish("ch", "hello")
            received.get() shouldBe 1
            redis.getPublishedMessages() shouldHaveSize 1
        }

        "should simulate external server message" {
            val redis = InMemoryRedis()
            var origin = ""
            redis.registerChannelUnique("ch") { _, _, server -> origin = server }
            redis.simulateExternalMessage("ch", "payload", "survival")
            origin shouldBe "survival"
        }
    }

    "clear" {
        val redis = InMemoryRedis()
        redis.publish("ch", "x")
        redis.clear()
        redis.getPublishedMessages() shouldHaveSize 0
    }
})
