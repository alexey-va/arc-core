package ru.arc.repository.redis

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.redis.InMemoryRedis
import ru.arc.repository.Entity

class RedisSyncServiceLifecycleTest {
    @Test
    fun `stop cancels scope and start creates a fresh scope`() {
        val jobs = mutableListOf<Job>()
        val service =
            RedisSyncService<TestEntity>(
                redis = InMemoryRedis(),
                channel = "test:sync",
                entityType = TestEntity::class.java,
                gson = Gson(),
                scopeFactory = {
                    val job = SupervisorJob()
                    jobs += job
                    CoroutineScope(Dispatchers.Default + job)
                },
            )

        service.start()
        assertEquals(1, jobs.size)
        assertTrue(jobs.single().isActive)

        service.stop()
        assertFalse(jobs.single().isActive)

        service.start()
        assertEquals(2, jobs.size)
        assertTrue(jobs.last().isActive)

        service.stop()
        assertFalse(jobs.last().isActive)
    }

    private data class TestEntity(val key: String = "id") : Entity {
        override fun id(): String = key
    }
}
