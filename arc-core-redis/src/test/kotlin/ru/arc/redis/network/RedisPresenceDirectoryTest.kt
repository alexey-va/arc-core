package ru.arc.redis.network

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds

class RedisPresenceDirectoryTest : FreeSpec({
    data class Node(val server: String, val origin: String, val observedAt: Long, val sequence: Long)

    fun codec() = BoundedJsonCodec(
        gson = Gson(),
        type = Node::class.java,
        rootContract = JsonObjectContract(setOf("server", "origin", "observedAt", "sequence")),
        bounds = JsonResourceBounds(512, maxStringCharacters = 64),
        validate = { node ->
            require(node.server.matches(Regex("[a-z0-9_-]{1,32}")))
            require(node.origin.matches(Regex("[a-z0-9_-]{1,32}")))
            require(node.observedAt >= 0)
            require(node.sequence >= 0)
        },
    )

    fun directory(redis: InMemoryRedis, now: () -> Long, maxEntries: Int = 8) = RedisPresenceDirectory(
        redis = redis,
        hashKey = "arc:test:nodes",
        codec = codec(),
        entryId = Node::server,
        origin = Node::origin,
        observedAtMillis = Node::observedAt,
        sequence = Node::sequence,
        originAllowed = { it in setOf("spawn", "survival") },
        leaseMillis = 1_000,
        maxEntries = maxEntries,
        clockMillis = now,
    )

    "publishes and refreshes strict active leases" {
        var now = 1_000L
        val redis = InMemoryRedis()
        val presence = directory(redis, { now })
        val node = Node("survival", "survival", 1_000, 1)

        presence.publish(node).get()
        redis.getHash("arc:test:nodes").keys shouldContainExactly setOf("survival")
        presence.refresh().get() shouldBe RedisPresenceRefresh(listOf(node), emptyMap())
        presence.activeLeaseCount() shouldBe 1

        now = 2_000
        presence.snapshot() shouldBe emptyList()
        presence.close()
    }

    "rejects malformed, mismatched, disallowed and overflow entries without raw payloads" {
        val redis = InMemoryRedis()
        val valid = Node("spawn", "spawn", 1_000, 1)
        redis.setHash(
            "arc:test:nodes",
            mapOf(
                "bad field" to codec().encode(valid),
                "broken" to "{bad",
                "mismatch" to codec().encode(valid),
                "rogue" to codec().encode(Node("rogue", "rogue", 1_000, 1)),
                "spawn" to codec().encode(valid),
            ),
        )
        val presence = directory(redis, { 1_000 }, maxEntries = 4)
        val result = presence.refresh().get()

        result.values shouldBe emptyList()
        result.rejected shouldContainExactly mapOf(
            RedisPresenceRejection.CAPACITY to 1,
            RedisPresenceRejection.UNSAFE_FIELD to 1,
            RedisPresenceRejection.MALFORMED_PAYLOAD to 1,
            RedisPresenceRejection.ENTRY_ID_MISMATCH to 1,
            RedisPresenceRejection.ORIGIN_REJECTED to 1,
        )
        presence.close()
    }

    "clears old leases when TTL policy changes" {
        val redis = InMemoryRedis()
        val node = Node("spawn", "spawn", 1_000, 1)
        redis.setHash("arc:test:nodes", mapOf("spawn" to codec().encode(node)))
        val presence = directory(redis, { 1_000 })
        presence.refresh().get()
        presence.activeLeaseCount() shouldBe 1
        presence.updateLeaseMillis(2_000)
        presence.activeLeaseCount() shouldBe 0
        presence.close()
    }

    "rejects a decoded entry that fails the caller policy before caching" {
        val redis = InMemoryRedis()
        val node = Node("spawn", "spawn", 2_000, 1)
        redis.setHash("arc:test:nodes", mapOf("spawn" to codec().encode(node)))
        val presence = RedisPresenceDirectory(
            redis = redis,
            hashKey = "arc:test:nodes",
            codec = codec(),
            entryId = Node::server,
            origin = Node::origin,
            observedAtMillis = Node::observedAt,
            originAllowed = { true },
            entryAllowed = { it.observedAt <= 1_100 },
            leaseMillis = 1_000,
            maxEntries = 8,
            clockMillis = { 1_000 },
        )

        presence.refresh().get().rejected shouldBe mapOf(RedisPresenceRejection.ENTRY_POLICY_REJECTED to 1)
        presence.activeLeaseCount() shouldBe 0
        presence.close()
    }
})
