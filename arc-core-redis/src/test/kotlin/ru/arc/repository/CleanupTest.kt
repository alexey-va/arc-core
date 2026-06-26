
package ru.arc.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class CleanupTest {

    private lateinit var storage: InMemoryStorage<TestEntity>
    private lateinit var config: RepoConfig<TestEntity>
    private lateinit var repo: CachedRepository<TestEntity>

    @BeforeEach
    fun setup() {
        storage = InMemoryStorage()
        config = RepoConfig.builder<TestEntity>("test-cleanup")
            .storageKey("test_data")
            .updateChannel("test_updates")
            .saveInterval(100.milliseconds)
            .enableCleanup(true)
            .cleanupInterval(50.milliseconds)  // Fast cleanup for tests
            .entityTimeout(100.milliseconds)  // Entities expire after 100ms
            .build()

        repo = CachedRepository(
            config = config,
            storage = storage
        )
    }

    @AfterEach
    fun teardown() = runTest {
        repo.shutdown()
    }

    @Test
    fun `context entities are never cleaned up`() = runTest {
        repo.init()

        val entity1 = TestEntity("id1", "value1")
        val entity2 = TestEntity("id2", "value2")

        repo.save(entity1)
        repo.save(entity2)

        // Add id1 to context
        repo.addContext("id1")

        // Set old access time for id2 (simulate expired)
        val oldTime = System.currentTimeMillis() - 200 // 200ms ago
        repo.setLastAccessTime("id2", oldTime)

        // Manually trigger cleanup
        repo.cleanupNow()

        // id1 should still be in cache (in context)
        assertNotNull(repo.get("id1").getOrNull())

        // id2 should be removed (not in context, expired)
        assertNull(repo.get("id2").getOrNull())
    }

    @Test
    fun `recently accessed entities are not cleaned up`() = runTest {
        repo.init()

        val entity = TestEntity("id1", "value1")
        repo.save(entity)

        // Access it every 50ms (before timeout)
        delay(50.milliseconds)
        repo.get("id1")  // Access resets timer

        delay(50.milliseconds)
        repo.get("id1")  // Access resets timer again

        delay(50.milliseconds)
        // Should still be in cache (recently accessed)
        assertNotNull(repo.get("id1").getOrNull())
    }

    @Test
    fun `cleanup removes expired non-context entities`() = runTest {
        repo.init()

        val entity1 = TestEntity("id1", "value1")
        val entity2 = TestEntity("id2", "value2")
        val entity3 = TestEntity("id3", "value3")

        repo.save(entity1)
        repo.save(entity2)
        repo.save(entity3)

        // Add id1 to context
        repo.addContext("id1")

        // Set old access times for id2 and id3 (simulate expired)
        val oldTime = System.currentTimeMillis() - 200 // 200ms ago
        repo.setLastAccessTime("id2", oldTime)
        repo.setLastAccessTime("id3", oldTime)

        // Manually trigger cleanup
        repo.cleanupNow()

        // Only id1 should remain (in context)
        assertEquals(1, repo.all().getOrNull()?.size)
        assertNotNull(repo.get("id1").getOrNull())
        assertNull(repo.get("id2").getOrNull())
        assertNull(repo.get("id3").getOrNull())
    }

    @Test
    fun `cleanup can be disabled`() = runTest {
        val noCleanupConfig = config.copy(enableCleanup = false)
        val noCleanupRepo = CachedRepository(
            config = noCleanupConfig,
            storage = storage
        )

        noCleanupRepo.init()

        val entity = TestEntity("id1", "value1")
        noCleanupRepo.save(entity)

        // Wait past timeout
        delay(150.milliseconds)

        // Entity should still be in cache (cleanup disabled)
        assertNotNull(noCleanupRepo.get("id1").getOrNull())

        noCleanupRepo.shutdown()
    }

    @Test
    fun `save updates access time`() = runTest {
        repo.init()

        val entity = TestEntity("id1", "value1")
        repo.save(entity)

        delay(50.milliseconds)

        // Save again (updates access time)
        repo.save(entity)

        delay(50.milliseconds)

        // Should still be in cache (recently saved)
        assertNotNull(repo.get("id1").getOrNull())
    }

    @Test
    fun `get updates access time`() = runTest {
        repo.init()

        val entity = TestEntity("id1", "value1")
        repo.save(entity)

        delay(50.milliseconds)

        // Get it (updates access time)
        repo.get("id1")

        delay(50.milliseconds)

        // Should still be in cache (recently accessed)
        assertNotNull(repo.get("id1").getOrNull())
    }

    @Test
    fun `delete removes from access tracking`() = runTest {
        repo.init()

        val entity = TestEntity("id1", "value1")
        repo.save(entity)

        repo.delete("id1")

        // Access tracking should be cleared
        delay(50.milliseconds)

        // Should not be in cache
        assertNull(repo.get("id1").getOrNull())
    }

    @Test
    fun `cleanup notifies observers`() = runTest {
        repo.init()

        val entity = TestEntity("id1", "value1")
        repo.save(entity)

        val updates = mutableListOf<TestEntity?>()
        val job = this.launch {
            repo.observe("id1").take(2).toList(updates)
        }

        delay(10.milliseconds)  // Let observer start

        // Set old access time (simulate expired)
        val oldTime = System.currentTimeMillis() - 200 // 200ms ago
        repo.setLastAccessTime("id1", oldTime)

        // Manually trigger cleanup
        repo.cleanupNow()

        delay(10.milliseconds)  // Give observer time to process

        job.cancel()

        // Should have received null when cleaned up
        assertTrue(updates.any { it == null }, "Expected null in updates, got: $updates")
    }
}

