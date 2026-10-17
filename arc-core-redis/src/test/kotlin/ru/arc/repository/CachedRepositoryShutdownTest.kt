package ru.arc.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CachedRepositoryShutdownTest : FunSpec({
    test("terminal persistence failure is observable while registry and owned scope still close") {
        val job = SupervisorJob()
        val storage = InMemoryStorage<TestEntity>()
        val repository = CachedRepository(
            config = RepoConfig.builder<TestEntity>("failed-terminal-drain").build(),
            storage = storage,
            scope = CoroutineScope(Dispatchers.Default + job),
        )
        repository.init().getOrThrow()
        repository.save(TestEntity("pending", "must not acknowledge a failed write")).getOrThrow()
        storage.failOnSave = true
        val outcome = runCatching { repository.shutdown() }
        outcome.isFailure shouldBe true
        repository.getStats().dirtyCount shouldBe 1
        CachedRepository.allStats().any { it.repoId == "failed-terminal-drain" } shouldBe false
        job.isCancelled shouldBe true
    }
})
