package ru.arc.repository.redis

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 */
class RedisInvalidationSyncService<T : Entity>(
    private val redis: RedisOperations,
    private val channel: String,
    private val loadEntity: suspend (String) -> RepoResult<T?>,
    private val gson: Gson = Gson(),
    private val scopeFactory: () -> CoroutineScope = {
        CoroutineScope(Dispatchers.IO + SupervisorJob())
    },
) : SyncService<T> {
    private val log = LoggerFactory.getLogger(RedisInvalidationSyncService::class.java)
    private var scope = scopeFactory()

    private var updateHandler: (suspend (T) -> Unit)? = null
    private var deleteHandler: (suspend (String) -> Unit)? = null

    private val listener = ChannelListener { ch, message, _ ->
        if (ch == channel) processMessage(message)
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
        log.debug("Unsubscribed from invalidation channel: {}", channel)
    }

    private fun processMessage(json: String) {
        scope.launch {
            try {
                val message = gson.fromJson(json, InvalidationMessage::class.java)
                    ?: error("Invalidation message cannot be null")
                require(message.id.isNotBlank()) { "Invalidation id cannot be blank" }

                when (message.type) {
                    MessageType.UPDATE -> {
                        val entity = loadEntity(message.id).getOrThrow()
                        if (entity == null) {
                            log.warn("Invalidation for missing entity '{}' on channel {}", message.id, channel)
                        } else {
                            updateHandler?.invoke(entity)
                        }
                    }

                    MessageType.DELETE -> deleteHandler?.invoke(message.id)
                }
            } catch (error: Exception) {
                log.error("Failed to process invalidation on {}: {}", channel, error.message, error)
            }
        }
    }

    private data class InvalidationMessage(
        val type: MessageType,
        val id: String,
    )

    private enum class MessageType {
        UPDATE,
        DELETE,
    }
}
