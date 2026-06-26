package ru.arc.repository.redis

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.arc.repository.Entity
import ru.arc.repository.RepoResult
import ru.arc.repository.SyncService
import java.lang.reflect.Type

/**
 * Redis pub/sub implementation of SyncService.
 */
class RedisSyncService<T : Entity>(
    private val redis: RedisOperations,
    private val channel: String,
    private val entityType: Type,
    private val gson: Gson = Gson(),
) : SyncService<T> {
    private val log = LoggerFactory.getLogger(RedisSyncService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var updateHandler: (suspend (T) -> Unit)? = null
    private var deleteHandler: (suspend (String) -> Unit)? = null

    private val listener = ChannelListener { ch, message, _ ->
        if (ch == channel) {
            processMessage(message)
        }
    }

    override suspend fun broadcastUpdate(entity: T): RepoResult<Unit> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            val message = SyncMessage(
                type = MessageType.UPDATE,
                id = entity.id(),
                data = gson.toJson(entity),
            )
            redis.publish(channel, gson.toJson(message))
        }
    }

    override suspend fun broadcastDelete(id: String): RepoResult<Unit> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            val message = SyncMessage(
                type = MessageType.DELETE,
                id = id,
                data = null,
            )
            redis.publish(channel, gson.toJson(message))
        }
    }

    override fun onUpdate(handler: suspend (T) -> Unit) {
        updateHandler = handler
    }

    override fun onDelete(handler: suspend (String) -> Unit) {
        deleteHandler = handler
    }

    override fun start() {
        redis.registerChannelUnique(channel, listener)
        log.debug("Subscribed to sync channel: $channel")
    }

    override fun stop() {
        redis.unregisterChannel(channel, listener)
        log.debug("Unsubscribed from sync channel: $channel")
    }

    @Suppress("UNCHECKED_CAST")
    private fun processMessage(json: String) {
        scope.launch {
            try {
                val message = gson.fromJson(json, SyncMessage::class.java)

                when (message.type) {
                    MessageType.UPDATE -> {
                        val entity = gson.fromJson(message.data, entityType) as T
                        updateHandler?.invoke(entity)
                    }

                    MessageType.DELETE -> {
                        deleteHandler?.invoke(message.id)
                    }
                }
            } catch (e: Exception) {
                log.error("Failed to process sync message: ${e.message}", e)
            }
        }
    }

    private data class SyncMessage(
        val type: MessageType,
        val id: String,
        val data: String?,
    )

    private enum class MessageType {
        UPDATE, DELETE
    }
}
