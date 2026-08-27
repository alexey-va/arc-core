package ru.arc.redis.network

import ru.arc.redis.RedisOperations
import ru.arc.redis.safety.OriginBoundRedisBus
import ru.arc.redis.safety.RecentMessageDeduplicator
import ru.arc.redis.safety.RedisMessageRejection
import ru.arc.redis.safety.RedisWireCodec
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_REPLAY_TTL_MS = 24L * 60L * 60L * 1_000L

/** Optional bounded replay protection for a validated Redis topic. */
class RedisReplayPolicy<T : Any>(
    val messageId: (T) -> String,
    val ttlMillis: Long,
    val maxEntries: Int,
) {
    init {
        require(ttlMillis in 1..MAX_REPLAY_TTL_MS) { "Redis replay TTL must be between 1 ms and 24 hours" }
        require(maxEntries in 1..1_000_000) { "Redis replay capacity must be between 1 and 1000000" }
    }
}

/**
 * Lifecycle-owning typed Redis pub/sub boundary.
 *
 * Opening a topic registers exactly one listener. Closing it unregisters that
 * listener idempotently. Wire validation, origin policy and optional replay
 * protection are delegated to [OriginBoundRedisBus]. The message handler
 * executes on the Redis delivery thread; platform consumers must marshal API
 * work onto their required scheduler.
 */
class ValidatedRedisTopic<T : Any> private constructor(
    private val bus: OriginBoundRedisBus<T>,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun publish(message: T) {
        check(!closed.get()) { "Validated Redis topic is closed" }
        bus.publish(message)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) bus.close()
    }

    companion object {
        @JvmStatic
        fun <T : Any> open(
            redis: RedisOperations,
            channel: String,
            codec: RedisWireCodec<T>,
            originAllowed: (String) -> Boolean,
            embeddedOrigin: ((T) -> String)? = null,
            replay: RedisReplayPolicy<T>? = null,
            clockMillis: () -> Long = System::currentTimeMillis,
            onMessage: (message: T, originServer: String) -> Unit,
            onRejected: (RedisMessageRejection) -> Unit = {},
            onHandlerFailure: (Throwable) -> Unit = {},
        ): ValidatedRedisTopic<T> {
            val bus = OriginBoundRedisBus(
                redis = redis,
                channel = channel,
                codec = codec,
                originAllowed = originAllowed,
                embeddedOrigin = embeddedOrigin,
                messageId = replay?.messageId,
                deduplicator = replay?.let { RecentMessageDeduplicator(it.ttlMillis, it.maxEntries) },
                clockMillis = clockMillis,
                onMessage = onMessage,
                onRejected = onRejected,
                onHandlerFailure = onHandlerFailure,
            )
            bus.register()
            return ValidatedRedisTopic(bus)
        }
    }
}
