package ru.arc.redis.network

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.RedisMessageRejection

class ValidatedRedisTopicTest : FreeSpec({
    data class Message(val id: String, val origin: String, val value: String)

    fun codec() = BoundedJsonCodec(
        gson = Gson(),
        type = Message::class.java,
        rootContract = JsonObjectContract(setOf("id", "origin", "value")),
        bounds = JsonResourceBounds(512, maxStringCharacters = 160),
        validate = { message ->
            require(message.id.matches(Regex("[a-z0-9:._-]{1,160}")))
            require(message.origin.matches(Regex("[a-z0-9_-]{1,32}")))
            require(message.value.length <= 160)
        },
    )

    "opens registered, applies replay policy and closes idempotently" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val received = mutableListOf<String>()
        val rejected = mutableListOf<RedisMessageRejection>()
        val topic = ValidatedRedisTopic.open(
            redis = redis,
            channel = "arc:test:validated-topic",
            codec = codec(),
            originAllowed = { it in setOf("spawn", "survival") },
            embeddedOrigin = Message::origin,
            replay = RedisReplayPolicy(Message::id, ttlMillis = 1_000, maxEntries = 8),
            onMessage = { message, _ -> received += message.id },
            onRejected = rejected::add,
        )

        redis.listenerCount("arc:test:validated-topic") shouldBe 1
        val message = Message("message:one", "spawn", "hello")
        topic.publish(message)
        redis.simulateExternalMessage("arc:test:validated-topic", codec().encode(message), "spawn")
        redis.simulateExternalMessage("arc:test:validated-topic", codec().encode(message.copy(id = "message:two")), "attacker")

        received.shouldContainExactly("message:one")
        rejected.shouldContainExactly(RedisMessageRejection.DUPLICATE, RedisMessageRejection.ORIGIN_REJECTED)
        topic.close()
        topic.close()
        redis.listenerCount("arc:test:validated-topic") shouldBe 0
        shouldThrow<IllegalStateException> { topic.publish(message.copy(id = "message:closed")) }
    }
})
