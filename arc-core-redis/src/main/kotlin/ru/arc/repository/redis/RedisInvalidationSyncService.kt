package ru.arc.repository.redis

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.arc.repository.Entity
import ru.arc.repository.RepoResult
import ru.arc.repository.SyncService

/**
 * Cross-server repository synchronization that keeps pub/sub messages small.
 *
 * Storage is authoritative: an UPDATE contains only an id and subscribers load
 * the entity after the publisher has completed its durable write.
 *
 * [localOrigin] prevents the publisher from loading its own freshly persisted
 * entity. [invalidationCoalesceMillis] bounds repeated reloads for a hot id;
 * an invalidation received during a reload always schedules one trailing load.
 */
class RedisInvalidationSyncService<T : Entity>(
    private val redis: RedisOperations,
    private val channel: String,
    private val loadEntity: suspend (String) -> RepoResult<T?>,
    private val gson: Gson = Gson(),
    private val scopeFactory: () -> CoroutineScope = {
        CoroutineScope(Dispatchers.IO + SupervisorJob())
    },
    private val localOrigin: String? = null,
    private val invalidationCoalesceMillis: Long = 0L,
) : SyncService<T> {
    init {
        require(invalidationCoalesceMillis >= 0L) {
            "Invalidation coalesce window cannot be negative"
        }
    }

    private val log = LoggerFactory.getLogger(RedisInvalidationSyncService::class.java)
    private var scope = scopeFactory()
    private val pendingUpdateLock = Any()
    private val pendingUpdates = mutableMapOf<String, PendingUpdate>()

    private var updateHandler: (suspend (T) -> Unit)? = null
    private var deleteHandler: (suspend (String) -> Unit)? = null

    private val listener = ChannelListener { ch, message, originServer ->
        if (ch == channel && !isLocalOrigin(originServer)) processMessage(message)
    }

    override suspend fun broadcastUpdate(entity: T): RepoResult<Unit> =
        RepoResult.runCatching {
            redis.publish(channel, gson.toJson(InvalidationMessage(MessageType.UPDATE, entity.id())))
        }

    override suspend fun broadcastDelete(id: String): RepoResult<Unit> =
        RepoResult.runCatching {
            redis.publish(channel, gson.toJson(InvalidationMessage(MessageType.DELETE, id)))
        }

    override fun onUpdate(handler: suspend (T) -> Unit) {
        updateHandler = handler
    }

    override fun onDelete(handler: suspend (String) -> Unit) {
        deleteHandler = handler
    }

    override fun start() {
        if (!scope.isActive) scope = scopeFactory()
        redis.registerChannelUnique(channel, listener)
        log.debug("Subscribed to invalidation channel: {}", channel)
    }

    override fun stop() {
        redis.unregisterChannel(channel, listener)
        scope.cancel()
        synchronized(pendingUpdateLock) {
            pendingUpdates.clear()
        }
        log.debug("Unsubscribed from invalidation channel: {}", channel)
    }

    private fun processMessage(json: String) {
        try {
            val message = gson.fromJson(json, InvalidationMessage::class.java)
                ?: error("Invalidation message cannot be null")
            require(message.id.isNotBlank()) { "Invalidation id cannot be blank" }

            when (message.type) {
                MessageType.UPDATE -> scheduleUpdate(message.id)
                MessageType.DELETE -> {
                    cancelPendingUpdate(message.id)
                    scope.launch { invokeDeleteHandler(message.id) }
                }
            }
        } catch (error: Exception) {
            log.error("Failed to process invalidation on {}: {}", channel, error.message, error)
        }
    }

    private fun scheduleUpdate(id: String) {
        var jobToStart: Job? = null
        synchronized(pendingUpdateLock) {
            val existing = pendingUpdates[id]
            if (existing != null) {
                existing.generation++
            } else {
                val pending = PendingUpdate()
                pending.job =
                    scope.launch(start = CoroutineStart.LAZY) {
                        drainUpdates(id, pending)
                    }
                pendingUpdates[id] = pending
                jobToStart = pending.job
            }
        }
        jobToStart?.start()
    }

    private suspend fun drainUpdates(
        id: String,
        pending: PendingUpdate,
    ) {
        try {
            while (currentCoroutineContext().isActive) {
                if (invalidationCoalesceMillis > 0L) {
                    delay(invalidationCoalesceMillis)
                }

                val observedGeneration =
                    synchronized(pendingUpdateLock) {
                        if (pendingUpdates[id] !== pending) return
                        pending.generation
                    }

                reloadAndDispatch(id)

                val hasTrailingUpdate =
                    synchronized(pendingUpdateLock) {
                        if (pendingUpdates[id] !== pending) {
                            false
                        } else if (pending.generation == observedGeneration) {
                            pendingUpdates.remove(id)
                            false
                        } else {
                            true
                        }
                    }
                if (!hasTrailingUpdate) return
            }
        } finally {
            synchronized(pendingUpdateLock) {
                if (pendingUpdates[id] === pending) pendingUpdates.remove(id)
            }
        }
    }

    private suspend fun reloadAndDispatch(id: String) {
        try {
            val entity = loadEntity(id).getOrThrow()
            currentCoroutineContext().ensureActive()
            if (entity == null) {
                log.warn("Invalidation for missing entity '{}' on channel {}", id, channel)
            } else {
                updateHandler?.invoke(entity)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.error("Failed to reload invalidated entity '{}' on {}: {}", id, channel, error.message, error)
        }
    }

    private suspend fun invokeDeleteHandler(id: String) {
        try {
            deleteHandler?.invoke(id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.error("Failed to process delete invalidation '{}' on {}: {}", id, channel, error.message, error)
        }
    }

    private fun cancelPendingUpdate(id: String) {
        val pending = synchronized(pendingUpdateLock) { pendingUpdates.remove(id) }
        pending?.job?.cancel()
    }

    private fun isLocalOrigin(originServer: String): Boolean =
        localOrigin?.equals(originServer, ignoreCase = true) == true

    private class PendingUpdate(
        var generation: Long = 1,
        var job: Job? = null,
    )

    private data class InvalidationMessage(
        val type: MessageType,
        val id: String,
    )

    private enum class MessageType {
        UPDATE,
        DELETE,
    }
}
