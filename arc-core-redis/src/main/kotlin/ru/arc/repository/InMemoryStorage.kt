package ru.arc.repository

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [Storage] for unit tests and local development.
 */
class InMemoryStorage<T : Entity> : Storage<T> {

    private val data = ConcurrentHashMap<String, T>()

    var failOnLoad = false
    var failOnSave = false
    var loadDelay: Long = 0
    var saveDelay: Long = 0

    var loadCount = 0
        private set
    var saveCount = 0
        private set
    var deleteCount = 0
        private set

    override suspend fun load(id: String): RepoResult<T?> {
        loadCount++
        if (loadDelay > 0) kotlinx.coroutines.delay(loadDelay)
        if (failOnLoad) return RepoResult.error("Simulated load failure")
        return RepoResult.success(data[id])
    }

    override suspend fun loadMany(ids: Set<String>): RepoResult<Map<String, T>> {
        loadCount++
        if (loadDelay > 0) kotlinx.coroutines.delay(loadDelay)
        if (failOnLoad) return RepoResult.error("Simulated load failure")
        return RepoResult.success(ids.mapNotNull { id -> data[id]?.let { id to it } }.toMap())
    }

    override suspend fun loadAll(): RepoResult<Map<String, T>> {
        loadCount++
        if (loadDelay > 0) kotlinx.coroutines.delay(loadDelay)
        if (failOnLoad) return RepoResult.error("Simulated load failure")
        return RepoResult.success(data.toMap())
    }

    override suspend fun save(entity: T): RepoResult<Unit> {
        saveCount++
        if (saveDelay > 0) kotlinx.coroutines.delay(saveDelay)
        if (failOnSave) return RepoResult.error("Simulated save failure")
        data[entity.id()] = entity
        return RepoResult.success(Unit)
    }

    override suspend fun saveMany(entities: Collection<T>): RepoResult<Unit> {
        saveCount++
        if (saveDelay > 0) kotlinx.coroutines.delay(saveDelay)
        if (failOnSave) return RepoResult.error("Simulated save failure")
        entities.forEach { data[it.id()] = it }
        return RepoResult.success(Unit)
    }

    override suspend fun delete(id: String): RepoResult<Unit> {
        deleteCount++
        if (failOnSave) return RepoResult.error("Simulated delete failure")
        data.remove(id)
        return RepoResult.success(Unit)
    }

    override suspend fun deleteMany(ids: Set<String>): RepoResult<Unit> {
        deleteCount++
        if (failOnSave) return RepoResult.error("Simulated delete failure")
        ids.forEach { data.remove(it) }
        return RepoResult.success(Unit)
    }

    override suspend fun exists(id: String): RepoResult<Boolean> {
        if (failOnLoad) return RepoResult.error("Simulated exists failure")
        return RepoResult.success(data.containsKey(id))
    }

    fun put(entity: T) {
        data[entity.id()] = entity
    }

    fun get(id: String): T? = data[id]
    fun clear() {
        data.clear()
        loadCount = 0
        saveCount = 0
        deleteCount = 0
    }

    fun size() = data.size
    fun contains(id: String) = data.containsKey(id)
    fun all(): Map<String, T> = data.toMap()
}

/**
 * In-memory [SyncService] for unit tests.
 */
class InMemorySyncService<T : Entity> : SyncService<T> {

    private var updateHandler: (suspend (T) -> Unit)? = null
    private var deleteHandler: (suspend (String) -> Unit)? = null

    private val broadcastedUpdates = mutableListOf<T>()
    private val broadcastedDeletes = mutableListOf<String>()

    var failOnBroadcast = false
    var isRunning = false
        private set

    override suspend fun broadcastUpdate(entity: T): RepoResult<Unit> {
        if (failOnBroadcast) return RepoResult.error("Simulated broadcast failure")
        broadcastedUpdates.add(entity)
        return RepoResult.success(Unit)
    }

    override suspend fun broadcastDelete(id: String): RepoResult<Unit> {
        if (failOnBroadcast) return RepoResult.error("Simulated broadcast failure")
        broadcastedDeletes.add(id)
        return RepoResult.success(Unit)
    }

    override fun onUpdate(handler: suspend (T) -> Unit) {
        updateHandler = handler
    }

    override fun onDelete(handler: suspend (String) -> Unit) {
        deleteHandler = handler
    }

    override fun start() {
        isRunning = true
    }

    override fun stop() {
        isRunning = false
    }

    suspend fun simulateRemoteUpdate(entity: T) {
        updateHandler?.invoke(entity)
    }

    suspend fun simulateRemoteDelete(id: String) {
        deleteHandler?.invoke(id)
    }

    fun getBroadcastedUpdates(): List<T> = broadcastedUpdates.toList()
    fun getBroadcastedDeletes(): List<String> = broadcastedDeletes.toList()

    fun clear() {
        broadcastedUpdates.clear()
        broadcastedDeletes.clear()
    }
}
