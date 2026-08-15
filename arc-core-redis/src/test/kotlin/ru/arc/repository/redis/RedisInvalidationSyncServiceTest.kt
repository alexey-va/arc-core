package ru.arc.repository.redis

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.arc.redis.InMemoryRedis
import ru.arc.repository.Entity
import ru.arc.repository.RepoResult

class RedisInvalidationSyncServiceTest : FreeSpec({
    "large updates publish only an id and reload the durable entity" {
        val redis = InMemoryRedis()
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
            )

        try {
            service.onUpdate(received::complete)
            service.start()

            runBlocking { service.broadcastUpdate(stored).getOrThrow() }

            runBlocking { withTimeout(2_000) { received.await() } } shouldBe stored
            loadCount shouldBe 1
            val wire = redis.getPublishedMessages().single().message
            wire.length shouldBeLessThan 256
            wire.contains(payload) shouldBe false
            wire.contains(stored.id()) shouldBe true
        } finally {
            service.stop()
        }
    }

    "delete invalidations do not load storage" {
        val redis = InMemoryRedis()
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
            )

        try {
            service.onDelete(deleted::complete)
            service.start()

            runBlocking { service.broadcastDelete("audit-player").getOrThrow() }

            runBlocking { withTimeout(2_000) { deleted.await() } } shouldBe "audit-player"
            loadCount shouldBe 0
        } finally {
            service.stop()
        }
    }
}) {
    private data class TestEntity(
        val key: String,
        val payload: String,
    ) : Entity {
        override fun id(): String = key
    }
}
