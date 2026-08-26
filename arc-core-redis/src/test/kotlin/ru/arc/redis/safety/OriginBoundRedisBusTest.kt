package ru.arc.redis.safety

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity

class OriginBoundRedisBusTest : FreeSpec({
    data class Message(
        val protocolVersion: Int,
        val messageId: String,
        val origin: String,
        val text: String,
    )

    fun codec() = BoundedJsonCodec(
        gson = Gson(),
        type = Message::class.java,
        rootContract = JsonObjectContract(setOf("protocolVersion", "messageId", "origin", "text")),
        bounds = JsonResourceBounds(512, maxStringCharacters = 160),
        validate = { message ->
            require(message.protocolVersion == 1)
            require(message.messageId.matches(Regex("[a-z0-9:._-]{1,160}")))
            require(message.origin.matches(Regex("[a-z0-9_-]{1,32}")))
            require(message.text.length <= 160)
        },
    )

    "accepts validated origins, publishes locally and unregisters exactly once" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val received = mutableListOf<Pair<Message, String>>()
        val bus = OriginBoundRedisBus(
            redis = redis,
            channel = "arc:test:events",
            codec = codec(),
            originAllowed = { it in setOf("spawn", "survival") },
            embeddedOrigin = Message::origin,
            onMessage = { message, origin -> received += message to origin },
        )
        bus.register()
        bus.publish(Message(1, "event:one", "spawn", "hello"))
        received.map { it.second }.shouldContainExactly("spawn")
        redis.listenerCount("arc:test:events") shouldBe 1
        bus.close()
        bus.close()
        redis.listenerCount("arc:test:events") shouldBe 0
    }

    "rejects transport origin before parsing and rejects embedded spoofing" {
        val redis = InMemoryRedis()
        val rejected = mutableListOf<RedisMessageRejection>()
        val bus = OriginBoundRedisBus(
            redis = redis,
            channel = "arc:test:events",
            codec = codec(),
            originAllowed = { it == "survival" },
            embeddedOrigin = Message::origin,
            onMessage = { _, _ -> error("must not deliver") },
            onRejected = rejected::add,
        )
        bus.register()
        redis.simulateExternalMessage("arc:test:events", "not-json", "attacker")
        redis.simulateExternalMessage(
            "arc:test:events",
            codec().encode(Message(1, "event:one", "spawn", "spoof")),
            "survival",
        )
        rejected.shouldContainExactly(
            RedisMessageRejection.ORIGIN_REJECTED,
            RedisMessageRejection.EMBEDDED_ORIGIN_MISMATCH,
        )
    }

    "drops malformed, unknown-field and oversized messages without exposing raw payload" {
        val redis = InMemoryRedis()
        val rejected = mutableListOf<RedisMessageRejection>()
        val bus = OriginBoundRedisBus(
            redis = redis,
            channel = "arc:test:events",
            codec = codec(),
            originAllowed = { true },
            onMessage = { _, _ -> error("must not deliver") },
            onRejected = rejected::add,
        )
        bus.register()
        redis.simulateExternalMessage("arc:test:events", "{bad", "spawn")
        redis.simulateExternalMessage(
            "arc:test:events",
            """{"protocolVersion":1,"messageId":"event:one","origin":"spawn","text":"x","command":"op"}""",
            "spawn",
        )
        redis.simulateExternalMessage("arc:test:events", "x".repeat(513), "spawn")
        rejected.shouldContainExactly(
            RedisMessageRejection.MALFORMED_PAYLOAD,
            RedisMessageRejection.MALFORMED_PAYLOAD,
            RedisMessageRejection.MALFORMED_PAYLOAD,
        )
    }

    "deduplicates replay, expires it and fails closed when the guard is full" {
        val redis = InMemoryRedis()
        val received = mutableListOf<String>()
        val rejected = mutableListOf<RedisMessageRejection>()
        var now = 100L
        val bus = OriginBoundRedisBus(
            redis = redis,
            channel = "arc:test:events",
            codec = codec(),
            originAllowed = { true },
            messageId = Message::messageId,
            deduplicator = RecentMessageDeduplicator(ttlMillis = 10, maxEntries = 1),
            clockMillis = { now },
            onMessage = { message, _ -> received += message.messageId },
            onRejected = rejected::add,
        )
        bus.register()
        val one = codec().encode(Message(1, "event:one", "spawn", "one"))
        val two = codec().encode(Message(1, "event:two", "spawn", "two"))
        redis.simulateExternalMessage("arc:test:events", one, "spawn")
        redis.simulateExternalMessage("arc:test:events", one, "spawn")
        redis.simulateExternalMessage("arc:test:events", two, "spawn")
        now = 110
        redis.simulateExternalMessage("arc:test:events", two, "spawn")
        received.shouldContainExactly("event:one", "event:two")
        rejected.shouldContainExactly(RedisMessageRejection.DUPLICATE, RedisMessageRejection.REPLAY_GUARD_FULL)
    }

    "isolates handler failures and continues delivery" {
        val redis = InMemoryRedis()
        val failures = mutableListOf<String>()
        val received = mutableListOf<String>()
        val bus = OriginBoundRedisBus(
            redis = redis,
            channel = "arc:test:events",
            codec = codec(),
            originAllowed = { true },
            onMessage = { message, _ ->
                if (message.messageId == "event:bad") error("handler failed")
                received += message.messageId
            },
            onHandlerFailure = { failures += requireNotNull(it.message) },
        )
        bus.register()
        redis.simulateExternalMessage("arc:test:events", codec().encode(Message(1, "event:bad", "spawn", "x")), "spawn")
        redis.simulateExternalMessage("arc:test:events", codec().encode(Message(1, "event:good", "spawn", "x")), "spawn")
        failures.shouldContainExactly("handler failed")
        received.shouldContainExactly("event:good")
        shouldThrow<IllegalStateException> { bus.register() }
    }
})
