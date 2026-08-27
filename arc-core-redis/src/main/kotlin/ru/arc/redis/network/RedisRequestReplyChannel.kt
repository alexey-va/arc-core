package ru.arc.redis.network

import ru.arc.core.Tasks
import ru.arc.redis.RedisOperations
import ru.arc.redis.safety.RedisMessageRejection
import ru.arc.redis.safety.RedisWireCodec
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture

fun interface RedisRequestTimeoutHandle {
    fun cancel()
}

fun interface RedisRequestTimeoutScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): RedisRequestTimeoutHandle
}

/** Uses the installed ARC scheduler without exposing scheduler types in the public channel API. */
object ArcTaskRedisRequestTimeoutScheduler : RedisRequestTimeoutScheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): RedisRequestTimeoutHandle {
        require(delayMillis > 0L) { "Redis request timeout must be positive" }
        val delayTicks = ((delayMillis - 1L) / 50L) + 1L
        val task = Tasks.scheduler.runLaterAsync(delayTicks, Runnable(action))
        return RedisRequestTimeoutHandle(task::cancel)
    }
}

enum class RedisRequestFailurePhase {
    SCHEDULE_TIMEOUT,
    PUBLISH,
}

sealed interface RedisRequestResult<out T : Any> {
    data class Reply<T : Any>(val message: T, val originServer: String) : RedisRequestResult<T>
    data object TimedOut : RedisRequestResult<Nothing>
    data object CapacityExceeded : RedisRequestResult<Nothing>
    data object DuplicateRequestId : RedisRequestResult<Nothing>
    data object InvalidRequestId : RedisRequestResult<Nothing>
    data object Closed : RedisRequestResult<Nothing>
    data class InfrastructureFailure(
        val phase: RedisRequestFailurePhase,
        val cause: Throwable,
    ) : RedisRequestResult<Nothing>
}

enum class RedisReplyRejection {
    INVALID_REPLY_TO,
    UNMATCHED_REPLY,
    REPLY_POLICY_REJECTED,
}

/**
 * Bounded request/reply correlation over one validated Redis topic.
 *
 * Messages without `replyTo` are delivered to [onMessage]. Replies can only
 * complete an exact pending request and must pass [replyAllowed]. Unmatched or
 * spoofed replies never consume pending state. Returned futures have no thread
 * affinity: they can complete on the Redis delivery thread, timeout scheduler,
 * publishing caller, or closing caller. Platform work must be marshalled by
 * the consumer.
 */
