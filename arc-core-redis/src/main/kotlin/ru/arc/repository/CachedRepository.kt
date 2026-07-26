package ru.arc.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A cached repository that combines local cache with remote storage.
 *
 * Features:
 * - Local in-memory cache for fast reads
 * - Dirty tracking for efficient writes
 * - Background sync to remote storage
 * - Retry logic with exponential backoff
 * - Pub/sub for cross-server synchronization
 * - Context-aware loading
 */
class CachedRepository<T : Entity>(
    private val config: RepoConfig<T>,
    private val storage: Storage<T>,
    private val syncService: SyncService<T>? = null,
    private val cache: DirtyTrackingCache<T> = ConcurrentDirtyTrackingCache(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : ContextRepository<T>, ObservableRepository<T> {

    // Context management
    private val contexts = ConcurrentHashMap.newKeySet<String>()

    // Access tracking for cleanup
    private val lastAccess = ConcurrentHashMap<String, Long>()

    // Load tracking to prevent duplicate loads
    private val loadingDeferreds = ConcurrentHashMap<String, CompletableDeferred<RepoResult<T?>>>()
    private val loadAttempts = ConcurrentHashMap<String, Long>()
    private val loadCooldown = 60_000L // 1 minute

    // Observable flows
    private val entityUpdates = MutableSharedFlow<Pair<String, T?>>(extraBufferCapacity = 100)
    private val allUpdates = MutableStateFlow<List<T>>(emptyList())

    // Background jobs
    private var saveJob: Job? = null
    private var cleanupJob: Job? = null

    // Lock for atomic operations
    private val mutex = Mutex()
    private val log = LoggerFactory.getLogger(CachedRepository::class.java)

    companion object {
        private val registry = CopyOnWriteArrayList<CachedRepository<*>>()

        @JvmStatic
        fun allStats(): List<CacheStats> = registry.map { it.getStats() }

        internal fun register(repo: CachedRepository<*>) {
            registry.removeIf { it.config.id == repo.config.id }
            registry.add(repo)
        }

        internal fun unregister(repo: CachedRepository<*>) {
            registry.remove(repo)
        }
    }

    /**
     * Initialize the repository.
     */
    suspend fun init(): RepoResult<Unit> {
        log.info("Initializing repository: ${config.id}")
        register(this)

        val result =
            try {
                if (config.loadAllOnStart) {
                    val loadResult = loadAll()
                    if (loadResult.isError) {
                        loadResult.map { }
                    } else {
                        startServices()
                        RepoResult.success(Unit)
                    }
                } else {
                    startServices()
                    RepoResult.success(Unit)
                }
            } catch (e: Exception) {
                RepoResult.error("Failed to initialize repository '${config.id}': ${e.message}", e)
            }

        if (result.isError) {
            cleanupAfterFailedInit()
        }

        return result
    }

    private fun startServices() {
        startBackgroundSync()
        if (config.enableCleanup) {
            startCleanupJob()
        }
        setupSyncListeners()
    }

    private fun cleanupAfterFailedInit() {
        saveJob?.cancel()
        cleanupJob?.cancel()
        runCatching { syncService?.stop() }
        unregister(this)
        scope.cancel()
    }

    /**
     * Shutdown the repository gracefully.
     */
    suspend fun shutdown() {
        log.info("Shutting down repository: ${config.id}")

        saveJob?.cancel()
        cleanupJob?.cancel()
        try {
            syncService?.stop()
            saveDirty()
        } finally {
            unregister(this)
            scope.cancel()
        }
    }

    // =========================================================================
    // Repository Implementation
    // =========================================================================

    override suspend fun get(id: String): RepoResult<T?> {
        // Try cache first
        cache.get(id)?.let {
            updateAccessTime(id)
            return RepoResult.success(it)
        }

        // Try loading from storage
        return loadFromStorage(id)
    }

    override suspend fun getOrCreate(id: String, factory: () -> T): RepoResult<T> {
        // Check cache
        cache.get(id)?.let {
            log.debug("[{}] getOrCreate: cache hit for {}", config.id, id)
            updateAccessTime(id)
            return RepoResult.success(it)
        }
        log.debug("[{}] getOrCreate: cache miss for {}, loading from storage", config.id, id)

        // Try loading
        val loaded = loadFromStorage(id)
        @Suppress("UNCHECKED_CAST")
        if (loaded.isError) {
            log.debug("[{}] getOrCreate: storage error for {}: {}", config.id, id, (loaded as RepoResult.Error).message)
            return loaded as RepoResult<T>
        }

        loaded.getOrNull()?.let {
            log.debug("[{}] getOrCreate: loaded existing entity {} from storage", config.id, id)
            return RepoResult.success(it)
        }

        // Concurrent callers share the storage load above. The cache performs
        // the final creation atomically, so the factory runs at most once.
        var created = false
        val entity =
            try {
                cache.getOrPut(id) {
                    factory().also {
                        require(it.id() == id) {
                            "Created entity id '${it.id()}' does not match requested id '$id'"
                        }
                        created = true
                    }
                }
            } catch (e: Exception) {
                return RepoResult.error("Failed to create entity '$id': ${e.message}", e)
            }
        updateAccessTime(id)
        if (created) {
            log.debug("[{}] getOrCreate: created new entity {}", config.id, id)
            entityUpdates.tryEmit(id to entity)
            updateAllFlow()
        }
        return RepoResult.success(entity)
    }

    override suspend fun save(entity: T): RepoResult<Unit> {
        markDirty(entity)
        return RepoResult.success(Unit)
    }

    override suspend fun delete(id: String): RepoResult<Unit> {
        cache.remove(id)
        lastAccess.remove(id)

        // Delete from storage
        val result = withRetry { storage.delete(id) }
        if (result.isError) {
            log.warn("Failed to delete $id from storage: ${(result as RepoResult.Error).message}")
        }

        // Broadcast deletion
        syncService?.broadcastDelete(id)

        // Notify observers
        entityUpdates.tryEmit(id to null)
        updateAllFlow()

        return result
    }

    override suspend fun all(): RepoResult<List<T>> {
        return RepoResult.success(cache.all().toList())
    }

    override suspend fun exists(id: String): RepoResult<Boolean> {
        if (cache.contains(id)) return RepoResult.success(true)
        return storage.exists(id)
    }

    // =========================================================================
    // Observable Implementation
    // =========================================================================

    override fun observe(id: String): Flow<T?> {
        return entityUpdates
            .filter { it.first == id }
            .map { it.second }
            .onStart { emit(cache.get(id)) }
    }

    override fun observeAll(): Flow<List<T>> = allUpdates.asStateFlow()

    // =========================================================================
    // Context Implementation
    // =========================================================================

    override fun addContext(id: String) {
        if (contexts.add(id)) {
            updateAccessTime(id)
            scope.launch {
                // Pre-load entity for this context
                loadFromStorage(id)
            }
        }
    }

    override fun removeContext(id: String) {
        contexts.remove(id)
    }

    override fun getContexts(): Set<String> = contexts.toSet()

    // =========================================================================
    // Sync Operations
    // =========================================================================

    /**
     * Manually save all dirty entities.
     */
    suspend fun saveDirty(): RepoResult<Unit> = mutex.withLock {
        val dirty = cache.getDirtyEntities()
        if (dirty.isEmpty()) return RepoResult.success(Unit)

        log.debug("Saving ${dirty.size} dirty entities for ${config.id}")
        // Clear the snapshot before the asynchronous write. If code mutates an
        // entity while the write is in flight, markDirty() will add it back and
        // the newer state will be persisted by the next pass.
        dirty.forEach { cache.markClean(it.id()) }

        val result = withRetry {
            storage.saveMany(dirty)
        }

        if (result.isSuccess) {
            // Broadcast updates
            dirty.forEach { entity ->
                syncService?.broadcastUpdate(entity)
            }
        } else {
            dirty.forEach { cache.markDirty(it.id()) }
            log.warn("Failed to save dirty entities: ${(result as RepoResult.Error).message}")
        }

        return result
    }

    /**
     * Load all entities from storage.
     */
    suspend fun loadAll(): RepoResult<Unit> = mutex.withLock {
        log.debug("Loading all entities for ${config.id}")

        val result = storage.loadAll()

        result.map { entities ->
            val loadedIds = entities.keys
            val staleIds =
                cache.keys().filter { id ->
                    id !in loadedIds && !cache.isDirty(id)
                }
            staleIds.forEach { id ->
                cache.remove(id)
                lastAccess.remove(id)
                entityUpdates.tryEmit(id to null)
            }

            entities.forEach { (_, entity) ->
                if (cache.isDirty(entity.id())) return@forEach
                cache.put(entity)
                cache.markClean(entity.id())
                updateAccessTime(entity.id())
            }
            updateAllFlow()
        }
    }

    /**
     * Synchronous cache read — returns entity if already in cache, null otherwise.
     * Does not trigger storage load. Use for hot-path reads where cache is guaranteed warm.
     */
    fun getNow(id: String): T? = cache.get(id)

    /**
     * Synchronous read of all cached entities.
     */
    fun allNow(): List<T> = cache.all().toList()

    /**
     * Mark entity as dirty so the background save job persists it.
     * Equivalent to calling save() without coroutine overhead.
     */
    fun markDirty(entity: T) {
        cache.put(entity)
        updateAccessTime(entity.id())
        entityUpdates.tryEmit(entity.id() to entity)
        updateAllFlow()
    }

    /**
     * Get cache statistics.
     */
    fun getStats(): CacheStats = CacheStats(
        repoId = config.id,
        cacheSize = cache.size(),
        dirtyCount = cache.getDirtyIds().size,
        contextCount = contexts.size
    )

    /**
     * Manually trigger cleanup (useful for testing).
     */
    suspend fun cleanupNow() {
        cleanupExpiredEntities()
    }

    /**
     * Set last access time for testing (internal use).
     */
    internal fun setLastAccessTime(id: String, time: Long) {
        lastAccess[id] = time
    }

    // =========================================================================
    // Private Methods
    // =========================================================================

    private suspend fun loadFromStorage(id: String): RepoResult<T?> {
        // Check if already loading - wait for existing load
        val existingDeferred = loadingDeferreds[id]
        if (existingDeferred != null) {
            log.debug("[{}] loadFromStorage: waiting for in-progress load of {}", config.id, id)
            return existingDeferred.await()
        }

        // Check load cooldown
        val lastAttempt = loadAttempts[id]
        if (lastAttempt != null && System.currentTimeMillis() - lastAttempt < loadCooldown) {
            val remaining = loadCooldown - (System.currentTimeMillis() - lastAttempt)
            log.debug("[{}] loadFromStorage: skipping {} — on cooldown for {}ms more", config.id, id, remaining)
            return RepoResult.success(null)
        }

        // Create deferred for this load
        val deferred = CompletableDeferred<RepoResult<T?>>()
        loadingDeferreds[id] = deferred

        try {
            loadAttempts[id] = System.currentTimeMillis()
            log.debug("[{}] loadFromStorage: fetching {} from Redis (key={})", config.id, id, config.storageKey)

            val result = withRetry { storage.load(id) }

            if (result.isSuccess) {
                val entity = result.getOrNull()
                if (entity != null) {
                    log.debug("[{}] loadFromStorage: loaded {} successfully", config.id, id)
                    cache.put(entity)
                    cache.markClean(entity.id())
                    updateAccessTime(entity.id())
                    entityUpdates.tryEmit(entity.id() to entity)
                    updateAllFlow()
                } else {
                    log.debug("[{}] loadFromStorage: {} not found in Redis (null)", config.id, id)
                }
            } else {
                log.debug("[{}] loadFromStorage: storage error for {}: {}", config.id, id, (result as RepoResult.Error).message)
            }

            deferred.complete(result)
            return result
        } catch (e: Exception) {
            val errorResult = RepoResult.error("Failed to load $id: ${e.message}", e)
            log.debug("[{}] loadFromStorage: exception loading {}: {}", config.id, id, e.message)
            deferred.complete(errorResult)
            return errorResult
        } finally {
            loadingDeferreds.remove(id)
        }
    }

    private fun startBackgroundSync() {
        saveJob = scope.launch {
            while (isActive) {
                delay(config.saveInterval)
                try {
                    saveDirty()
                } catch (e: Exception) {
                    log.error("Error in background save for ${config.id}", e)
                }
            }
        }
    }

    private fun startCleanupJob() {
        cleanupJob = scope.launch {
            while (isActive) {
                delay(config.cleanupInterval)
                try {
                    cleanupExpiredEntities()
                } catch (e: Exception) {
                    log.error("Error in cleanup for ${config.id}", e)
                }
            }
        }
    }

    /**
     * Remove entities from cache that:
     * - Are NOT in context (we don't care about them)
     * - Haven't been accessed recently (older than entityTimeout)
     */
    private suspend fun cleanupExpiredEntities(): Unit = mutex.withLock {
        if (!config.enableCleanup) return

        val now = System.currentTimeMillis()
        val timeoutMillis = config.entityTimeout.inWholeMilliseconds
        val cutoff = now - timeoutMillis

        val toRemove = mutableListOf<String>()

        // Find entities to evict
        cache.keys().forEach { id ->
            // Never evict context entities
            if (contexts.contains(id)) return@forEach

            val lastAccessTime = lastAccess[id] ?: 0L

            // Evict if not accessed recently
            if (lastAccessTime < cutoff) {
                toRemove.add(id)
            }
        }

        if (toRemove.isNotEmpty()) {
            log.debug("Cleaning up ${toRemove.size} expired entities from ${config.id}")

            toRemove.forEach { id ->
                cache.remove(id)
                lastAccess.remove(id)
                entityUpdates.tryEmit(id to null)  // Notify observers
            }

            updateAllFlow()
        }
    }

    /**
     * Update last access time for an entity.
     */
    private fun updateAccessTime(id: String) {
        lastAccess[id] = System.currentTimeMillis()
    }

    private fun setupSyncListeners() {
        syncService?.onUpdate { entity ->
            // Check if we have this entity or it's in our context
            if (cache.contains(entity.id()) || contexts.contains(entity.id())) {
                val existing = cache.get(entity.id())
                if (existing != null) {
                    // Merge if entity supports it
                    @Suppress("UNCHECKED_CAST")
                    if (existing is Mergeable<*>) {
                        (existing as Mergeable<T>).merge(entity)
                    } else {
                        cache.put(entity)
                    }
                } else {
                    cache.put(entity)
                }
                cache.markClean(entity.id())
                updateAccessTime(entity.id())
                entityUpdates.tryEmit(entity.id() to entity)
                updateAllFlow()
            }
        }

        syncService?.onDelete { id ->
            cache.remove(id)
            entityUpdates.tryEmit(id to null)
            updateAllFlow()
        }

        syncService?.start()
    }

    private suspend fun <R> withRetry(block: suspend () -> RepoResult<R>): RepoResult<R> {
        var lastError: RepoResult.Error? = null

        repeat(config.maxRetries) { attempt ->
            val result = try {
                block()
            } catch (e: Exception) {
                RepoResult.error("Exception: ${e.message}", e)
            }

            if (result.isSuccess) return result

            lastError = result as RepoResult.Error
            val delay = config.retryBaseDelay * (1 shl attempt)
            log.debug("Retry ${attempt + 1}/${config.maxRetries} after $delay")
            delay(delay)
        }

        return lastError ?: RepoResult.error("Max retries exceeded")
    }

    private fun updateAllFlow() {
        allUpdates.value = cache.all().toList()
    }
}

/**
 * Interface for entities that support merging.
 */
interface Mergeable<T> {
    fun merge(other: T)
}

/**
 * Cache statistics.
 */
data class CacheStats(
    val repoId: String,
    val cacheSize: Int,
    val dirtyCount: Int,
    val contextCount: Int
)
