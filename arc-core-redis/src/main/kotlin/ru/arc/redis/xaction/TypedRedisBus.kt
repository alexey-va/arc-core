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
        try {
            val parsed = gson.fromJson(message, messageType)
            if (parsed == null) {
                log.error("[{}] Deserialized null message from server '{}': {}", channel, originServer, message)
                return@ChannelListener
            }
            log.debug("[{}] Received message from server '{}'", channel, originServer)
            onMessage(parsed, originServer)
        } catch (e: Exception) {
            log.error("[{}] Failed to deserialize message from server '{}': {}", channel, originServer, message, e)
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
        log.debug("[{}] Publishing: {}", channel, json)
        redis.publish(channel, json)
    }
}
