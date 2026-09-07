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
    /** Guarded by this instance monitor, like [sourceKeys] mutations. */
    private val activeKeys = HashSet<Key>()
    private val activeKeyOwners = HashMap<Key, Int>()

    data class Stats(
        val totalSeries: Int,
        val activeSeries: Int,
        val staleSeries: Int,
        val sources: Int,
    )

    fun applySnapshot(
        source: String,
        points: Collection<MetricPoint>,
    ) {
        val nextKeys = LinkedHashSet<Key>(points.size)
        val updates = ArrayList<Pair<AtomicReference<Double>, Double>>(points.size)
        for (point in points) {
            require(METRIC_NAME.matches(point.name)) { "Invalid metric name: ${point.name}" }
            val sortedTags = point.tags.entries.sortedBy { it.key }.map { it.key to it.value }
            val key = Key(point.name, sortedTags)
            val state =
                values.computeIfAbsent(key) {
                    val newState = AtomicReference(0.0)
                    Gauge
                        .builder(point.name, newState) { it.get() }
                        .description(point.description)
                        .tags(sortedTags.map { (name, value) -> Tag.of(name, value) })
                        .register(registry)
                    newState
                }
            updates += state to point.value
            nextKeys += key
        }

        // Registering new series can be expensive. Keep it outside the lock
        // shared by fast snapshots and stats; publish values and ownership together.
        synchronized(this) {
            updates.forEach { (state, value) -> state.set(value) }
            val previousKeys = sourceKeys.put(source, nextKeys).orEmpty()
            previousKeys.forEach { key ->
                if (key !in nextKeys) {
                    removeActiveKey(key)
                    if (key !in activeKeys) values[key]?.set(0.0)
                }
            }
            nextKeys.forEach { key ->
                if (key !in previousKeys) addActiveKey(key)
            }
        }
    }

    private fun addActiveKey(key: Key) {
        activeKeyOwners[key] = (activeKeyOwners[key] ?: 0) + 1
        activeKeys += key
    }

    private fun removeActiveKey(key: Key) {
        val owners = (activeKeyOwners[key] ?: return) - 1
        if (owners == 0) {
            activeKeyOwners.remove(key)
            activeKeys.remove(key)
        } else {
            activeKeyOwners[key] = owners
        }
    }

    @Synchronized
    fun clearSource(source: String) {
        sourceKeys.remove(source)?.forEach { key ->
            removeActiveKey(key)
            if (key !in activeKeys) values[key]?.set(0.0)
        }
    }

    fun value(
        name: String,
        tags: Map<String, String> = emptyMap(),
    ): Double? {
        val key = Key(name, tags.entries.sortedBy { it.key }.map { it.key to it.value })
        return values[key]?.get()
    }

    @Synchronized
    fun stats(): Stats {
        return Stats(
            totalSeries = values.size,
            activeSeries = activeKeys.size,
            staleSeries = (values.size - activeKeys.size).coerceAtLeast(0),
            sources = sourceKeys.size,
        )
    }

    private companion object {
        val METRIC_NAME = Regex("[a-zA-Z_:][a-zA-Z0-9_:]*")
    }
}
