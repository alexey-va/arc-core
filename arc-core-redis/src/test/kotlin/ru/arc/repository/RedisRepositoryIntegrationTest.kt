package ru.arc.repository

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.arc.xaction.XCondition
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests: [redisRepo] + [InMemoryRedis] hash storage + pub/sub sync.
 */
class RedisRepositoryIntegrationTest : FreeSpec({

    fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun shutdown(repo: CachedRepository<*>) {
        repo.shutdown()
    }

    "redisRepo persists entity to redis hash" {
        val redis = InMemoryRedis()
        val gson = Gson()
        val scope = testScope()
        val repo = redisRepo<TestEntity>(
            redis = redis,
            gson = gson,
            id = "int-persist",
            storageKey = "arc.int.persist",
            updateChannel = "arc.int.persist.sync",
            scope = scope,
        ) {
            enableCleanup(false)
            saveInterval(3600.seconds)
        }

        runTest {
            val entity = TestEntity("player-1", "coins", counter = 5)
            repo.save(entity)
            repo.saveDirty()

            val stored = redis.getHash("arc.int.persist")["player-1"].shouldNotBeNull()
            gson.fromJson(stored, TestEntity::class.java).value shouldBe "coins"

            shutdown(repo)
        }
    }

    "redisRepo loads cold entity from redis hash" {
        val redis = InMemoryRedis()
        val gson = Gson()
        val scope = testScope()
        val entity = TestEntity("cold-1", "loaded")
        redis.saveMapEntries("arc.int.cold", "cold-1", gson.toJson(entity)).get()

        val repo = redisRepo<TestEntity>(
            redis = redis,
            gson = gson,
            id = "int-cold",
            storageKey = "arc.int.cold",
            updateChannel = "arc.int.cold.sync",
            scope = scope,
        ) {
            enableCleanup(false)
            saveInterval(3600.seconds)
        }

        runTest {
            val loaded = repo.get("cold-1").getOrNull().shouldNotBeNull()
            loaded.value shouldBe "loaded"
            shutdown(repo)
        }
    }

    "two repos on same redis receive cross-instance sync updates" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val gson = Gson()
        val scope1 = testScope()
        val scope2 = testScope()

        val repo1 = redisRepo<TestEntity>(
            redis = redis,
            gson = gson,
            id = "int-sync-1",
            storageKey = "arc.int.sync",
            updateChannel = "arc.int.sync.updates",
            scope = scope1,
        ) {
            enableCleanup(false)
            saveInterval(50.seconds)
        }

        val repo2 = redisRepo<TestEntity>(
            redis = redis,
            gson = gson,
            id = "int-sync-2",
            storageKey = "arc.int.sync",
            updateChannel = "arc.int.sync.updates",
            scope = scope2,
        ) {
            enableCleanup(false)
            saveInterval(3600.seconds)
        }

        runTest {
            val entity = TestEntity("sync-id", "v1")
            repo1.save(entity)
            repo1.saveDirty()

            repo2.addContext("sync-id")
            repo2.get("sync-id")

            val updated = TestEntity("sync-id", "v2", counter = 2)
            repo1.save(updated)
            repo1.saveDirty()

            val synced = withContext(Dispatchers.Default) {
                var candidate = repo2.getNow("sync-id")
                withTimeout(5.seconds) {
                    while (candidate == null || candidate.value != "v2" || candidate.counter != 2) {
                        delay(10)
                        candidate = repo2.getNow("sync-id")
                    }
                }
                requireNotNull(candidate)
            }

            synced.value shouldBe "v2"
            synced.counter shouldBe 2

            shutdown(repo1)
            shutdown(repo2)
        }
    }

    "XCondition roundtrips through gson for wire payloads" {
        val gson = Gson()
        val original = XCondition.ofPermission("arc.board.receive")
            .copy(
                playerName = "Steve",
                serverName = "spawn",
                placeholders = mapOf("%vault_eco_balance%" to "100"),
            )
        val restored = gson.fromJson(gson.toJson(original), XCondition::class.java)
        restored shouldBe original
    }
})
