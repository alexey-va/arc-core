package ru.arc.metrics.core

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

data class MetricPoint(
    val name: String,
    val description: String,
    val value: Double,
    val tags: Map<String, String> = emptyMap(),
)

/**
 * Strongly retains gauge state and updates it only from scheduled snapshots.
 * Prometheus scrapes therefore never call Paper, Velocity, filesystem, or MXBean APIs.
 */
class CachedGaugeStore(
    private val registry: MeterRegistry,
) {
    private data class Key(
        val name: String,
        val tags: List<Pair<String, String>>,
    )

    private val values = ConcurrentHashMap<Key, AtomicReference<Double>>()
    private val sourceKeys = ConcurrentHashMap<String, Set<Key>>()

    data class Stats(
        val totalSeries: Int,
        val activeSeries: Int,
        val staleSeries: Int,
        val sources: Int,
    )

    @Synchronized
    fun applySnapshot(
        source: String,
        points: Collection<MetricPoint>,
    ) {
        val nextKeys = LinkedHashSet<Key>(points.size)
        for (point in points) {
            require(METRIC_NAME.matches(point.name)) { "Invalid metric name: ${point.name}" }
            val sortedTags = point.tags.entries.sortedBy { it.key }.map { it.key to it.value }
            val key = Key(point.name, sortedTags)
            val state =
                values.computeIfAbsent(key) {
                    val newState = AtomicReference(point.value)
                    Gauge
                        .builder(point.name, newState) { it.get() }
                        .description(point.description)
                        .tags(sortedTags.map { (name, value) -> Tag.of(name, value) })
                        .register(registry)
                    newState
                }
            state.set(point.value)
            nextKeys += key
        }

        sourceKeys.put(source, nextKeys)?.minus(nextKeys)?.forEach { stale ->
            values[stale]?.set(0.0)
        }
    }

    @Synchronized
    fun clearSource(source: String) {
        sourceKeys.remove(source)?.forEach { key -> values[key]?.set(0.0) }
    }

    fun value(
        name: String,
        tags: Map<String, String> = emptyMap(),
    ): Double? {
        val key = Key(name, tags.entries.sortedBy { it.key }.map { it.key to it.value })
        return values[key]?.get()
    }

    fun stats(): Stats {
        val active = sourceKeys.values.asSequence().flatten().toSet().size
        return Stats(
            totalSeries = values.size,
            activeSeries = active,
            staleSeries = (values.size - active).coerceAtLeast(0),
            sources = sourceKeys.size,
        )
    }

    private companion object {
        val METRIC_NAME = Regex("[a-zA-Z_:][a-zA-Z0-9_:]*")
    }
}
