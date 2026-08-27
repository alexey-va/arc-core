package ru.arc.redis.network

import ru.arc.network.LeasedNetworkDirectory
import ru.arc.network.NetworkLeaseObservation
import ru.arc.network.NetworkLeaseRejectionReason
import ru.arc.redis.RedisOperations
import ru.arc.redis.safety.RedisWireCodec
import java.util.EnumMap
import java.util.concurrent.CompletableFuture

enum class RedisPresenceRejection {
    UNSAFE_FIELD,
    MALFORMED_PAYLOAD,
    ENTRY_ID_MISMATCH,
    ORIGIN_REJECTED,
    ENTRY_POLICY_REJECTED,
    CLOCK_ROLLBACK,
    EXPIRED,
    STALE_OBSERVATION,
    STALE_SEQUENCE,
    CAPACITY,
}

data class RedisPresenceRefresh<T : Any>(
    val values: List<T>,
    val rejected: Map<RedisPresenceRejection, Int>,
)

/**
 * Strict Redis-hash backed network presence with a bounded local lease view.
 *
 * Each hash field must equal the decoded entry id. Values from disallowed
 * origins or malformed wire payloads are rejected without exposing raw data.
 * Publish and refresh futures inherit the Redis operation completion thread
 * and have no platform thread affinity.
 */
