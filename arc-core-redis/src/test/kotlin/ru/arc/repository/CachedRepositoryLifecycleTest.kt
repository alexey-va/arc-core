package ru.arc.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CachedRepositoryLifecycleTest {
    @Test
    fun `shutdown unregisters repository and cancels its scope`() =
        runTest {
            val scopeJob = SupervisorJob()
            val repo =
                CachedRepository(
                    config = config("lifecycle-shutdown"),
                    storage = InMemoryStorage<TestEntity>(),
                    scope = CoroutineScope(Dispatchers.Default + scopeJob),
                )

            repo.init().getOrThrow()
            assertTrue(CachedRepository.allStats().any { it.repoId == "lifecycle-shutdown" })

            repo.shutdown()

            assertFalse(CachedRepository.allStats().any { it.repoId == "lifecycle-shutdown" })
            assertTrue(scopeJob.isCancelled)
        }

    @Test
    fun `failed init unregisters repository and cancels its scope`() =
        runTest {
            val scopeJob = SupervisorJob()
            val storage = InMemoryStorage<TestEntity>().apply { failOnLoad = true }
            val repo =
                CachedRepository(
                    config = config("lifecycle-failed-init", loadAllOnStart = true),
                    storage = storage,
                    scope = CoroutineScope(Dispatchers.Default + scopeJob),
                )

            val result = repo.init()

            assertTrue(result.isError)
            assertFalse(CachedRepository.allStats().any { it.repoId == "lifecycle-failed-init" })
            assertTrue(scopeJob.isCancelled)
        }

    private fun config(
        id: String,
        loadAllOnStart: Boolean = false,
    ): RepoConfig<TestEntity> =
        RepoConfig
            .builder<TestEntity>(id)
            .loadAllOnStart(loadAllOnStart)
            .build()
}
