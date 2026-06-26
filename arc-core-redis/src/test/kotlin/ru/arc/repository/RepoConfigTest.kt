
package ru.arc.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RepoConfigTest {

    @Test
    fun `builder creates config with default values`() {
        val config = RepoConfig.builder<TestEntity>("test-repo").build()

        assertEquals("test-repo", config.id)
        assertEquals("test-repo", config.storageKey)
        assertEquals("test-repo_updates", config.updateChannel)
        assertEquals(5.seconds, config.saveInterval)
        assertNull(config.entityFactory)
        assertFalse(config.loadAllOnStart)
        assertEquals(3, config.maxRetries)
        assertEquals(100.milliseconds, config.retryBaseDelay)
        assertFalse(config.enableBackups)
        assertNull(config.backupFolder)
        assertEquals(10.seconds, config.backupInterval)
    }

    @Test
    fun `builder sets storageKey`() {
        val config = RepoConfig.builder<TestEntity>("test-repo")
            .storageKey("custom_key")
            .build()

        assertEquals("custom_key", config.storageKey)
    }

    @Test
    fun `builder sets updateChannel`() {
        val config = RepoConfig.builder<TestEntity>("test-repo")
            .updateChannel("custom_channel")
            .build()

        assertEquals("custom_channel", config.updateChannel)
    }

    @Test
    fun `builder sets saveInterval`() {
        val config = RepoConfig.builder<TestEntity>("test-repo")
            .saveInterval(30.seconds)
            .build()

        assertEquals(30.seconds, config.saveInterval)
    }

    @Test
    fun `builder sets entityFactory`() {
        val factory: (String) -> TestEntity = { TestEntity(it, "default") }
        val config = RepoConfig.builder<TestEntity>("test-repo")
            .entityFactory(factory)
            .build()

        assertNotNull(config.entityFactory)
        assertEquals("default", config.entityFactory!!("id1").value)
    }

    @Test
    fun `builder sets loadAllOnStart`() {
        val config = RepoConfig.builder<TestEntity>("test-repo")
            .loadAllOnStart(true)
            .build()

        assertTrue(config.loadAllOnStart)
    }

    @Test
    fun `builder sets maxRetries`() {
        val config = RepoConfig.builder<TestEntity>("test-repo")
            .maxRetries(5)
            .build()

        assertEquals(5, config.maxRetries)
    }

    @Test
    fun `builder sets retryBaseDelay`() {
        val config = RepoConfig.builder<TestEntity>("test-repo")
            .retryBaseDelay(500.milliseconds)
            .build()

        assertEquals(500.milliseconds, config.retryBaseDelay)
    }

    @Test
    fun `builder sets backup options`() {
        val config = RepoConfig.builder<TestEntity>("test-repo")
            .enableBackups(true)
            .backupFolder("/backups")
            .backupInterval(1.seconds)
            .build()

        assertTrue(config.enableBackups)
        assertEquals("/backups", config.backupFolder)
        assertEquals(1.seconds, config.backupInterval)
    }

    @Test
    fun `builder supports chaining`() {
        val config = RepoConfig.builder<TestEntity>("test-repo")
            .storageKey("key")
            .updateChannel("channel")
            .saveInterval(10.seconds)
            .maxRetries(5)
            .enableBackups(true)
            .build()

        assertEquals("key", config.storageKey)
        assertEquals("channel", config.updateChannel)
        assertEquals(10.seconds, config.saveInterval)
        assertEquals(5, config.maxRetries)
        assertTrue(config.enableBackups)
    }

    @Test
    fun `config is data class with copy`() {
        val original = RepoConfig.builder<TestEntity>("test-repo").build()

        val copy = original.copy(storageKey = "new_key")

        assertEquals("new_key", copy.storageKey)
        assertEquals(original.id, copy.id)
        assertEquals(original.updateChannel, copy.updateChannel)
    }
}
