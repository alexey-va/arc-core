package ru.arc.redis.safety

import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import java.util.concurrent.atomic.AtomicBoolean

enum class RedisMessageRejection {
    ORIGIN_REJECTED,
    EMBEDDED_ORIGIN_MISMATCH,
    MALFORMED_PAYLOAD,
    DUPLICATE,
    REPLAY_GUARD_FULL,
}

/**
 * Bounded typed Redis pub/sub boundary with origin policy and optional replay
 * protection. Rejections expose only a stable reason; raw payloads never enter
 * diagnostics through this API.
 */
class OriginBoundRedisBus<T : Any>(
    private val redis: RedisOperations,
    private val channel: String,
    private val codec: BoundedJsonCodec<T>,
    private val originAllowed: (String) -> Boolean,
    private val embeddedOrigin: ((T) -> String)? = null,
    private val messageId: ((T) -> String)? = null,
    private val deduplicator: RecentMessageDeduplicator? = null,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val onMessage: (message: T, originServer: String) -> Unit,
    private val onRejected: (RedisMessageRejection) -> Unit = {},
    private val onHandlerFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val registered = AtomicBoolean(false)

    init {
        require(channel.matches(CHANNEL_PATTERN)) { "Unsafe Redis channel name" }
        require((messageId == null) == (deduplicator == null)) {
            "Redis replay protection requires both a message-id extractor and a deduplicator"
        }
    }

    private val listener = ChannelListener { observedChannel, raw, origin ->
        if (observedChannel != channel) return@ChannelListener
        if (!originAllowed(origin)) {
            onRejected(RedisMessageRejection.ORIGIN_REJECTED)
            return@ChannelListener
        }
        val decoded = try {
            codec.decode(raw)
        } catch (_: RuntimeException) {
            onRejected(RedisMessageRejection.MALFORMED_PAYLOAD)
            return@ChannelListener
        }
        val extractedOrigin: String?
        val id: String?
        try {
            extractedOrigin = embeddedOrigin?.invoke(decoded)
            id = messageId?.invoke(decoded)
        } catch (_: RuntimeException) {
            onRejected(RedisMessageRejection.MALFORMED_PAYLOAD)
            return@ChannelListener
        }
        if (extractedOrigin != null && extractedOrigin != origin) {
            onRejected(RedisMessageRejection.EMBEDDED_ORIGIN_MISMATCH)
            return@ChannelListener
        }
        if (id != null) {
            val claim = try {
                deduplicator!!.claim(id, clockMillis())
            } catch (_: RuntimeException) {
                onRejected(RedisMessageRejection.MALFORMED_PAYLOAD)
                return@ChannelListener
            }
            when (claim) {
                MessageClaimResult.ACCEPTED -> Unit
                MessageClaimResult.DUPLICATE -> {
                    onRejected(RedisMessageRejection.DUPLICATE)
                    return@ChannelListener
                }
                MessageClaimResult.CAPACITY_EXCEEDED -> {
                    onRejected(RedisMessageRejection.REPLAY_GUARD_FULL)
                    return@ChannelListener
                }
            }
        }
        try {
            onMessage(decoded, origin)
        } catch (failure: Exception) {
            onHandlerFailure(failure)
        }
    }

    fun register() {
        check(registered.compareAndSet(false, true)) { "Redis bus is already registered" }
        try {
            redis.registerChannelUnique(channel, listener)
        } catch (failure: RuntimeException) {
            registered.set(false)
            throw failure
        }
    }

    fun publish(message: T) {
        redis.publish(channel, codec.encode(message))
    }

    override fun close() {
        if (registered.compareAndSet(true, false)) redis.unregisterChannel(channel, listener)
    }

    private companion object {
        val CHANNEL_PATTERN = Regex("[A-Za-z0-9:._-]{1,160}")
    }
}
