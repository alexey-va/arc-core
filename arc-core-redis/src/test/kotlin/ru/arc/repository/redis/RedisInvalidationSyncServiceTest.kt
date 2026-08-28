package ru.arc.repository.redis

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.arc.repository.Entity
import ru.arc.repository.RepoResult
import java.util.concurrent.atomic.AtomicInteger

class RedisInvalidationSyncServiceTest : FreeSpec({
    "large updates skip their origin and reload the durable entity on another server" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val payload = "x".repeat(2_000_000)
        val stored = TestEntity("audit-player", payload)
        val received = CompletableDeferred<TestEntity>()
        var loadCount = 0
        val service =
            RedisInvalidationSyncService(
                redis = redis,
                channel = "audit:invalidate:v2",
                loadEntity = { id ->
                    loadCount++
                    RepoResult.success(stored.takeIf { it.id() == id })
                },
                gson = Gson(),
                localOrigin = "spawn",
            )

        try {
            service.onUpdate(received::complete)
            service.start()

            runBlocking { service.broadcastUpdate(stored).getOrThrow() }

            loadCount shouldBe 0
            received.isCompleted shouldBe false
            val wire = redis.getPublishedMessages().single().message
            wire.length shouldBeLessThan 256
            wire.contains(payload) shouldBe false
            wire.contains(stored.id()) shouldBe true

            redis.simulateExternalMessage("audit:invalidate:v2", wire, originServer = "survival")
            runBlocking { withTimeout(2_000) { received.await() } } shouldBe stored
            loadCount shouldBe 1
        } finally {
            service.stop()
        }
    }

    "bursts for one id coalesce into one durable reload" {
        val redis = InMemoryRedis()
        val stored = TestEntity("audit-player", "latest")
        val received = CompletableDeferred<TestEntity>()
        val loadCount = AtomicInteger()
        val service =
            RedisInvalidationSyncService(
                redis = redis,
                channel = "audit:invalidate:v2",
                loadEntity = {
                    loadCount.incrementAndGet()
                    RepoResult.success(stored)
                },
                invalidationCoalesceMillis = 50,
            )

        try {
            service.onUpdate(received::complete)
            service.start()

            repeat(20) {
                redis.simulateExternalMessage("audit:invalidate:v2", updateMessage(stored.id()))
            }

            runBlocking { withTimeout(2_000) { received.await() } } shouldBe stored
            loadCount.get() shouldBe 1
        } finally {
            service.stop()
        }
    }

    "an update arriving during a reload schedules one trailing reload" {
        val redis = InMemoryRedis()
        val firstLoadStarted = CompletableDeferred<Unit>()
        val releaseFirstLoad = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        val bothReceived = CompletableDeferred<Unit>()
        val loadCount = AtomicInteger()
        var stored = TestEntity("audit-player", "first")
        val service =
            RedisInvalidationSyncService(
                redis = redis,
                channel = "audit:invalidate:v2",
                loadEntity = {
                    val snapshot = stored
                    if (loadCount.incrementAndGet() == 1) {
                        firstLoadStarted.complete(Unit)
                        releaseFirstLoad.await()
                    }
                    RepoResult.success(snapshot)
                },
                invalidationCoalesceMillis = 25,
            )

        try {
            service.onUpdate { entity ->
                received += entity.payload
                if (received.size == 2) bothReceived.complete(Unit)
            }
            service.start()

            redis.simulateExternalMessage("audit:invalidate:v2", updateMessage(stored.id()))
            runBlocking { withTimeout(2_000) { firstLoadStarted.await() } }
            stored = stored.copy(payload = "second")
            redis.simulateExternalMessage("audit:invalidate:v2", updateMessage(stored.id()))
            releaseFirstLoad.complete(Unit)

            runBlocking { withTimeout(2_000) { bothReceived.await() } }
            received.shouldContainExactly("first", "second")
            loadCount.get() shouldBe 2
        } finally {
            service.stop()
        }
    }

    "delete cancels a coalesced update for the same id" {
        val redis = InMemoryRedis()
        val deleted = CompletableDeferred<String>()
        val loadCount = AtomicInteger()
        val updateCount = AtomicInteger()
        val service =
            RedisInvalidationSyncService(
                redis = redis,
                channel = "audit:invalidate:v2",
                loadEntity = {
                    loadCount.incrementAndGet()
                    RepoResult.success(TestEntity(it, "stale"))
                },
                invalidationCoalesceMillis = 100,
            )

        try {
            service.onUpdate { updateCount.incrementAndGet() }
            service.onDelete(deleted::complete)
            service.start()

            redis.simulateExternalMessage("audit:invalidate:v2", updateMessage("audit-player"))
            redis.simulateExternalMessage("audit:invalidate:v2", deleteMessage("audit-player"))

            runBlocking {
                withTimeout(2_000) { deleted.await() }
                delay(150)
            }
            loadCount.get() shouldBe 0
            updateCount.get() shouldBe 0
        } finally {
            service.stop()
        }
    }

    "delete invalidations skip their origin and do not load storage" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val deleted = CompletableDeferred<String>()
        var loadCount = 0
        val service =
            RedisInvalidationSyncService<TestEntity>(
                redis = redis,
                channel = "audit:invalidate:v2",
                loadEntity = {
                    loadCount++
                    RepoResult.success(null)
                },
                localOrigin = "spawn",
            )

        try {
            service.onDelete(deleted::complete)
            service.start()

            runBlocking { service.broadcastDelete("audit-player").getOrThrow() }
            deleted.isCompleted shouldBe false

            val wire = redis.getPublishedMessages().single().message
            redis.simulateExternalMessage("audit:invalidate:v2", wire, originServer = "survival")
            runBlocking { withTimeout(2_000) { deleted.await() } } shouldBe "audit-player"
            loadCount shouldBe 0
        } finally {
            service.stop()
        }
    }
}) {
    companion object {
        private fun updateMessage(id: String): String = """{"type":"UPDATE","id":"$id"}"""

        private fun deleteMessage(id: String): String = """{"type":"DELETE","id":"$id"}"""
    }

    private data class TestEntity(
        val key: String,
        val payload: String,
    ) : Entity {
        override fun id(): String = key
    }
}
