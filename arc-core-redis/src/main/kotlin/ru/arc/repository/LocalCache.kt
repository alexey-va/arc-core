package ru.arc.repository

import java.util.concurrent.ConcurrentHashMap

/**
 * Local in-memory cache interface.
 */
interface LocalCache<T : Entity> {
    /**
     * Get entity from cache.
     */
    fun get(id: String): T?

    /**
     * Put entity into cache.
     */
    fun put(entity: T)

    /**
     * Remove entity from cache.
     */
    fun remove(id: String): T?

    /**
     * Check if entity exists in cache.
     */
    fun contains(id: String): Boolean

    /**
     * Get all entities in cache.
     */
    fun all(): Collection<T>

    /**
     * Get all IDs in cache.
     */
    fun keys(): Set<String>

    /**
     * Get cache size.
     */
    fun size(): Int

    /**
     * Clear all entries.
     */
    fun clear()

    /**
     * Get or compute if absent.
     */
    fun getOrPut(id: String, factory: () -> T): T
}

/**
 * Thread-safe implementation using ConcurrentHashMap.
 */
class ConcurrentLocalCache<T : Entity> : LocalCache<T> {
    private val map = ConcurrentHashMap<String, T>()

    override fun get(id: String): T? = map[id]

    override fun put(entity: T) {
        map[entity.id()] = entity
    }

    override fun remove(id: String): T? = map.remove(id)

    override fun contains(id: String): Boolean = map.containsKey(id)

    override fun all(): Collection<T> = map.values.toList()

    override fun keys(): Set<String> = map.keys.toSet()

    override fun size(): Int = map.size

    override fun clear() = map.clear()

    override fun getOrPut(id: String, factory: () -> T): T {
        // Use computeIfAbsent for atomic operation - factory called at most once
        return map.computeIfAbsent(id) { factory() }
    }
}

/**
 * Cache with dirty tracking for optimized persistence.
 */
interface DirtyTrackingCache<T : Entity> : LocalCache<T> {
    /**
     * Mark entity as dirty (needs saving).
     */
    fun markDirty(id: String)

    /**
     * Mark entity as clean (saved).
     */
    fun markClean(id: String)

    /**
     * Check if entity is dirty.
     */
    fun isDirty(id: String): Boolean

    /**
     * Get all dirty entity IDs.
     */
    fun getDirtyIds(): Set<String>

    /**
     * Get all dirty entities.
     */
    fun getDirtyEntities(): List<T>

    /**
     * Clear all dirty flags.
     */
    fun clearDirtyFlags()
}

/**
 * Thread-safe implementation with dirty tracking.
 */
class ConcurrentDirtyTrackingCache<T : Entity> : DirtyTrackingCache<T> {
    private val map = ConcurrentHashMap<String, T>()
    private val dirtySet = ConcurrentHashMap.newKeySet<String>()

    override fun get(id: String): T? = map[id]

    override fun put(entity: T) {
        map[entity.id()] = entity
        dirtySet.add(entity.id())
    }

    override fun remove(id: String): T? {
        dirtySet.remove(id)
        return map.remove(id)
    }

    override fun contains(id: String): Boolean = map.containsKey(id)

    override fun all(): Collection<T> = map.values.toList()

    override fun keys(): Set<String> = map.keys.toSet()

    override fun size(): Int = map.size

    override fun clear() {
        map.clear()
        dirtySet.clear()
    }

    override fun getOrPut(id: String, factory: () -> T): T {
        return map.getOrPut(id) {
            factory().also { dirtySet.add(it.id()) }
        }
    }

    override fun markDirty(id: String) {
        if (map.containsKey(id)) {
            dirtySet.add(id)
        }
    }

    override fun markClean(id: String) {
        dirtySet.remove(id)
    }

    override fun isDirty(id: String): Boolean = dirtySet.contains(id)

    override fun getDirtyIds(): Set<String> = dirtySet.toSet()

    override fun getDirtyEntities(): List<T> = dirtySet.mapNotNull { map[it] }

    override fun clearDirtyFlags() = dirtySet.clear()
}

