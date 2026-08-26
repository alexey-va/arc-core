package ru.arc.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/** Mutable thread-safe clock for deterministic expiry, retry and rollback tests. */
class DeterministicClock private constructor(
    initialMillis: Long,
    private val zone: ZoneId,
) : Clock() {
    private val currentMillis = AtomicLong(initialMillis)

    init {
        require(initialMillis >= 0L) { "Deterministic clock must not start before the epoch" }
    }

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = DeterministicClock(millis(), zone)

    override fun instant(): Instant = Instant.ofEpochMilli(millis())

    override fun millis(): Long = currentMillis.get()

    fun set(instant: Instant) {
        val millis = instant.toEpochMilli()
        require(millis >= 0L) { "Deterministic clock must not move before the epoch" }
        currentMillis.set(millis)
    }

    /** May be negative so rollback behavior can be tested explicitly. */
    fun advance(duration: Duration): Instant {
        val delta = duration.toMillis()
        val updated = currentMillis.updateAndGet { previous ->
            Math.addExact(previous, delta).also {
                require(it >= 0L) { "Deterministic clock must not move before the epoch" }
            }
        }
        return Instant.ofEpochMilli(updated)
    }

    companion object {
        @JvmStatic
        fun at(instant: Instant, zone: ZoneId = ZoneOffset.UTC): DeterministicClock =
            DeterministicClock(instant.toEpochMilli(), zone)

        @JvmStatic
        fun atMillis(millis: Long, zone: ZoneId = ZoneOffset.UTC): DeterministicClock =
            DeterministicClock(millis, zone)
    }
}