class RedisRequestReplyChannel<T : Any>(
    redis: RedisOperations,
    channel: String,
    codec: RedisWireCodec<T>,
    originAllowed: (String) -> Boolean,
    private val requestId: (T) -> String,
    private val replyTo: (T) -> String?,
    private val replyAllowed: (request: T, reply: T, originServer: String) -> Boolean,
    private val timeoutMillis: Long,
    private val maxPending: Int,
    private val timeoutScheduler: RedisRequestTimeoutScheduler = ArcTaskRedisRequestTimeoutScheduler,
    embeddedOrigin: ((T) -> String)? = null,
    replay: RedisReplayPolicy<T>? = null,
    clockMillis: () -> Long = System::currentTimeMillis,
    private val onMessage: (message: T, originServer: String) -> Unit,
    onRejected: (RedisMessageRejection) -> Unit = {},
    private val onReplyRejected: (RedisReplyRejection) -> Unit = {},
    onHandlerFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val monitor = Any()
    private val pending = LinkedHashMap<String, Pending<T>>()
    private var closed = false
    private val topic: ValidatedRedisTopic<T>

    init {
        require(timeoutMillis in 1..MAX_REQUEST_TIMEOUT_MS) {
            "Redis request timeout must be between 1 ms and 24 hours"
        }
        require(maxPending in 1..1_000_000) { "Redis pending capacity must be between 1 and 1000000" }
        topic = ValidatedRedisTopic.open(
            redis = redis,
            channel = channel,
            codec = codec,
            originAllowed = originAllowed,
            embeddedOrigin = embeddedOrigin,
            replay = replay,
            clockMillis = clockMillis,
            onMessage = ::receive,
            onRejected = onRejected,
            onHandlerFailure = onHandlerFailure,
        )
    }

    fun request(message: T): CompletableFuture<RedisRequestResult<T>> {
        val id = try {
            requestId(message)
        } catch (_: RuntimeException) {
            return CompletableFuture.completedFuture(RedisRequestResult.InvalidRequestId)
        }
        if (!id.matches(REQUEST_ID_PATTERN)) {
            return CompletableFuture.completedFuture(RedisRequestResult.InvalidRequestId)
        }

        val future = CompletableFuture<RedisRequestResult<T>>()
        val entry = Pending(message, future)
        val immediate = synchronized(monitor) {
            when {
                closed -> RedisRequestResult.Closed
                pending.containsKey(id) -> RedisRequestResult.DuplicateRequestId
                pending.size >= maxPending -> RedisRequestResult.CapacityExceeded
                else -> {
                    pending[id] = entry
                    null
                }
            }
        }
        if (immediate != null) {
            future.complete(immediate)
            return future
        }

        val timeoutHandle = try {
            timeoutScheduler.schedule(timeoutMillis) { timeOut(id, entry) }
        } catch (failure: RuntimeException) {
            failPending(id, entry, RedisRequestResult.InfrastructureFailure(RedisRequestFailurePhase.SCHEDULE_TIMEOUT, failure))
            return future
        }
        val stillPending = synchronized(monitor) {
            if (pending[id] === entry) {
                entry.timeout = timeoutHandle
                true
            } else {
                false
            }
        }
        if (!stillPending) {
            timeoutHandle.cancel()
            return future
        }

        try {
            topic.publish(message)
        } catch (failure: RuntimeException) {
            failPending(id, entry, RedisRequestResult.InfrastructureFailure(RedisRequestFailurePhase.PUBLISH, failure))
        }
        return future
    }

    fun publish(message: T) {
        check(synchronized(monitor) { !closed }) { "Redis request/reply channel is closed" }
        topic.publish(message)
    }

    fun pendingCount(): Int = synchronized(monitor) { pending.size }

    override fun close() {
        val abandoned = synchronized(monitor) {
            if (closed) return
            closed = true
            pending.values.toList().also { pending.clear() }
        }
        topic.close()
        abandoned.forEach { entry ->
            entry.timeout?.cancel()
            entry.future.complete(RedisRequestResult.Closed)
        }
    }

    private fun receive(message: T, originServer: String) {
        val correlationId = try {
            replyTo(message)
        } catch (_: RuntimeException) {
            onReplyRejected(RedisReplyRejection.INVALID_REPLY_TO)
            return
        }
        if (correlationId == null) {
            onMessage(message, originServer)
            return
        }
        if (!correlationId.matches(REQUEST_ID_PATTERN)) {
            onReplyRejected(RedisReplyRejection.INVALID_REPLY_TO)
            return
        }

        val entry = synchronized(monitor) { pending[correlationId] }
        if (entry == null) {
            onReplyRejected(RedisReplyRejection.UNMATCHED_REPLY)
            return
        }
        val allowed = try {
            replyAllowed(entry.request, message, originServer)
        } catch (_: RuntimeException) {
            false
        }
        if (!allowed) {
            onReplyRejected(RedisReplyRejection.REPLY_POLICY_REJECTED)
            return
        }
        val accepted = synchronized(monitor) {
            if (pending[correlationId] === entry) {
                pending.remove(correlationId)
                true
            } else {
                false
            }
        }
        if (!accepted) {
            onReplyRejected(RedisReplyRejection.UNMATCHED_REPLY)
            return
        }
        entry.timeout?.cancel()
        entry.future.complete(RedisRequestResult.Reply(message, originServer))
    }

    private fun timeOut(id: String, entry: Pending<T>) {
        val removed = synchronized(monitor) {
            if (pending[id] === entry) {
                pending.remove(id)
                true
            } else {
                false
            }
        }
        if (removed) entry.future.complete(RedisRequestResult.TimedOut)
    }

    private fun failPending(id: String, entry: Pending<T>, result: RedisRequestResult<T>) {
        val removed = synchronized(monitor) {
            if (pending[id] === entry) {
                pending.remove(id)
                true
            } else {
                false
            }
        }
        if (removed) {
            entry.timeout?.cancel()
            entry.future.complete(result)
        }
    }

    private class Pending<T : Any>(
        val request: T,
        val future: CompletableFuture<RedisRequestResult<T>>,
        var timeout: RedisRequestTimeoutHandle? = null,
    )

    private companion object {
        const val MAX_REQUEST_TIMEOUT_MS = 24L * 60L * 60L * 1_000L
        val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9:._-]{1,160}")
    }
}