class RedisPresenceDirectory<T : Any>(
    private val redis: RedisOperations,
    private val hashKey: String,
    private val codec: RedisWireCodec<T>,
    private val entryId: (T) -> String,
    private val origin: (T) -> String,
    private val observedAtMillis: (T) -> Long,
    private val sequence: ((T) -> Long?)? = null,
    private val originAllowed: (String) -> Boolean,
    private val entryAllowed: (T) -> Boolean = { true },
    leaseMillis: Long,
    private val maxEntries: Int,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val monitor = Any()
    private var directory = newDirectory(leaseMillis)
    private var closed = false

    init {
        require(hashKey.matches(HASH_KEY_PATTERN)) { "Unsafe Redis presence hash key" }
        require(maxEntries in 1..1_000_000) { "Redis presence capacity must be between 1 and 1000000" }
    }

    fun publish(value: T): CompletableFuture<Unit> {
        val encoded: String
        val id: String
        try {
            id = entryId(value)
            require(id.matches(ENTRY_ID_PATTERN)) { "Unsafe Redis presence entry id" }
            require(origin(value).matches(ORIGIN_PATTERN)) { "Unsafe Redis presence origin" }
            encoded = codec.encode(value)
        } catch (failure: RuntimeException) {
            return CompletableFuture.failedFuture(failure)
        }
        if (synchronized(monitor) { closed }) {
            return CompletableFuture.failedFuture(IllegalStateException("Redis presence directory is closed"))
        }
        return redis.saveMapEntries(hashKey, id, encoded).thenApply { Unit }
    }

    fun refresh(): CompletableFuture<RedisPresenceRefresh<T>> {
        if (synchronized(monitor) { closed }) {
            return CompletableFuture.failedFuture(IllegalStateException("Redis presence directory is closed"))
        }
        return redis.loadMap(hashKey).thenApply(::acceptLoaded)
    }

    fun snapshot(): List<T> = synchronized(monitor) {
        if (closed) emptyList() else directory.snapshot().map { it.value }
    }

    fun activeLeaseCount(): Int = synchronized(monitor) { if (closed) 0 else directory.size() }

    /** Policy changes clear old leases so entries are revalidated under the new TTL. */
    fun updateLeaseMillis(leaseMillis: Long) = synchronized(monitor) {
        check(!closed) { "Redis presence directory is closed" }
        directory = newDirectory(leaseMillis)
    }

    override fun close(): Unit = synchronized(monitor) {
        if (closed) return
        closed = true
        directory.clear()
    }

    private fun acceptLoaded(rawEntries: Map<String, String>): RedisPresenceRefresh<T> = synchronized(monitor) {
        check(!closed) { "Redis presence directory is closed" }
        val rejected = EnumMap<RedisPresenceRejection, Int>(RedisPresenceRejection::class.java)
        val entries = rawEntries.entries.sortedBy(Map.Entry<String, String>::key)
        if (entries.size > maxEntries) {
            rejected.increment(RedisPresenceRejection.CAPACITY, entries.size - maxEntries)
        }
        entries.take(maxEntries).forEach { (field, raw) ->
            if (!field.matches(ENTRY_ID_PATTERN)) {
                rejected.increment(RedisPresenceRejection.UNSAFE_FIELD)
                return@forEach
            }
            val value = try {
                codec.decode(raw)
            } catch (_: RuntimeException) {
                rejected.increment(RedisPresenceRejection.MALFORMED_PAYLOAD)
                return@forEach
            }
            val decodedId: String
            val decodedOrigin: String
            val observedAt: Long
            val decodedSequence: Long?
            try {
                decodedId = entryId(value)
                decodedOrigin = origin(value)
                observedAt = observedAtMillis(value)
                decodedSequence = sequence?.invoke(value)
            } catch (_: RuntimeException) {
                rejected.increment(RedisPresenceRejection.MALFORMED_PAYLOAD)
                return@forEach
            }
            if (decodedId != field) {
                rejected.increment(RedisPresenceRejection.ENTRY_ID_MISMATCH)
                return@forEach
            }
            if (!decodedOrigin.matches(ORIGIN_PATTERN) || !originAllowed(decodedOrigin)) {
                rejected.increment(RedisPresenceRejection.ORIGIN_REJECTED)
                return@forEach
            }
            val acceptedByPolicy = try {
                entryAllowed(value)
            } catch (_: RuntimeException) {
                false
            }
            if (!acceptedByPolicy) {
                rejected.increment(RedisPresenceRejection.ENTRY_POLICY_REJECTED)
                return@forEach
            }
            when (
                val observation = directory.observe(
                    key = decodedId,
                    origin = decodedOrigin,
                    value = value,
                    sequence = decodedSequence,
                    observedAtMillis = observedAt,
                )
            ) {
                is NetworkLeaseObservation.Accepted -> Unit
                is NetworkLeaseObservation.Rejected -> rejected.increment(observation.reason.toRedisReason())
            }
        }
        RedisPresenceRefresh(
            values = directory.snapshot().map { it.value },
            rejected = rejected.toMap(),
        )
    }

    private fun newDirectory(leaseMillis: Long) = LeasedNetworkDirectory<String, T>(
        leaseMillis = leaseMillis,
        maxEntries = maxEntries,
        clock = clockMillis,
    )

    private fun NetworkLeaseRejectionReason.toRedisReason(): RedisPresenceRejection = when (this) {
        NetworkLeaseRejectionReason.CLOCK_ROLLBACK -> RedisPresenceRejection.CLOCK_ROLLBACK
        NetworkLeaseRejectionReason.EXPIRED -> RedisPresenceRejection.EXPIRED
        NetworkLeaseRejectionReason.STALE_OBSERVATION -> RedisPresenceRejection.STALE_OBSERVATION
        NetworkLeaseRejectionReason.STALE_SEQUENCE -> RedisPresenceRejection.STALE_SEQUENCE
        NetworkLeaseRejectionReason.CAPACITY -> RedisPresenceRejection.CAPACITY
    }

    private fun MutableMap<RedisPresenceRejection, Int>.increment(reason: RedisPresenceRejection, count: Int = 1) {
        this[reason] = getOrDefault(reason, 0) + count
    }

    private companion object {
        val HASH_KEY_PATTERN = Regex("[A-Za-z0-9:._-]{1,160}")
        val ENTRY_ID_PATTERN = Regex("[A-Za-z0-9:._-]{1,160}")
        val ORIGIN_PATTERN = Regex("[A-Za-z0-9:._-]{1,128}")
    }
}
