package ru.arc.repository

/**
 * Remote storage interface for persistence.
 */
interface Storage<T : Entity> {
    /**
     * Load entity by ID.
     */
    suspend fun load(id: String): RepoResult<T?>

    /**
     * Load multiple entities by IDs.
     */
    suspend fun loadMany(ids: Set<String>): RepoResult<Map<String, T>>

    /**
     * Load all entities.
     */
    suspend fun loadAll(): RepoResult<Map<String, T>>

    /**
     * Save entity.
     */
    suspend fun save(entity: T): RepoResult<Unit>

    /**
     * Save multiple entities.
     */
    suspend fun saveMany(entities: Collection<T>): RepoResult<Unit>

    /**
     * Delete entity by ID.
     */
    suspend fun delete(id: String): RepoResult<Unit>

    /**
     * Delete multiple entities by IDs.
     */
    suspend fun deleteMany(ids: Set<String>): RepoResult<Unit>

    /**
     * Check if entity exists.
     */
    suspend fun exists(id: String): RepoResult<Boolean>
}

/**
 * Pub/Sub interface for cross-server synchronization.
 */
interface PubSub {
    /**
     * Subscribe to a channel.
     */
    fun subscribe(channel: String, listener: (String) -> Unit)

    /**
     * Unsubscribe from a channel.
     */
    fun unsubscribe(channel: String)

    /**
     * Publish message to a channel.
     */
    suspend fun publish(channel: String, message: String): RepoResult<Unit>
}

/**
 * Synchronization service for distributed updates.
 */
interface SyncService<T : Entity> {
    /**
     * Broadcast entity update to other servers.
     */
    suspend fun broadcastUpdate(entity: T): RepoResult<Unit>

    /**
     * Broadcast entity deletion to other servers.
     */
    suspend fun broadcastDelete(id: String): RepoResult<Unit>

    /**
     * Subscribe to updates.
     */
    fun onUpdate(handler: suspend (T) -> Unit)

    /**
     * Subscribe to deletions.
     */
    fun onDelete(handler: suspend (String) -> Unit)

    /**
     * Start listening for updates.
     */
    fun start()

    /**
     * Stop listening.
     */
    fun stop()
}
