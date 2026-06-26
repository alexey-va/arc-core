
package ru.arc.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalCacheTest {

    // =========================================================================
    // ConcurrentLocalCache Tests
    // =========================================================================

    @Nested
    @DisplayName("ConcurrentLocalCache")
    inner class ConcurrentLocalCacheTests {

        private lateinit var cache: ConcurrentLocalCache<TestEntity>

        @BeforeEach
        fun setup() {
            cache = ConcurrentLocalCache()
        }

        @Test
        fun `put adds entity to cache`() {
            val entity = TestEntity("id1", "value1")

            cache.put(entity)

            assertEquals(entity, cache.get("id1"))
        }

        @Test
        fun `get returns null for missing entity`() {
            assertNull(cache.get("missing"))
        }

        @Test
        fun `remove removes entity from cache`() {
            cache.put(TestEntity("id1", "value"))

            val removed = cache.remove("id1")

            assertEquals("value", removed?.value)
            assertNull(cache.get("id1"))
        }

        @Test
        fun `remove returns null for missing entity`() {
            assertNull(cache.remove("missing"))
        }

        @Test
        fun `contains returns true for existing entity`() {
            cache.put(TestEntity("id1", "value"))

            assertTrue(cache.contains("id1"))
        }

        @Test
        fun `contains returns false for missing entity`() {
            assertFalse(cache.contains("missing"))
        }

        @Test
        fun `all returns all entities`() {
            cache.put(TestEntity("id1", "value1"))
            cache.put(TestEntity("id2", "value2"))

            val all = cache.all()

            assertEquals(2, all.size)
        }

        @Test
        fun `keys returns all keys`() {
            cache.put(TestEntity("id1", "value1"))
            cache.put(TestEntity("id2", "value2"))

            val keys = cache.keys()

            assertTrue(keys.contains("id1"))
            assertTrue(keys.contains("id2"))
        }

        @Test
        fun `size returns correct count`() {
            cache.put(TestEntity("id1", "value1"))
            cache.put(TestEntity("id2", "value2"))

            assertEquals(2, cache.size())
        }

        @Test
        fun `clear removes all entities`() {
            cache.put(TestEntity("id1", "value1"))
            cache.put(TestEntity("id2", "value2"))

            cache.clear()

            assertEquals(0, cache.size())
        }

        @Test
        fun `getOrPut returns existing entity`() {
            cache.put(TestEntity("id1", "existing"))

            val result = cache.getOrPut("id1") { TestEntity("id1", "new") }

            assertEquals("existing", result.value)
        }

        @Test
        fun `getOrPut creates new entity if missing`() {
            val result = cache.getOrPut("id1") { TestEntity("id1", "created") }

            assertEquals("created", result.value)
        }

        @Test
        fun `concurrent access is thread-safe`() {
            val executor = Executors.newFixedThreadPool(10)
            val latch = CountDownLatch(1000)

            repeat(1000) { i ->
                executor.submit {
                    cache.put(TestEntity("id$i", "value$i"))
                    cache.get("id$i")
                    latch.countDown()
                }
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(1000, cache.size())

            executor.shutdown()
        }

        @Test
        fun `concurrent getOrPut creates exactly one entity`() {
            val executor = Executors.newFixedThreadPool(10)
            val latch = CountDownLatch(100)
            val createCount = AtomicInteger(0)

            repeat(100) {
                executor.submit {
                    cache.getOrPut("shared_id") {
                        createCount.incrementAndGet()
                        TestEntity("shared_id", "value")
                    }
                    latch.countDown()
                }
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(1, createCount.get())

            executor.shutdown()
        }
    }

    // =========================================================================
    // ConcurrentDirtyTrackingCache Tests
    // =========================================================================

    @Nested
    @DisplayName("ConcurrentDirtyTrackingCache")
    inner class DirtyTrackingCacheTests {

        private lateinit var cache: ConcurrentDirtyTrackingCache<TestEntity>

        @BeforeEach
        fun setup() {
            cache = ConcurrentDirtyTrackingCache()
        }

        @Test
        fun `put marks entity as dirty`() {
            cache.put(TestEntity("id1", "value"))

            assertTrue(cache.isDirty("id1"))
        }

        @Test
        fun `markClean removes dirty flag`() {
            cache.put(TestEntity("id1", "value"))

            cache.markClean("id1")

            assertFalse(cache.isDirty("id1"))
        }

        @Test
        fun `markDirty sets dirty flag`() {
            cache.put(TestEntity("id1", "value"))
            cache.markClean("id1")

            cache.markDirty("id1")

            assertTrue(cache.isDirty("id1"))
        }

        @Test
        fun `markDirty does nothing for missing entity`() {
            cache.markDirty("missing")

            assertFalse(cache.isDirty("missing"))
        }

        @Test
        fun `getDirtyIds returns all dirty ids`() {
            cache.put(TestEntity("id1", "value1"))
            cache.put(TestEntity("id2", "value2"))
            cache.put(TestEntity("id3", "value3"))
            cache.markClean("id2")

            val dirtyIds = cache.getDirtyIds()

            assertTrue(dirtyIds.contains("id1"))
            assertFalse(dirtyIds.contains("id2"))
            assertTrue(dirtyIds.contains("id3"))
        }

        @Test
        fun `getDirtyEntities returns dirty entities`() {
            cache.put(TestEntity("id1", "value1"))
            cache.put(TestEntity("id2", "value2"))
            cache.markClean("id2")

            val dirtyEntities = cache.getDirtyEntities()

            assertEquals(1, dirtyEntities.size)
            assertEquals("id1", dirtyEntities[0].id())
        }

        @Test
        fun `clearDirtyFlags clears all dirty flags`() {
            cache.put(TestEntity("id1", "value1"))
            cache.put(TestEntity("id2", "value2"))

            cache.clearDirtyFlags()

            assertEquals(0, cache.getDirtyIds().size)
        }

        @Test
        fun `remove clears dirty flag`() {
            cache.put(TestEntity("id1", "value"))

            cache.remove("id1")

            assertFalse(cache.isDirty("id1"))
        }

        @Test
        fun `clear clears dirty flags`() {
            cache.put(TestEntity("id1", "value1"))
            cache.put(TestEntity("id2", "value2"))

            cache.clear()

            assertEquals(0, cache.getDirtyIds().size)
        }

        @Test
        fun `getOrPut marks new entity as dirty`() {
            cache.getOrPut("id1") { TestEntity("id1", "value") }

            assertTrue(cache.isDirty("id1"))
        }

        @Test
        fun `getOrPut does not mark existing entity as dirty`() {
            cache.put(TestEntity("id1", "value"))
            cache.markClean("id1")

            cache.getOrPut("id1") { TestEntity("id1", "new_value") }

            assertFalse(cache.isDirty("id1"))
        }
    }
}
