package ru.arc.network

import java.util.LinkedHashMap

/** One locally observed, expiring network entry. */
data class NetworkLeaseEntry<K : Any, V : Any>(
    val key: K,
    val origin: String,
    val value: V,
    val sequence: Long?,
    val observedAtMillis: Long,
    val expiresAtMillis: Long,
)

enum class NetworkLeaseRejectionReason {
    CLOCK_ROLLBACK,
    EXPIRED,
    STALE_OBSERVATION,
    STALE_SEQUENCE,
    CAPACITY,
}

/** Typed result of observing one network lease. */
sealed interface NetworkLeaseObservation<out K : Any, out V : Any> {
    data class Accepted<K : Any, V : Any>(
        val entry: NetworkLeaseEntry<K, V>,
        val replaced: Boolean,
    ) : NetworkLeaseObservation<K, V>

    data class Rejected(
        val reason: NetworkLeaseRejectionReason,
    ) : NetworkLeaseObservation<Nothing, Nothing>
}

/**
 * Bounded, thread-safe local view of expiring network presence.
 *
 * Transport authentication, wire decoding and origin allowlisting remain with
 * the caller (normally [ru.arc.redis.safety.OriginBoundRedisBus]). This class
 * owns only local lease replacement, optional monotonic sequence checks,
 * capacity and wall-clock rollback behavior. A rollback clears every entry and
 * rejects that observation so routing fails closed until a fresh heartbeat.
 *
 * [leaseMillis] is intentionally policy-light: it only has to be positive.
 * Product configuration remains free to choose its own useful heartbeat/TTL.
 */
class LeasedNetworkDirectory<K : Any, V : Any>(
    private val leaseMillis: Long,
    private val maxEntries: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val monitor = Any()
    private val entries = LinkedHashMap<K, NetworkLeaseEntry<K, V>>()
    private var lastObservedClock: Long? = null

    init {
        require(leaseMillis > 0L) { "Network lease duration must be positive" }
        require(maxEntries in 1..1_000_000) { "Network lease capacity must be between 1 and 1000000" }
    }

    fun observe(
        key: K,
        origin: String,
        value: V,
        sequence: Long? = null,
        observedAtMillis: Long? = null,
    ): NetworkLeaseObservation<K, V> = synchronized(monitor) {
        validateOrigin(origin)
        sequence?.let { require(it >= 0L) { "Network lease sequence must not be negative" } }
        val now = observeClockLocked()
            ?: return@synchronized NetworkLeaseObservation.Rejected(NetworkLeaseRejectionReason.CLOCK_ROLLBACK)
        removeExpiredLocked(now)
        val observedAt = observedAtMillis ?: now
        require(observedAt >= 0L) { "Network lease observation time must not be negative" }
        val expiresAt = saturatingAdd(observedAt, leaseMillis)
        if (now >= expiresAt) {
            return@synchronized NetworkLeaseObservation.Rejected(NetworkLeaseRejectionReason.EXPIRED)
        }
        val previous = entries[key]
        if (previous != null && observedAt < previous.observedAtMillis) {
            return@synchronized NetworkLeaseObservation.Rejected(NetworkLeaseRejectionReason.STALE_OBSERVATION)
        }
        if (sequence != null && previous?.sequence != null && sequence < previous.sequence) {
            return@synchronized NetworkLeaseObservation.Rejected(NetworkLeaseRejectionReason.STALE_SEQUENCE)
        }
        if (previous == null && entries.size >= maxEntries) {
            return@synchronized NetworkLeaseObservation.Rejected(NetworkLeaseRejectionReason.CAPACITY)
        }
        val entry = NetworkLeaseEntry(
            key = key,
            origin = origin,
            value = value,
            sequence = sequence,
            observedAtMillis = observedAt,
            expiresAtMillis = expiresAt,
        )
        entries[key] = entry
        NetworkLeaseObservation.Accepted(entry, replaced = previous != null)
    }

    fun get(key: K): NetworkLeaseEntry<K, V>? = synchronized(monitor) {
        val now = observeClockLocked() ?: return@synchronized null
        removeExpiredLocked(now)
        entries[key]
    }

    /** Returns an insertion-ordered active snapshot and prunes expired entries. */
    fun snapshot(): List<NetworkLeaseEntry<K, V>> = synchronized(monitor) {
        val now = observeClockLocked() ?: return@synchronized emptyList()
        removeExpiredLocked(now)
        entries.values.toList()
    }

    /** Returns the keys removed at this clock observation, in stable insertion order. */
    fun expire(): List<K> = synchronized(monitor) {
        val now = observeClockLocked() ?: return@synchronized emptyList()
        removeExpiredLocked(now)
    }

    fun remove(key: K): NetworkLeaseEntry<K, V>? = synchronized(monitor) { entries.remove(key) }

    fun clear() = synchronized(monitor) { entries.clear() }

    fun size(): Int = synchronized(monitor) { entries.size }

    private fun observeClockLocked(): Long? {
        val now = clock()
        require(now >= 0L) { "Network lease clock must not be negative" }
        val previous = lastObservedClock
        lastObservedClock = now
        if (previous != null && now < previous) {
            entries.clear()
            return null
        }
        return now
    }

    private fun removeExpiredLocked(now: Long): List<K> {
        val expired = entries.values.filter { now >= it.expiresAtMillis }.map(NetworkLeaseEntry<K, V>::key)
        expired.forEach(entries::remove)
        return expired
    }

    private fun validateOrigin(origin: String) {
        require(origin.length in 1..128 && origin.none(Char::isISOControl)) {
            "Network lease origin must be bounded visible text"
        }
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
