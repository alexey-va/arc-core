package ru.arc.testing.containers

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.RedisConnection
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.arc.redis.network.RedisPresenceDirectory
import ru.arc.redis.network.RedisRequestReplyChannel
import ru.arc.redis.network.RedisRequestResult
import ru.arc.redis.network.RedisRequestTimeoutHandle
import ru.arc.redis.network.RedisRequestTimeoutScheduler
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RedisNetworkLayerIntegrationTest : FreeSpec({
    data class Message(
        val id: String,
        val replyTo: String?,
        val origin: String,
        val destination: String,
        val body: String,
    )
    data class Node(val server: String, val origin: String, val observedAt: Long)

    val messageCodec = BoundedJsonCodec(
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
        },
    )
    val nodeCodec = BoundedJsonCodec(
        gson = Gson(),
        type = Node::class.java,
        rootContract = JsonObjectContract(setOf("server", "origin", "observedAt")),
        bounds = JsonResourceBounds(512, maxStringCharacters = 64),
        validate = { node ->
            require(node.server.matches(Regex("[a-z0-9_-]{1,32}")))
            require(node.origin.matches(Regex("[a-z0-9_-]{1,32}")))
            require(node.observedAt >= 0)
        },
    )

    "request reply and presence cross a real Redis transport" {
        RedisTestService.start().use { redisService ->
            val connection = RedisConnection(redisService.endpoint.host, redisService.endpoint.port)
            RedisManager(connection, ServerIdentity { "spawn" }).use { spawnRedis ->
                RedisManager(connection, ServerIdentity { "survival" }).use { survivalRedis ->
                    ScheduledRedisTimeouts().use { timeouts ->
                        lateinit var survivalChannel: RedisRequestReplyChannel<Message>
                        survivalChannel = RedisRequestReplyChannel(
                            redis = survivalRedis,
                            channel = "arc:test:network-layer",
                            codec = messageCodec,
                            originAllowed = { it in setOf("spawn", "survival") },
                            requestId = Message::id,
                            replyTo = Message::replyTo,
                            replyAllowed = { _, _, _ -> false },
                            timeoutMillis = 5_000,
                            maxPending = 8,
                            timeoutScheduler = timeouts,
                            embeddedOrigin = Message::origin,
                            onMessage = { request, origin ->
                                if (origin == "spawn" && request.destination == "survival") {
                                    survivalChannel.publish(
                                        Message("reply:one", request.id, "survival", "spawn", "accepted"),
                                    )
                                }
                            },
                        )
                        val spawnChannel = RedisRequestReplyChannel(
                            redis = spawnRedis,
                            channel = "arc:test:network-layer",
                            codec = messageCodec,
                            originAllowed = { it in setOf("spawn", "survival") },
                            requestId = Message::id,
                            replyTo = Message::replyTo,
                            replyAllowed = { request, response, origin ->
                                origin == request.destination && response.destination == request.origin
                            },
                            timeoutMillis = 5_000,
                            maxPending = 8,
                            timeoutScheduler = timeouts,
                            embeddedOrigin = Message::origin,
                            onMessage = { _, _ -> Unit },
                        )
                        spawnRedis.init()
                        survivalRedis.init()
                        awaitSubscriptions(spawnRedis, survivalRedis)

                        val result = spawnChannel.request(
                            Message("request:one", null, "spawn", "survival", "start"),
                        ).get(10, TimeUnit.SECONDS)
                        result shouldBe RedisRequestResult.Reply(
                            Message("reply:one", "request:one", "survival", "spawn", "accepted"),
                            "survival",
                        )

                        val now = System.currentTimeMillis()
                        val presence = RedisPresenceDirectory(
                            redis = spawnRedis,
                            hashKey = "arc:test:network-presence",
                            codec = nodeCodec,
                            entryId = Node::server,
                            origin = Node::origin,
                            observedAtMillis = Node::observedAt,
                            originAllowed = { it in setOf("spawn", "survival") },
                            leaseMillis = 10_000,
                            maxEntries = 8,
                        )
                        val node = Node("spawn", "spawn", now)
                        presence.publish(node).get(5, TimeUnit.SECONDS)
                        presence.refresh().get(5, TimeUnit.SECONDS).values shouldBe listOf(node)

                        presence.close()
                        spawnChannel.close()
                        survivalChannel.close()
                    }
                }
            }
        }
    }
})

private class ScheduledRedisTimeouts : RedisRequestTimeoutScheduler, AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor()

    override fun schedule(delayMillis: Long, action: () -> Unit): RedisRequestTimeoutHandle {
        val future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        return RedisRequestTimeoutHandle { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

private fun awaitSubscriptions(vararg managers: RedisManager) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (managers.any { !it.isSubscriptionActive() } && System.nanoTime() < deadline) {
        Thread.sleep(25)
    }
    check(managers.all(RedisManager::isSubscriptionActive)) { "Redis subscriptions did not become active" }
}
