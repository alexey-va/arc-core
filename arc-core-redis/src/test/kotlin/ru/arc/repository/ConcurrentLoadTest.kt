package ru.arc.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrentLoadTest {
    private lateinit var storage: InMemoryStorage<TestEntity>
    private lateinit var repo: CachedRepository<TestEntity>

    @BeforeEach
    fun setup() {
        storage = InMemoryStorage()
        val config =
            RepoConfig
                .builder<TestEntity>("test-concurrent")
                .storageKey("test_data")
                .updateChannel("test_updates")
                .saveInterval(100.milliseconds)
                .build()

        repo =
            CachedRepository(
                config = config,
                storage = storage,
            )
    }

    @AfterEach
    fun teardown() =
        runTest {
            repo.shutdown()
        }

    @Test
    fun `concurrent loads of same entity only load once`() =
        runTest {
            repo.init()

            // Simulate slow storage
            storage.loadDelay = 100.milliseconds.inWholeMilliseconds
            storage.put(TestEntity("id1", "from_storage"))

            // Start 10 concurrent loads
            val results =
                (1..10).map { i ->
                    async {
                        repo.get("id1")
                    }
                }

            // Wait for all
            val allResults = results.awaitAll()

            // All should succeed
            allResults.forEach { result ->
                assertTrue(result.isSuccess)
                assertEquals("from_storage", result.getOrNull()?.value)
            }

            // Storage should only be called once (not 10 times)
            assertEquals(1, storage.loadCount)
        }

    @Test
    fun `concurrent loads wait for first load to complete`() =
        runTest {
            repo.init()

            // Simulate slow storage
            storage.loadDelay = 50.milliseconds.inWholeMilliseconds
            storage.put(TestEntity("id1", "loaded_value"))

            val startTime = System.currentTimeMillis()

            // Start 5 concurrent loads
            val results =
                (1..5).map {
                    async {
                        repo.get("id1")
                    }
                }

            // Wait for all
            results.forEach { it.await() }

            val duration = System.currentTimeMillis() - startTime

            // Should take ~50ms (one load), not ~250ms (5 loads)
            assertTrue(duration < 100, "Took $duration ms, expected < 100ms")

            // All should get the same value
            results.forEach { result ->
                assertEquals("loaded_value", result.await().getOrNull()?.value)
            }
        }

    @Test
    fun `failed load propagates to all waiters`() =
        runTest {
            repo.init()

            storage.failOnLoad = true

            // Start 5 concurrent loads
            val results =
                (1..5).map {
                    async {
                        repo.get("id1")
                    }
                }

            // All should fail
            results.forEach { result ->
                val repoResult = result.await()
                assertTrue(repoResult.isError)
            }
        }
}
