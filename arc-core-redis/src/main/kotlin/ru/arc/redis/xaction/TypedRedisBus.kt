package ru.arc.redis.xaction

import com.google.gson.Gson
import org.slf4j.LoggerFactory
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations

/**
 * Redis pub/sub bus for typed JSON messages (e.g. cross-server [XAction] wire).
 */
class TypedRedisBus<T>(
    private val redis: RedisOperations,
    private val channel: String,
    private val gson: Gson,
    private val messageType: Class<T>,
    private val onMessage: (message: T, originServer: String) -> Unit,
) {
    private val log = LoggerFactory.getLogger(TypedRedisBus::class.java)

    private val listener = ChannelListener { ch, message, originServer ->
        if (ch != channel) return@ChannelListener
        if (message.length > MAX_MESSAGE_CHARACTERS) {
            log.error("[{}] Rejected oversized message from server '{}' ({} chars)", channel, originServer, message.length)
            return@ChannelListener
        }
        try {
            val parsed = gson.fromJson(message, messageType)
            if (parsed == null) {
                log.error("[{}] Deserialized null message from server '{}'", channel, originServer)
                return@ChannelListener
            }
            log.debug("[{}] Received message from server '{}'", channel, originServer)
            onMessage(parsed, originServer)
        } catch (e: Exception) {
            log.error("[{}] Failed to deserialize bounded message from server '{}' ({} chars)", channel, originServer, message.length, e)
        }
    }

    fun register() {
        redis.registerChannelUnique(channel, listener)
        log.info("[{}] Subscribed to channel", channel)
    }

    fun unregister() {
        redis.unregisterChannel(channel, listener)
        log.debug("[{}] Unsubscribed from channel", channel)
    }

    fun publish(message: T) {
        val json = gson.toJson(message)
        require(json.length <= MAX_MESSAGE_CHARACTERS) { "Typed Redis message exceeds its size limit" }
        log.debug("[{}] Publishing message ({} chars)", channel, json.length)
        redis.publish(channel, json)
    }

    private companion object {
        const val MAX_MESSAGE_CHARACTERS = 1_048_576
    }
}
