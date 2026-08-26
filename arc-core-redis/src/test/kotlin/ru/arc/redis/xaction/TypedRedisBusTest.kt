package ru.arc.redis.xaction

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import java.util.concurrent.CopyOnWriteArrayList

class TypedRedisBusTest : FreeSpec({

    data class TestPayload(val text: String, val n: Int = 0)

    "delivers published message to registered listener" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val received = CopyOnWriteArrayList<Pair<TestPayload, String>>()
        val bus = TypedRedisBus(
            redis = redis,
            channel = "arc.test.bus",
            gson = Gson(),
            messageType = TestPayload::class.java,
            onMessage = { payload, origin -> received.add(payload to origin) },
        )
        bus.register()
        bus.publish(TestPayload("hello", 1))

        received shouldHaveSize 1
        received[0].first.text shouldBe "hello"
        received[0].first.n shouldBe 1
        received[0].second shouldBe "spawn"
        redis.getPublishedMessages() shouldHaveSize 1
    }

    "receives message simulated from another server" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        var origin = ""
        var payload: TestPayload? = null
        val bus = TypedRedisBus(
            redis = redis,
            channel = "arc.test.bus",
            gson = Gson(),
            messageType = TestPayload::class.java,
            onMessage = { msg, server ->
                payload = msg
                origin = server
            },
        )
        bus.register()
        redis.simulateExternalMessage(
            channel = "arc.test.bus",
            message = Gson().toJson(TestPayload("from-survival")),
            originServer = "survival",
        )

        payload?.text shouldBe "from-survival"
        origin shouldBe "survival"
    }

    "ignores messages on other channels" {
        val redis = InMemoryRedis()
        var count = 0
        val bus = TypedRedisBus(
            redis = redis,
            channel = "arc.test.bus",
            gson = Gson(),
            messageType = TestPayload::class.java,
            onMessage = { _, _ -> count++ },
        )
        bus.register()
        redis.publish("other-channel", Gson().toJson(TestPayload("nope")))
        count shouldBe 0
    }

    "rejects oversized outgoing compatibility messages" {
        val redis = InMemoryRedis()
        val bus = TypedRedisBus(
            redis = redis,
            channel = "arc.test.bus",
            gson = Gson(),
            messageType = TestPayload::class.java,
            onMessage = { _, _ -> },
        )
        shouldThrow<IllegalArgumentException> { bus.publish(TestPayload("x".repeat(1_048_577))) }
        redis.getPublishedMessages() shouldHaveSize 0
    }
})
