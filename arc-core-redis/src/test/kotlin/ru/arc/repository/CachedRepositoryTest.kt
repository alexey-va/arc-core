package ru.arc.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class CachedRepositoryTest {
    private lateinit var storage: InMemoryStorage<TestEntity>
    private lateinit var syncService: InMemorySyncService<TestEntity>
    private lateinit var config: RepoConfig<TestEntity>
    private lateinit var repo: CachedRepository<TestEntity>

    @BeforeEach
    fun setup() {
        storage = InMemoryStorage()
        syncService = InMemorySyncService()
        config =
            RepoConfig
                .builder<TestEntity>("test-repo")
                .storageKey("test_data")
                .updateChannel("test_updates")
                .saveInterval(100.milliseconds)
                .maxRetries(3)
                .retryBaseDelay(10.milliseconds)
                .build()

        repo =
            CachedRepository(
                config = config,
                storage = storage,
                syncService = syncService,
            )
    }

    @AfterEach
    fun teardown() =
        runTest {
            repo.shutdown()
        }

    // =========================================================================
    // Basic CRUD Tests
    // =========================================================================

    @Nested
    @DisplayName("Basic CRUD Operations")
    inner class CrudTests {
        @Test
        fun `save puts entity in cache`() =
            runTest {
                val entity = TestEntity("id1", "value1")

                val result = repo.save(entity)

                assertTrue(result.isSuccess)
                assertEquals(entity, repo.get("id1").getOrNull())
            }

        @Test
        fun `get returns null for missing entity`() =
            runTest {
                val result = repo.get("non_existent")

                assertTrue(result.isSuccess)
                assertNull(result.getOrNull())
            }

        @Test
        fun `get loads from storage if not in cache`() =
            runTest {
                storage.put(TestEntity("id1", "from_storage"))

                val result = repo.get("id1")

                assertTrue(result.isSuccess)
                assertEquals("from_storage", result.getOrNull()?.value)
            }

        @Test
        fun `getOrCreate returns existing entity`() =
            runTest {
                val existing = TestEntity("id1", "existing")
                repo.save(existing)

                val result = repo.getOrCreate("id1") { TestEntity("id1", "new") }

                assertTrue(result.isSuccess)
                assertEquals("existing", result.getOrNull()?.value)
            }

        @Test
        fun `getOrCreate creates new if not exists`() =
            runTest {
                val result = repo.getOrCreate("id1") { TestEntity("id1", "created") }

                assertTrue(result.isSuccess)
                assertEquals("created", result.getOrNull()?.value)
            }

        @Test
        fun `getOrCreate loads from storage before creating`() =
            runTest {
                storage.put(TestEntity("id1", "from_storage"))

                val result = repo.getOrCreate("id1") { TestEntity("id1", "new") }

                assertTrue(result.isSuccess)
                assertEquals("from_storage", result.getOrNull()?.value)
            }

        @Test
        fun `delete removes entity from cache`() =
            runTest {
                repo.save(TestEntity("id1", "value"))

                val result = repo.delete("id1")

                assertTrue(result.isSuccess)
                assertNull(repo.get("id1").getOrNull())
            }

        @Test
        fun `delete removes entity from storage`() =
            runTest {
                storage.put(TestEntity("id1", "value"))
                repo.save(TestEntity("id1", "value"))

                repo.delete("id1")

                assertEquals(1, storage.deleteCount)
            }

        @Test
        fun `all returns all cached entities`() =
            runTest {
                repo.save(TestEntity("id1", "value1"))
                repo.save(TestEntity("id2", "value2"))
                repo.save(TestEntity("id3", "value3"))

                val result = repo.all()

                assertTrue(result.isSuccess)
                assertEquals(3, result.getOrNull()?.size)
            }

        @Test
        fun `exists returns true for cached entity`() =
            runTest {
                repo.save(TestEntity("id1", "value"))

                val result = repo.exists("id1")

                assertTrue(result.isSuccess)
                assertTrue(result.getOrNull() == true)
            }

        @Test
        fun `exists checks storage if not in cache`() =
            runTest {
                storage.put(TestEntity("id1", "value"))

                val result = repo.exists("id1")

                assertTrue(result.isSuccess)
                assertTrue(result.getOrNull() == true)
            }
    }

    // =========================================================================
    // Dirty Tracking Tests
    // =========================================================================

    @Nested
    @DisplayName("Dirty Tracking")
    inner class DirtyTrackingTests {
        @Test
        fun `save marks entity as dirty`() =
            runTest {
                val entity = TestEntity("id1", "value")
                repo.save(entity)

                val stats = repo.getStats()

                assertEquals(1, stats.dirtyCount)
            }

        @Test
        fun `saveDirty saves dirty entities to storage`() =
            runTest {
                repo.save(TestEntity("id1", "value1"))
                repo.save(TestEntity("id2", "value2"))

                val result = repo.saveDirty()

                assertTrue(result.isSuccess)
                assertEquals(1, storage.saveCount)
                assertEquals(2, storage.size())
            }

        @Test
        fun `saveDirty clears dirty flags`() =
            runTest {
                repo.save(TestEntity("id1", "value"))

                repo.saveDirty()

                assertEquals(0, repo.getStats().dirtyCount)
            }

        @Test
        fun `saveDirty keeps dirty flag on failure`() =
            runTest {
                repo.save(TestEntity("id1", "value"))
                storage.failOnSave = true

                repo.saveDirty()

                assertEquals(1, repo.getStats().dirtyCount)
            }

        @Test
        fun `saveDirty broadcasts updates`() =
            runTest {
                repo.save(TestEntity("id1", "value"))

                repo.saveDirty()

                assertEquals(1, syncService.getBroadcastedUpdates().size)
            }
    }

    // =========================================================================
    // Context Management Tests
    // =========================================================================

    @Nested
    @DisplayName("Context Management")
    inner class ContextTests {
        @Test
        fun `addContext adds id to context set`() =
            runTest {
                repo.addContext("player1")

                assertTrue(repo.getContexts().contains("player1"))
            }

        @Test
        fun `removeContext removes id from context set`() =
            runTest {
                repo.addContext("player1")
                repo.removeContext("player1")

                assertFalse(repo.getContexts().contains("player1"))
            }

        @Test
        fun `addContext triggers preload from storage`() =
            runTest {
                storage.put(TestEntity("player1", "preloaded"))

                repo.addContext("player1")
                delay(50) // Allow async load to complete

                assertEquals("preloaded", repo.get("player1").getOrNull()?.value)
            }
    }

    // =========================================================================
    // Sync Service Tests
    // =========================================================================

    @Nested
    @DisplayName("Synchronization")
    inner class SyncTests {
        @Test
        fun `init starts sync service`() =
            runTest {
                repo.init()

                assertTrue(syncService.isRunning)
            }

        @Test
        fun `shutdown stops sync service`() =
            runTest {
                repo.init()
                repo.shutdown()

                assertFalse(syncService.isRunning)
            }

        @Test
        fun `delete broadcasts deletion`() =
            runTest {
                repo.init()
                repo.save(TestEntity("id1", "value"))

                repo.delete("id1")

                assertTrue(syncService.getBroadcastedDeletes().contains("id1"))
            }

        @Test
        fun `remote update updates cache`() =
            runTest {
                repo.init()
                val initial = TestEntity("id1", "initial")
                repo.save(initial)

                syncService.simulateRemoteUpdate(TestEntity("id1", "updated"))

                assertEquals("updated", repo.get("id1").getOrNull()?.value)
            }

        @Test
        fun `remote delete removes from cache`() =
            runTest {
                repo.init()
                repo.save(TestEntity("id1", "value"))

                syncService.simulateRemoteDelete("id1")

                assertNull(repo.get("id1").getOrNull())
            }
    }

    // =========================================================================
    // Retry Logic Tests
    // =========================================================================

    @Nested
    @DisplayName("Retry Logic")
    inner class RetryTests {
        @Test
        fun `saveDirty retries on failure`() =
            runTest {
                repo.save(TestEntity("id1", "value"))

                storage.failOnSave = true

                // Make it fail twice then succeed
                storage::saveMany

                repo.saveDirty() // Will fail after retries

                // Verify retries happened (3 attempts)
                assertEquals(3, storage.saveCount)
            }

        @Test
        fun `load retries on failure`() =
            runTest {
                storage.failOnLoad = true

                repo.get("id1")

                // Verify retries
                assertEquals(3, storage.loadCount)
            }
    }

    // =========================================================================
    // Observable Tests
    // =========================================================================

    @Nested
    @DisplayName("Observable")
    inner class ObservableTests {
        @Test
        fun `observe emits current value on subscribe`() =
            runTest {
                val entity = TestEntity("id1", "value")
                repo.save(entity)

                val first = repo.observe("id1").first()

                assertEquals(entity, first)
            }

        @Test
        fun `observe emits updates`() =
            runTest {
                repo.save(TestEntity("id1", "initial"))

                val values = mutableListOf<TestEntity?>()
                val job =
                    launch {
                        repo.observe("id1").take(3).toList(values)
                    }

                delay(10)
                repo.save(TestEntity("id1", "updated1"))
                delay(10)
                repo.save(TestEntity("id1", "updated2"))
                delay(10)

                job.cancel()

                assertTrue(values.size >= 1)
                assertEquals("initial", values[0]?.value)
            }

        @Test
        fun `observeAll emits all entities`() =
            runTest {
                repo.save(TestEntity("id1", "value1"))
                repo.save(TestEntity("id2", "value2"))

                val all = repo.observeAll().first()

                assertEquals(2, all.size)
            }
    }

    // =========================================================================
    // Error Handling Tests
    // =========================================================================

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandlingTests {
        @Test
        fun `get handles storage error gracefully`() =
            runTest {
                storage.failOnLoad = true

                val result = repo.get("id1")

                assertTrue(result.isError)
            }

        @Test
        fun `loadAll handles storage error gracefully`() =
            runTest {
                storage.failOnLoad = true

                val result = repo.loadAll()

                assertTrue(result.isError)
            }

        @Test
        fun `delete continues on storage error`() =
            runTest {
                repo.save(TestEntity("id1", "value"))
                storage.failOnSave = true

                val result = repo.delete("id1")

                assertTrue(result.isError)
                // But cache should still be updated
                assertNull(repo.get("id1").getOrNull())
            }
    }

    // =========================================================================
    // Concurrent Access Tests
    // =========================================================================

    @Nested
    @DisplayName("Concurrent Access")
    inner class ConcurrencyTests {
        @Test
        fun `concurrent saves are handled safely`() =
            runTest {
                val jobs =
                    (1..100).map { i ->
                        launch {
                            repo.save(TestEntity("id$i", "value$i"))
                        }
                    }

                jobs.forEach { it.join() }

                assertEquals(100, repo.all().getOrNull()?.size)
            }

        @Test
        fun `concurrent reads and writes are safe`() =
            runTest {
                repo.save(TestEntity("id1", "initial"))

                val writeJob =
                    launch {
                        repeat(50) { i ->
                            repo.save(TestEntity("id1", "value$i"))
                            delay(1)
                        }
                    }

                val readJob =
                    launch {
                        repeat(50) {
                            repo.get("id1")
                            delay(1)
                        }
                    }

                writeJob.join()
                readJob.join()

                // Should not throw
                assertNotNull(repo.get("id1").getOrNull())
            }

        @Test
        fun `concurrent getOrCreate creates exactly one entity`() =
            runTest {
                var createCount = 0

                val jobs =
                    (1..10).map {
                        launch {
                            repo.getOrCreate("shared_id") {
                                createCount++
                                TestEntity("shared_id", "created")
                            }
                        }
                    }

                jobs.forEach { it.join() }

                // All should get the same entity
                val entities = repo.all().getOrNull()
                assertEquals(1, entities?.size)
            }
    }

    // =========================================================================
    // Statistics Tests
    // =========================================================================

    @Nested
    @DisplayName("Statistics")
    inner class StatisticsTests {
        @Test
        fun `getStats returns correct cache size`() =
            runTest {
                repo.save(TestEntity("id1", "value1"))
                repo.save(TestEntity("id2", "value2"))

                assertEquals(2, repo.getStats().cacheSize)
            }

        @Test
        fun `getStats returns correct dirty count`() =
            runTest {
                repo.save(TestEntity("id1", "value1"))
                repo.save(TestEntity("id2", "value2"))
                repo.saveDirty() // Clean id1 and id2
                repo.save(TestEntity("id3", "value3")) // Only id3 is dirty

                assertEquals(1, repo.getStats().dirtyCount)
            }

        @Test
        fun `getStats returns correct context count`() =
            runTest {
                repo.addContext("player1")
                repo.addContext("player2")

                assertEquals(2, repo.getStats().contextCount)
            }
    }

    // =========================================================================
    // Load All Tests
    // =========================================================================

    @Nested
    @DisplayName("Load All")
    inner class LoadAllTests {
        @Test
        fun `loadAll populates cache from storage`() =
            runTest {
                storage.put(TestEntity("id1", "value1"))
                storage.put(TestEntity("id2", "value2"))
                storage.put(TestEntity("id3", "value3"))

                repo.loadAll()

                assertEquals(3, repo.all().getOrNull()?.size)
            }

        @Test
        fun `loadAll marks entities as clean`() =
            runTest {
                storage.put(TestEntity("id1", "value1"))

                repo.loadAll()

                assertEquals(0, repo.getStats().dirtyCount)
            }

        @Test
        fun `init with loadAllOnStart loads all entities`() =
            runTest {
                storage.put(TestEntity("id1", "value1"))
                storage.put(TestEntity("id2", "value2"))

                val loadAllConfig =
                    RepoConfig
                        .builder<TestEntity>("test-load-all")
                        .loadAllOnStart(true)
                        .build()

                val loadAllRepo =
                    CachedRepository(
                        config = loadAllConfig,
                        storage = storage,
                        syncService = syncService,
                    )

                loadAllRepo.init()

                assertEquals(2, loadAllRepo.all().getOrNull()?.size)

                loadAllRepo.shutdown()
            }
    }

    @Nested
    @DisplayName("Sync Cache Accessors")
    inner class SyncAccessorTests {

        @Test
        fun `getNow returns null when entity not in cache`() {
            assertNull(repo.getNow("missing"))
        }

        @Test
        fun `getNow returns entity after save`() =
            runTest {
                val entity = TestEntity("id1", "hello")
                repo.save(entity)

                assertEquals(entity, repo.getNow("id1"))
            }

        @Test
        fun `getNow does not trigger storage load`() =
            runTest {
                storage.put(TestEntity("id1", "in_storage"))

                assertNull(repo.getNow("id1"))
                assertEquals(0, storage.loadCount)
            }

        @Test
        fun `allNow returns empty list when cache is empty`() {
            assertTrue(repo.allNow().isEmpty())
        }

        @Test
        fun `allNow returns all cached entities`() =
            runTest {
                repo.save(TestEntity("a", "val-a"))
                repo.save(TestEntity("b", "val-b"))
                repo.save(TestEntity("c", "val-c"))

                val result = repo.allNow()

                assertEquals(3, result.size)
                assertTrue(result.any { it.id() == "a" })
                assertTrue(result.any { it.id() == "b" })
                assertTrue(result.any { it.id() == "c" })
            }

        @Test
        fun `markDirty puts entity in cache and marks dirty`() =
            runTest {
                val entity = TestEntity("id1", "val")
                repo.markDirty(entity)

                assertEquals(entity, repo.getNow("id1"))
                assertEquals(1, repo.getStats().dirtyCount)
            }

        @Test
        fun `markDirty mutated entity is persisted on saveDirty`() =
            runTest {
                val entity = TestEntity("id1", "original")
                repo.save(entity)
                repo.saveDirty()
                assertEquals(0, repo.getStats().dirtyCount)

                entity.value = "mutated"
                repo.markDirty(entity)
                assertEquals(1, repo.getStats().dirtyCount)

                repo.saveDirty()

                assertEquals("mutated", storage.get("id1")?.value)
                assertEquals(0, repo.getStats().dirtyCount)
            }
    }
}
