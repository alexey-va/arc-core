package ru.arc.paper.api

import java.util.concurrent.TimeUnit

/**
 * Optional technical metrics sink registered by the ARC host.
 *
 * Metric names and tag keys use Prometheus-compatible syntax. Owner IDs and
 * tag values may contain bounded Unicode text, but must remain stable: never
 * use player names or IDs, request IDs, free text, or other unbounded values.
 * The provider caps owner and series counts and ignores invalid or over-limit
 * definitions. A missing provider or registry must be treated as a no-op.
 */
interface ArcMetricsService {
    /**
     * Opens the single active metric scope for a stable plugin/source ID.
     * Invalid, duplicate, or over-limit owners receive an inert scope.
     */
    fun open(ownerId: String): ArcMetricsScope
}

/**
 * Owns all meters declared by one consumer until [close]. Invalid or over-limit
 * metric definitions return inert handles instead of throwing.
 */
interface ArcMetricsScope : AutoCloseable {
    fun counter(name: String, description: String, tags: Map<String, String>): ArcMetricCounter

    fun gauge(name: String, description: String, tags: Map<String, String>): ArcMetricGauge

    fun timer(name: String, description: String, tags: Map<String, String>): ArcMetricTimer

    /** Removes only this owner's meters. Closing is idempotent. */
    override fun close()
}

interface ArcMetricCounter {
    fun increment()

    /** Non-positive and non-finite increments are ignored. */
    fun increment(amount: Double)
}

interface ArcMetricGauge {
    /** Non-finite values are ignored. */
    fun set(value: Double)

    fun value(): Double
}

interface ArcMetricTimer {
    /** Negative durations are ignored. */
    fun record(duration: Long, unit: TimeUnit)
}
