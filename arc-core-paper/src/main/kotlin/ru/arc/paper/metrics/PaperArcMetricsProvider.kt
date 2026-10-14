package ru.arc.paper.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import ru.arc.paper.api.ArcMetricCounter
import ru.arc.paper.api.ArcMetricGauge
import ru.arc.paper.api.ArcMetricTimer
import ru.arc.paper.api.ArcMetricsScope
import ru.arc.paper.api.ArcMetricsService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * ARC's single Paper-side owner for consumer metrics. The host supplies the
 * active registry and calls [rebind] after a metrics reload. This provider
 * never closes that shared registry.
 *
 * Owner scopes and each scope's meter declarations are capped. Measurements
 * made without a registry are dropped; only the bounded meter definitions and
 * the latest gauge scalar survive so existing handles can bind after reload.
 */
class PaperArcMetricsProvider(initialRegistry: MeterRegistry?) : ArcMetricsService, AutoCloseable {
    private val lock = Any()
    private val scopes = linkedMapOf<String, OwnerScope>()
    private val activeOwners = AtomicInteger()
    private val warningBits = AtomicInteger()
    private var registry: MeterRegistry? = initialRegistry
    private var ownerCountMeter: Meter? = null
    private var activeMetricHandles = 0
    private var closed = false

    init {
        bindOwnerCountMeter(initialRegistry)
    }

    override fun open(ownerId: String): ArcMetricsScope = open(ownerId, DEFAULT_METRICS_PER_OWNER)

    override fun open(ownerId: String, maxMetrics: Int): ArcMetricsScope = synchronized(lock) {
        if (closed || !isOwnerId(ownerId) || scopes.size >= MAX_OWNERS || ownerId in scopes) {
            val bit = when {
                closed -> 0
                ownerId in scopes -> DUPLICATE_OWNER_WARNING
                scopes.size >= MAX_OWNERS -> OWNER_LIMIT_WARNING
                else -> INVALID_OWNER_WARNING
            }
            if (bit != 0) warnOnce(bit, "ARC metrics rejected an invalid or duplicate owner scope")
            return@synchronized RejectedScope
        }
        if (maxMetrics !in 1..MAX_METRICS_PER_OWNER) {
            warnOnce(INVALID_BUDGET_WARNING, "ARC metrics rejected an invalid owner series budget")
            return@synchronized RejectedScope
        }

        val scope = OwnerScope(ownerId, maxMetrics)
        scopes[ownerId] = scope
        activeOwners.incrementAndGet()
        scope.rebind(registry)
        scope
    }

    /**
     * Switches the meters owned by this provider to [nextRegistry]. Existing
     * handles remain live; counter and timer samples start fresh in the new
     * registry, while gauges retain their latest scalar value.
     */
    fun rebind(nextRegistry: MeterRegistry?) = synchronized(lock) {
        if (closed || registry === nextRegistry) return@synchronized

        ownerCountMeter?.let { registry?.remove(it) }
        ownerCountMeter = null
        registry = nextRegistry
        scopes.values.forEach { it.rebind(nextRegistry) }
        bindOwnerCountMeter(nextRegistry)
    }

