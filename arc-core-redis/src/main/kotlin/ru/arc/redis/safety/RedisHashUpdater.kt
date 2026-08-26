package ru.arc.redis.safety

import ru.arc.redis.RedisOperations
import java.util.concurrent.CompletableFuture

sealed interface RedisHashDecision<out T> {
    data class Write<T>(val value: T) : RedisHashDecision<T>
    data object Delete : RedisHashDecision<Nothing>
    data object Reject : RedisHashDecision<Nothing>
}

sealed interface RedisHashUpdateResult<out T> {
    data class Changed<T>(val before: T?, val after: T?) : RedisHashUpdateResult<T>
    data class Unchanged<T>(val current: T) : RedisHashUpdateResult<T>
    data class Rejected<T>(val current: T?) : RedisHashUpdateResult<T>
    data class Contended(val attempts: Int) : RedisHashUpdateResult<Nothing>
}

sealed interface RedisHashConsumeResult<out T> {
    data class Consumed<T>(val value: T) : RedisHashConsumeResult<T>
    data class Rejected<T>(val current: T?) : RedisHashConsumeResult<T>
    data class Contended(val attempts: Int) : RedisHashConsumeResult<Nothing>
}

/**
 * Bounded optimistic transformation of one Redis hash field.
 *
 * Corrupt stored values fail the returned future and are never treated as
 * missing. The transformer may run more than once and must be side-effect free.
 */
class RedisHashUpdater<T : Any>(
    private val redis: RedisOperations,
    private val hashKey: String,
    private val codec: BoundedJsonCodec<T>,
    private val maxAttempts: Int = 12,
) {
    init {
        validateRedisToken(hashKey, "hash key")
        require(maxAttempts in 1..64) { "Redis CAS attempt limit must be between 1 and 64" }
    }

    fun update(
        field: String,
        transform: (T?) -> RedisHashDecision<T>,
    ): CompletableFuture<RedisHashUpdateResult<T>> {
        validateRedisToken(field, "hash field")
        return attempt(field, transform, 1)
    }

    fun consume(
        field: String,
        predicate: (T) -> Boolean = { true },
    ): CompletableFuture<RedisHashConsumeResult<T>> =
        update(field) { current ->
            when {
                current == null -> RedisHashDecision.Reject
                predicate(current) -> RedisHashDecision.Delete
                else -> RedisHashDecision.Reject
            }
        }.thenApply { result ->
            when (result) {
                is RedisHashUpdateResult.Changed -> RedisHashConsumeResult.Consumed(requireNotNull(result.before))
                is RedisHashUpdateResult.Rejected -> RedisHashConsumeResult.Rejected(result.current)
                is RedisHashUpdateResult.Unchanged -> RedisHashConsumeResult.Rejected(result.current)
                is RedisHashUpdateResult.Contended -> RedisHashConsumeResult.Contended(result.attempts)
            }
        }

    private fun attempt(
        field: String,
        transform: (T?) -> RedisHashDecision<T>,
        attemptNumber: Int,
    ): CompletableFuture<RedisHashUpdateResult<T>> {
        if (attemptNumber > maxAttempts) {
            return CompletableFuture.completedFuture(RedisHashUpdateResult.Contended(maxAttempts))
        }
        return redis.loadMapEntries(hashKey, field).thenCompose { values ->
            require(values.size == 1) { "Redis hash lookup returned an unexpected result count" }
            val beforeRaw = values.single()
            val before = beforeRaw?.let(codec::decode)
            when (val decision = transform(before)) {
                RedisHashDecision.Reject -> CompletableFuture.completedFuture(RedisHashUpdateResult.Rejected(before))
                RedisHashDecision.Delete -> {
                    if (beforeRaw == null) return@thenCompose CompletableFuture.completedFuture(RedisHashUpdateResult.Rejected(null))
                    compareAndContinue(field, beforeRaw, null, before, null, transform, attemptNumber)
                }
                is RedisHashDecision.Write -> {
                    val afterRaw = codec.encode(decision.value)
                    if (afterRaw == beforeRaw && before != null) {
                        CompletableFuture.completedFuture(RedisHashUpdateResult.Unchanged(before))
                    } else {
                        compareAndContinue(field, beforeRaw, afterRaw, before, decision.value, transform, attemptNumber)
                    }
                }
            }
        }
    }

    private fun compareAndContinue(
        field: String,
        beforeRaw: String?,
        afterRaw: String?,
        before: T?,
        after: T?,
        transform: (T?) -> RedisHashDecision<T>,
        attemptNumber: Int,
    ): CompletableFuture<RedisHashUpdateResult<T>> =
        redis.compareAndSetMapEntry(hashKey, field, beforeRaw, afterRaw).thenCompose { changed ->
            if (changed) CompletableFuture.completedFuture(RedisHashUpdateResult.Changed(before, after))
            else attempt(field, transform, attemptNumber + 1)
        }

    private fun validateRedisToken(value: String, label: String) {
        require(value.length in 1..256) { "Redis $label must contain 1 to 256 characters" }
        require(value.none(Char::isISOControl)) { "Redis $label must not contain control characters" }
    }
}
