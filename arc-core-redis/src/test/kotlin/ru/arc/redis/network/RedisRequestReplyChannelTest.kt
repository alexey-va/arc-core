package ru.arc.redis.network

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations
import ru.arc.redis.ServerIdentity
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds

class RedisRequestReplyChannelTest : FreeSpec({
    data class Message(
        val id: String,
        val replyTo: String?,
        val origin: String,
        val destination: String,
        val body: String,
    )

    fun codec() = BoundedJsonCodec(
        gson = Gson(),
        type = Message::class.java,
        rootContract = JsonObjectContract(
            allowedFields = setOf("id", "replyTo", "origin", "destination", "body"),
            requiredFields = setOf("id", "origin", "destination", "body"),
        ),
        bounds = JsonResourceBounds(1_024, maxStringCharacters = 160),
        validate = { message ->
            require(message.id.matches(Regex("[a-z0-9:._-]{1,160}")))
            require(message.replyTo == null || message.replyTo.matches(Regex("[a-z0-9:._-]{1,160}")))
            require(message.origin.matches(Regex("[a-z0-9_-]{1,32}")))
            require(message.destination.matches(Regex("[a-z0-9_-]{1,32}")))
            require(message.body.length <= 160)
        },
    )

    fun request(id: String = "request:one") = Message(id, null, "spawn", "survival", "start")
    fun reply(id: String = "reply:one", replyTo: String = "request:one", origin: String = "survival") =
        Message(id, replyTo, origin, "spawn", "accepted")

    "correlates a synchronous reply and cancels its timeout" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val scheduler = ManualTimeoutScheduler()
        lateinit var channel: RedisRequestReplyChannel<Message>
        channel = RedisRequestReplyChannel(
            redis = redis,
            channel = "arc:test:request-reply",
            codec = codec(),
            originAllowed = { it in setOf("spawn", "survival") },
            requestId = Message::id,
            replyTo = Message::replyTo,
            replyAllowed = { sent, response, origin ->
                origin == sent.destination && response.destination == sent.origin
            },
            timeoutMillis = 500,
            maxPending = 4,
            timeoutScheduler = scheduler,
            embeddedOrigin = Message::origin,
            onMessage = { message, _ ->
                if (message.body == "start") {
                    redis.simulateExternalMessage(
                        "arc:test:request-reply",
                        codec().encode(reply(replyTo = message.id)),
                        "survival",
                    )
                }
            },
        )

        channel.request(request()).get() shouldBe RedisRequestResult.Reply(reply(), "survival")
        channel.pendingCount() shouldBe 0
        scheduler.cancelledCount() shouldBe 1
        channel.close()
    }

    "keeps a pending request after a spoofed reply and later times it out" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val scheduler = ManualTimeoutScheduler()
        val rejected = mutableListOf<RedisReplyRejection>()
        val channel = RedisRequestReplyChannel(
            redis = redis,
            channel = "arc:test:request-reply",
            codec = codec(),
            originAllowed = { true },
            requestId = Message::id,
            replyTo = Message::replyTo,
            replyAllowed = { sent, response, origin ->
                origin == sent.destination && response.destination == sent.origin
            },
            timeoutMillis = 500,
            maxPending = 4,
            timeoutScheduler = scheduler,
            onMessage = { _, _ -> Unit },
            onReplyRejected = rejected::add,
        )
        val result = channel.request(request())
        redis.simulateExternalMessage("arc:test:request-reply", codec().encode(reply(origin = "attacker")), "attacker")
        channel.pendingCount() shouldBe 1
        scheduler.fireAll()

        result.get() shouldBe RedisRequestResult.TimedOut
        rejected.shouldContainExactly(RedisReplyRejection.REPLY_POLICY_REJECTED)
        channel.close()
    }

    "bounds pending requests, rejects duplicate ids and completes them on close" {
        val redis = InMemoryRedis()
        val scheduler = ManualTimeoutScheduler()
        val channel = RedisRequestReplyChannel(
            redis = redis,
            channel = "arc:test:request-reply",
            codec = codec(),
            originAllowed = { true },
            requestId = Message::id,
            replyTo = Message::replyTo,
            replyAllowed = { _, _, _ -> true },
            timeoutMillis = 500,
            maxPending = 1,
            timeoutScheduler = scheduler,
            onMessage = { _, _ -> Unit },
        )
        val first = channel.request(request())
        channel.request(request()).get() shouldBe RedisRequestResult.DuplicateRequestId
        channel.request(request("request:two")).get() shouldBe RedisRequestResult.CapacityExceeded
        channel.close()
        first.get() shouldBe RedisRequestResult.Closed
        channel.request(request("request:three")).get() shouldBe RedisRequestResult.Closed
    }

    "reports timeout scheduling and publish infrastructure failures" {
        val schedulingFailure = RedisRequestReplyChannel(
            redis = InMemoryRedis(),
            channel = "arc:test:request-reply",
            codec = codec(),
            originAllowed = { true },
            requestId = Message::id,
            replyTo = Message::replyTo,
            replyAllowed = { _, _, _ -> true },
            timeoutMillis = 500,
            maxPending = 1,
            timeoutScheduler = RedisRequestTimeoutScheduler { _, _ -> error("scheduler down") },
            onMessage = { _, _ -> Unit },
        )
        val scheduleResult = schedulingFailure.request(request()).get()
            .shouldBeInstanceOf<RedisRequestResult.InfrastructureFailure>()
        scheduleResult.phase shouldBe RedisRequestFailurePhase.SCHEDULE_TIMEOUT
        schedulingFailure.close()

        val publishFailure = RedisRequestReplyChannel(
            redis = ThrowingPublishRedis(),
            channel = "arc:test:request-reply",
            codec = codec(),
            originAllowed = { true },
            requestId = Message::id,
            replyTo = Message::replyTo,
            replyAllowed = { _, _, _ -> true },
            timeoutMillis = 500,
            maxPending = 1,
            timeoutScheduler = ManualTimeoutScheduler(),
            onMessage = { _, _ -> Unit },
        )
        val publishResult = publishFailure.request(request()).get()
            .shouldBeInstanceOf<RedisRequestResult.InfrastructureFailure>()
        publishResult.phase shouldBe RedisRequestFailurePhase.PUBLISH
        publishFailure.close()
    }
})

private class ManualTimeoutScheduler : RedisRequestTimeoutScheduler {
    private data class Entry(val action: () -> Unit, var cancelled: Boolean = false)
    private val entries = mutableListOf<Entry>()

    override fun schedule(delayMillis: Long, action: () -> Unit): RedisRequestTimeoutHandle {
        val entry = Entry(action)
        entries += entry
        return RedisRequestTimeoutHandle { entry.cancelled = true }
    }

    fun fireAll() = entries.filterNot(Entry::cancelled).forEach { it.action() }
    fun cancelledCount(): Int = entries.count(Entry::cancelled)
}

private class ThrowingPublishRedis(
    delegate: InMemoryRedis = InMemoryRedis(),
) : RedisOperations by delegate {
    override fun publish(channel: String, message: String) {
        throw IllegalStateException("redis down")
    }
}