    /** Number of active consumer scopes, for bounded diagnostics and tests. */
    internal fun activeOwnerCount(): Int = activeOwners.get()

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        scopes.values.forEach { it.closeFromProvider(registry) }
        scopes.clear()
        activeOwners.set(0)
        activeMetricHandles = 0
        ownerCountMeter?.let { registry?.remove(it) }
        ownerCountMeter = null
        registry = null
    }

    private fun closeScope(scope: OwnerScope) = synchronized(lock) {
        if (scopes[scope.ownerId] !== scope) return@synchronized
        scopes.remove(scope.ownerId)
        activeOwners.decrementAndGet()
        activeMetricHandles -= scope.registeredMetricCount()
        scope.closeFromProvider(registry)
    }

    private fun bindOwnerCountMeter(target: MeterRegistry?) {
        if (target == null || closed) return
        ownerCountMeter =
            try {
                Gauge.builder("arc_metrics_consumer_scopes", activeOwners) { count -> count.get().toDouble() }
                    .description("Active consumer metric scopes")
                    .register(target)
            } catch (_: RuntimeException) {
                warnOnce(REGISTRY_CONFLICT_WARNING, "ARC metrics could not bind its consumer-scope gauge")
                null
            }
    }

    private fun warnOnce(bit: Int, message: String) {
        while (true) {
            val observed = warningBits.get()
            if (observed and bit != 0) return
            if (warningBits.compareAndSet(observed, observed or bit)) {
                LOGGER.warning(message)
                return
            }
        }
    }

    private inner class OwnerScope(val ownerId: String, private val metricLimit: Int) : ArcMetricsScope {
        private val handles = linkedMapOf<MetricKey, MetricHandle>()
        private var isClosed = false

        override fun counter(name: String, description: String, tags: Map<String, String>): ArcMetricCounter =
            metric(MetricType.COUNTER, name, description, tags) as? ArcMetricCounter ?: NoOpCounter

        override fun gauge(name: String, description: String, tags: Map<String, String>): ArcMetricGauge =
            metric(MetricType.GAUGE, name, description, tags) as? ArcMetricGauge ?: NoOpGauge

        override fun timer(name: String, description: String, tags: Map<String, String>): ArcMetricTimer =
            metric(MetricType.TIMER, name, description, tags) as? ArcMetricTimer ?: NoOpTimer

        private fun metric(
            type: MetricType,
            name: String,
            description: String,
            inputTags: Map<String, String>,
        ): MetricHandle? = synchronized(lock) {
            if (closed || isClosed) return@synchronized null
            if (!isMetricName(name) || !isDescription(description) || !isValidTags(inputTags)) {
                warnOnce(INVALID_METRIC_WARNING, "ARC metrics skipped an invalid metric definition")
                return@synchronized null
            }

            val tags = inputTags.toSortedMap().map { it.key to it.value }
            val key = MetricKey(name, tags)
            val existing = handles[key]
            if (existing != null) {
                if (existing.type != type) {
                    warnOnce(TYPE_CONFLICT_WARNING, "ARC metrics skipped a metric name reused with another type")
                    return@synchronized null
                }
                return@synchronized existing
            }
            if (handles.size >= metricLimit) {
                warnOnce(METRIC_LIMIT_WARNING, "ARC metrics reached the per-owner meter limit")
                return@synchronized null
            }
            if (activeMetricHandles >= MAX_TOTAL_METRIC_HANDLES) {
                warnOnce(TOTAL_METRIC_LIMIT_WARNING, "ARC metrics reached the aggregate meter limit")
                return@synchronized null
            }

            val handle = when (type) {
                MetricType.COUNTER -> CounterHandle(ownerId, name, description, tags)
                MetricType.GAUGE -> GaugeHandle(ownerId, name, description, tags)
                MetricType.TIMER -> TimerHandle(ownerId, name, description, tags)
            }
            handles[key] = handle
            activeMetricHandles++
            handle.rebind(registry)
            handle
        }

        fun rebind(nextRegistry: MeterRegistry?) {
            if (isClosed) return
            handles.values.forEach { it.rebind(nextRegistry) }
        }

        fun registeredMetricCount(): Int = handles.size

        fun closeFromProvider(currentRegistry: MeterRegistry?) {
            if (isClosed) return
            isClosed = true
            handles.values.forEach { it.deactivate(currentRegistry) }
            handles.clear()
        }

        override fun close() = closeScope(this)
    }

    private abstract inner class MetricHandle(
        val type: MetricType,
        private val name: String,
        private val description: String,
        private val ownerId: String,
        private val tags: List<Pair<String, String>>,
    ) {
        private var currentRegistry: MeterRegistry? = null
        protected var meter: Meter? = null
        private var active = true

        @Synchronized
        fun rebind(nextRegistry: MeterRegistry?) {
            if (!active || currentRegistry === nextRegistry) return
            unregister()
            currentRegistry = nextRegistry
            if (nextRegistry != null) meter = register(nextRegistry)
        }

        @Synchronized
        fun deactivate(registryToRemoveFrom: MeterRegistry?) {
            if (!active) return
            active = false
            if (registryToRemoveFrom != null && meter != null) {
                registryToRemoveFrom.remove(meter!!)
            }
            meter = null
            currentRegistry = null
        }

        @Synchronized
        protected fun <T> withMeter(block: (Meter) -> T): T? {
            if (!active) return null
            return meter?.let(block)
        }

        @Synchronized
        protected fun whileActive(block: () -> Unit) {
            if (active) block()
        }

        private fun unregister() {
            val oldRegistry = currentRegistry
            val oldMeter = meter
            if (oldRegistry != null && oldMeter != null) oldRegistry.remove(oldMeter)
            meter = null
        }

        private fun register(target: MeterRegistry): Meter? =
            try {
                when (type) {
                    MetricType.COUNTER -> Counter.builder(name).description(description).tags(meterTags()).register(target)
                    MetricType.GAUGE ->
                        Gauge.builder(name, this) { value -> value.readGauge() }
                            .description(description)
                            .tags(meterTags())
                            .register(target)
                    MetricType.TIMER -> Timer.builder(name).description(description).tags(meterTags()).register(target)
                }
            } catch (_: RuntimeException) {
                warnOnce(REGISTRY_CONFLICT_WARNING, "ARC metrics skipped a definition rejected by the registry")
                null
            }

        protected open fun readGauge(): Double = 0.0

        private fun meterTags(): Tags {
            val all = arrayOfNulls<String>((tags.size + 1) * 2)
            all[0] = "owner"
            all[1] = ownerId
            tags.forEachIndexed { index, (key, value) ->
                all[(index + 1) * 2] = key
                all[(index + 1) * 2 + 1] = value
            }
            return Tags.of(*all.requireNoNulls())
        }
    }

    private inner class CounterHandle(
        ownerId: String,
        name: String,
        description: String,
        tags: List<Pair<String, String>>,
    ) : MetricHandle(MetricType.COUNTER, name, description, ownerId, tags), ArcMetricCounter {
        override fun increment() {
            increment(1.0)
        }

        override fun increment(amount: Double) {
            if (!amount.isFinite() || amount <= 0.0) return
            withMeter { (it as? Counter)?.increment(amount) }
        }
    }

    private inner class GaugeHandle(
        ownerId: String,
        name: String,
        description: String,
        tags: List<Pair<String, String>>,
    ) : MetricHandle(MetricType.GAUGE, name, description, ownerId, tags), ArcMetricGauge {
        private val valueBits = AtomicLong(java.lang.Double.doubleToRawLongBits(0.0))

        override fun set(value: Double) {
            if (!value.isFinite()) return
            whileActive {
                valueBits.set(java.lang.Double.doubleToRawLongBits(value))
            }
        }

        override fun value(): Double = java.lang.Double.longBitsToDouble(valueBits.get())

        override fun readGauge(): Double = value()
    }

    private inner class TimerHandle(
        ownerId: String,
        name: String,
        description: String,
        tags: List<Pair<String, String>>,
    ) : MetricHandle(MetricType.TIMER, name, description, ownerId, tags), ArcMetricTimer {
        override fun record(duration: Long, unit: TimeUnit) {
            if (duration < 0L) return
            withMeter { (it as? Timer)?.record(duration, unit) }
        }
    }

    private data class MetricKey(val name: String, val tags: List<Pair<String, String>>)

    private enum class MetricType {
        COUNTER,
        GAUGE,
        TIMER,
    }

    private object RejectedScope : ArcMetricsScope {
        override fun counter(name: String, description: String, tags: Map<String, String>): ArcMetricCounter = NoOpCounter
        override fun gauge(name: String, description: String, tags: Map<String, String>): ArcMetricGauge = NoOpGauge
        override fun timer(name: String, description: String, tags: Map<String, String>): ArcMetricTimer = NoOpTimer
        override fun close() = Unit
    }

    private object NoOpCounter : ArcMetricCounter {
        override fun increment() = Unit
        override fun increment(amount: Double) = Unit
    }

    private object NoOpGauge : ArcMetricGauge {
        override fun set(value: Double) = Unit
        override fun value(): Double = 0.0
    }

    private object NoOpTimer : ArcMetricTimer {
        override fun record(duration: Long, unit: TimeUnit) = Unit
    }

    private fun isValidTags(tags: Map<String, String>): Boolean =
        tags.size <= MAX_TAGS && tags.all { (key, value) ->
            key != "owner" && isTagKey(key) && isTagValue(value)
        }

    private companion object {
        const val MAX_OWNERS = 64
        const val DEFAULT_METRICS_PER_OWNER = 128
        const val MAX_METRICS_PER_OWNER = 4096
        const val MAX_TOTAL_METRIC_HANDLES = MAX_OWNERS * DEFAULT_METRICS_PER_OWNER
        const val MAX_TAGS = 8
        const val MAX_OWNER_LENGTH = 64
        const val MAX_METRIC_NAME_LENGTH = 100
        const val MAX_TAG_KEY_LENGTH = 40
        const val MAX_TAG_VALUE_LENGTH = 64
        const val MAX_DESCRIPTION_LENGTH = 160

        const val INVALID_OWNER_WARNING = 1
        const val DUPLICATE_OWNER_WARNING = 1 shl 1
        const val OWNER_LIMIT_WARNING = 1 shl 2
        const val INVALID_METRIC_WARNING = 1 shl 3
        const val TYPE_CONFLICT_WARNING = 1 shl 4
        const val METRIC_LIMIT_WARNING = 1 shl 5
        const val REGISTRY_CONFLICT_WARNING = 1 shl 6
        const val INVALID_BUDGET_WARNING = 1 shl 7
        const val TOTAL_METRIC_LIMIT_WARNING = 1 shl 8

        val LOGGER: Logger = Logger.getLogger(PaperArcMetricsProvider::class.java.name)

        fun isOwnerId(value: String): Boolean =
            value.isNotBlank() && isBoundedText(value, MAX_OWNER_LENGTH)

        fun isMetricName(value: String): Boolean =
            value.length in 1..MAX_METRIC_NAME_LENGTH &&
                isPrometheusMetricStart(value[0]) && value.all(::isPrometheusMetricPart)

        fun isTagKey(value: String): Boolean {
            if (value.length !in 1..MAX_TAG_KEY_LENGTH) return false
            if (value.startsWith("__") || value.startsWith("._") || value.startsWith("..") || value.startsWith("_.")) return false
            return isPrometheusLabelStart(value[0]) && value.all(::isPrometheusLabelPart)
        }

        fun isTagValue(value: String): Boolean =
            value.length <= MAX_TAG_VALUE_LENGTH && value.none { it.isISOControl() }

        fun isDescription(value: String): Boolean =
            value.isNotBlank() && value.length <= MAX_DESCRIPTION_LENGTH && value.none { it.isISOControl() }

        private fun isBoundedText(value: String, maxLength: Int): Boolean =
            value.length in 1..maxLength && value.none { it.isISOControl() }

        private fun isPrometheusMetricStart(char: Char): Boolean = isAsciiLetter(char) || char == '_' || char == '.' || char == ':'

        private fun isPrometheusMetricPart(char: Char): Boolean =
            isAsciiLetter(char) || char in '0'..'9' || char == '_' || char == '.' || char == ':'

        private fun isPrometheusLabelStart(char: Char): Boolean = isAsciiLetter(char) || char == '_' || char == '.'

        private fun isPrometheusLabelPart(char: Char): Boolean =
            isAsciiLetter(char) || char in '0'..'9' || char == '_' || char == '.'

        private fun isAsciiLetter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'
    }
}
