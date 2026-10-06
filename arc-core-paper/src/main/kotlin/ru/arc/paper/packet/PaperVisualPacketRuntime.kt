package ru.arc.paper.packet

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.metrics.core.MetricPoint
import ru.arc.observability.StructuredDebugLine
import ru.arc.paper.api.ArcVisualPacketBudget
import ru.arc.paper.api.VisualPacketAdmission
import java.util.WeakHashMap
import kotlin.math.round

/**
 * ARC-owned admission provider for replaceable visual packets. It is deliberately
 * independent of Bukkit player state: callers pass a connection identity and the
 * exact encoded, pre-compression payload size.
 */
class PaperVisualPacketRuntime internal constructor(
    initialSettings: Settings,
    private val nanoTime: () -> Long = System::nanoTime,
    private val hostPlugin: Plugin? = null,
) : ArcVisualPacketBudget, AutoCloseable {
    private val monitor = Any()
    private var settings = initialSettings
    private val serverBucket = TokenBucket(initialSettings.serverRateBitsPerSecond, initialSettings.serverCapacityBits, nanoTime())
    private val viewerBuckets = WeakHashMap<Any, TokenBucket>()
    private val sources = LinkedHashMap<String, SourceCounters>()
    private var closed = false
    private var overloaded = false
    private var lastReportNanos = nanoTime()
    private val taskScope = hostPlugin?.let { LifecycleTaskScope() }
    private val debugLine = StructuredDebugLine("ARC_VISUAL_PACKETS", maxValueCharacters = 1_024)

    init {
        // Keep the overflow bucket within the fixed cardinality budget.
        sources[OVERFLOW_SOURCE] = SourceCounters()
    }

    override fun acquire(
        source: String,
        connection: Any,
        bytes: Int,
        packets: Int,
        cleanup: Boolean,
        writable: Boolean,
    ): VisualPacketAdmission {
        require(bytes >= 0) { "Encoded visual payload size cannot be negative" }
        require(packets >= 0) { "Visual packet count cannot be negative" }
        synchronized(monitor) {
            val sourceCounters = countersFor(source)
            sourceCounters.total.attempts++
            sourceCounters.interval.attempts++

            if (cleanup && !writable) return deferred(sourceCounters, VisualPacketAdmission.CHANNEL_BACKPRESSURE)
            if (!cleanup && closed) return deferred(sourceCounters, VisualPacketAdmission.CLOSED)
            if (!writable) return deferred(sourceCounters, VisualPacketAdmission.CHANNEL_BACKPRESSURE)

            val now = nanoTime()
            serverBucket.refill(now)
            val viewerBucket = viewerBuckets.getOrPut(connection) {
                TokenBucket(settings.viewerRateBitsPerSecond, settings.viewerCapacityBits, now)
            }
            viewerBucket.refill(now)
            val costBits = bytes.toLong() * BITS_PER_BYTE

            if (cleanup) {
                // Cleanup may not be delayed behind a rate budget, but it still
                // consumes its real cost so ordinary updates repay that traffic.
                serverBucket.charge(costBits)
                viewerBucket.charge(costBits)
                sourceCounters.admit(cleanup = true)
                return VisualPacketAdmission.ALLOWED
            }

            val result = when {
                !serverBucket.canSpend(costBits) -> VisualPacketAdmission.SERVER_RATE
                !viewerBucket.canSpend(costBits) -> VisualPacketAdmission.VIEWER_RATE
                else -> null
            }
            if (result != null) return deferred(sourceCounters, result)

            serverBucket.charge(costBits)
            viewerBucket.charge(costBits)
            sourceCounters.admit(cleanup = false)
            return VisualPacketAdmission.ALLOWED
        }
    }

    /** Records payloads actually handed to the transport, including cleanup. */
    override fun recordSent(source: String, bytes: Int, packets: Int, cleanup: Boolean) {
        require(bytes >= 0) { "Encoded visual payload size cannot be negative" }
        require(packets >= 0) { "Visual packet count cannot be negative" }
        synchronized(monitor) {
            val sourceCounters = countersFor(source)
            sourceCounters.total.recordSent(bytes, packets, cleanup)
            sourceCounters.interval.recordSent(bytes, packets, cleanup)
        }
    }

    /**
     * Atomically applies a complete, validated candidate. Existing bucket balances
     * are refilled at the old rates first and then retained; changing rates cannot
     * grant a fresh burst.
     */
    fun reload(config: Config) {
        // Config turns an unreadable/malformed YAML document into an empty map.
        // Bootstrap may use defaults, but reload must not silently reset live limits.
        require(listOf("server-mbps", "viewer-mbps", "burst-ms", "log-interval-seconds", "verbose-logs").any(config::exists)) {
            "No visual packet settings were loaded; keep at least one explicit setting"
        }
        updateSettings(Settings.from(config))
    }

    fun updateSettings(candidate: Settings) {
        synchronized(monitor) {
            check(!closed) { "Visual packet runtime is closed" }
            val previous = settings
            val now = nanoTime()
            serverBucket.update(candidate.serverRateBitsPerSecond, candidate.serverCapacityBits, now)
            viewerBuckets.values.forEach { bucket ->
                bucket.update(candidate.viewerRateBitsPerSecond, candidate.viewerCapacityBits, now)
            }
            settings = candidate
            if (previous.logIntervalSeconds != candidate.logIntervalSeconds) {
                restartReporter(candidate.logIntervalSeconds)
            }
        }
    }

    /** Cumulative bounded snapshot for host metrics or operator diagnostics. */
    fun snapshot(): RuntimeSnapshot = synchronized(monitor) {
        val perSource = sources.mapValues { (_, value) -> value.total.snapshot() }
        RuntimeSnapshot(
            closed = closed,
            settings = settings,
            viewerBuckets = viewerBuckets.size,
            sources = perSource.toSortedMap(),
            totals = perSource.values.fold(CounterSnapshot()) { acc, value -> acc + value },
        )
    }

    /** Aggregate gauges plus three series per stable source, bounded to [MAX_SOURCES]. */
    fun metricPoints(): List<MetricPoint> {
        val snapshot = snapshot()
        val totals = snapshot.totals
        return buildList {
            add(MetricPoint("arc_visual_packet_payload_bytes_total", "Encoded visual payload bytes handed to transport", totals.sentBytes.toDouble()))
            add(MetricPoint("arc_visual_packet_payload_packets_total", "Visual packets handed to transport", totals.sentPackets.toDouble()))
            add(MetricPoint("arc_visual_packet_cleanup_bytes_total", "Cleanup payload bytes handed to transport", totals.cleanupBytes.toDouble()))
            add(MetricPoint("arc_visual_packet_cleanup_packets_total", "Cleanup packets handed to transport", totals.cleanupPackets.toDouble()))
            add(MetricPoint("arc_visual_packet_admission_attempts_total", "Visual transaction admission attempts", totals.attempts.toDouble()))
            add(MetricPoint("arc_visual_packet_admitted_total", "Visual transactions admitted", totals.admitted.toDouble()))
            add(MetricPoint("arc_visual_packet_cleanup_admitted_total", "Cleanup transactions admitted", totals.cleanupAdmitted.toDouble()))
            VisualPacketAdmission.entries.filter { it != VisualPacketAdmission.ALLOWED }.forEach { reason ->
                add(
                    MetricPoint(
                        "arc_visual_packet_deferred_total",
                        "Visual transactions deferred by admission reason",
                        totals.deferred(reason).toDouble(),
                        mapOf("reason" to reason.name.lowercase()),
                    ),
                )
            }
            snapshot.sources.forEach { (source, counters) ->
                if (!counters.hasActivity()) return@forEach
                val tags = mapOf("source" to source)
                add(MetricPoint("arc_visual_packet_source_payload_bytes_total", "Encoded visual payload bytes by source", counters.sentBytes.toDouble(), tags))
                add(MetricPoint("arc_visual_packet_source_payload_packets_total", "Visual packets by source", counters.sentPackets.toDouble(), tags))
                add(MetricPoint("arc_visual_packet_source_deferred_total", "Visual transactions deferred by source", counters.deferredTotal().toDouble(), tags))
            }
        }
    }

    override fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
        }
        try {
            taskScope?.close()
        } finally {
            hostPlugin?.server?.servicesManager?.unregister(ArcVisualPacketBudget::class.java, this)
        }
    }

    private fun deferred(counters: SourceCounters, result: VisualPacketAdmission): VisualPacketAdmission {
        counters.total.defer(result)
        counters.interval.defer(result)
        return result
    }

    private fun countersFor(rawSource: String): SourceCounters {
        val name = rawSource.takeIf { SOURCE_PATTERN.matches(it) } ?: OVERFLOW_SOURCE
        sources[name]?.let { return it }
        if (sources.size >= MAX_SOURCES) return sources.getValue(OVERFLOW_SOURCE)
        return SourceCounters().also { sources[name] = it }
    }

    private fun startReporter() {
        val interval = synchronized(monitor) { settings.logIntervalSeconds }
        restartReporter(interval)
    }

    private fun restartReporter(intervalSeconds: Long) {
        val scope = taskScope ?: return
        if (closed) return
        scope.restart()
        val periodTicks = intervalSeconds * TICKS_PER_SECOND
        checkNotNull(scope.runTimerAsync(periodTicks, periodTicks, ::reportInterval)) {
            "Could not schedule the visual packet reporter"
        }
    }

    private fun reportInterval() {
        val report = synchronized(monitor) {
            if (closed) return
            val now = nanoTime()
            val elapsedNanos = (now - lastReportNanos).coerceAtLeast(1L)
            lastReportNanos = now
            val activeSources = sources.mapNotNull { (name, value) ->
                val interval = value.interval.drain()
                interval.takeIf { it.hasActivity() }?.let { name to it }
            }.sortedBy { it.first }
            val total = activeSources.fold(CounterSnapshot()) { acc, (_, value) -> acc + value }
            val currentOverload = total.hasRateOrBackpressureDeferrals()
            val transition = when {
                currentOverload && !overloaded -> "overload"
                !currentOverload && overloaded -> "recovered"
                else -> null
            }
            overloaded = currentOverload
            Triple(
                elapsedNanos.toDouble() / NANOS_PER_SECOND,
                activeSources,
                ReportStatus(total, transition, settings.verboseLogs, settings),
            )
        }
        val logger = hostPlugin?.logger ?: return
        val elapsedSeconds = report.first
        val sourcesNow = report.second
        val status = report.third
        val total = status.totals
        if (status.transition == "overload" || (status.transition == null && total.hasRateOrBackpressureDeferrals())) {
            val topSources = sourcesNow
                .sortedWith(
                    compareByDescending<Pair<String, CounterSnapshot>> { it.second.pressureDeferredTotal() }
                        .thenByDescending { it.second.sentBytes }
                        .thenBy { it.first },
                )
                .take(TOP_OVERLOAD_SOURCES)
                .joinToString(",") { (source, counters) -> "$source:${counters.sentBytes}/${counters.pressureDeferredTotal()}" }
            logger.warning(
                line(
                    "status" to "overload",
                    "seconds" to rounded(elapsedSeconds),
                    "bytes" to total.sentBytes,
                    "packets" to total.sentPackets,
                    "mbit_s" to mbitPerSecond(total.sentBytes, elapsedSeconds),
                    "channel_deferred" to total.channelBackpressure,
                    "viewer_deferred" to total.viewerRate,
                    "server_deferred" to total.serverRate,
                    "server_limit_mbps" to status.settings.serverMbps,
                    "viewer_limit_mbps" to status.settings.viewerMbps,
                    "burst_ms" to status.settings.burstMillis,
                    "top_sources" to topSources,
                ),
            )
        } else if (status.transition == "recovered") {
            logger.info(line("status" to "recovered", "seconds" to rounded(elapsedSeconds), "bytes" to total.sentBytes, "packets" to total.sentPackets))
        }
        if (status.verbose) {
            logger.info(
                line(
                    "status" to "interval",
                    "seconds" to rounded(elapsedSeconds),
                    "bytes" to total.sentBytes,
                    "packets" to total.sentPackets,
                    "mbit_s" to mbitPerSecond(total.sentBytes, elapsedSeconds),
                    "cleanup_bytes" to total.cleanupBytes,
                    "attempts" to total.attempts,
                    "admitted" to total.admitted,
                    "cleanup_admitted" to total.cleanupAdmitted,
                    "channel_deferred" to total.channelBackpressure,
                    "viewer_deferred" to total.viewerRate,
                    "server_deferred" to total.serverRate,
                    "closed_deferred" to total.closed,
                ),
            )
            sourcesNow.forEach { (source, counters) ->
                logger.info(
                    line(
                        "status" to "source",
                        "source" to source,
                        "seconds" to rounded(elapsedSeconds),
                        "bytes" to counters.sentBytes,
                        "packets" to counters.sentPackets,
                        "mbit_s" to mbitPerSecond(counters.sentBytes, elapsedSeconds),
                        "cleanup_bytes" to counters.cleanupBytes,
                        "attempts" to counters.attempts,
                        "admitted" to counters.admitted,
                        "cleanup_admitted" to counters.cleanupAdmitted,
                        "channel_deferred" to counters.channelBackpressure,
                        "viewer_deferred" to counters.viewerRate,
                        "server_deferred" to counters.serverRate,
                        "closed_deferred" to counters.closed,
                    ),
                )
            }
        }
    }

    private fun line(vararg fields: Pair<String, Any?>): String = debugLine.line(fields.asList())

    private fun rounded(value: Double): Double = round(value * 100.0) / 100.0

    private fun mbitPerSecond(bytes: Long, elapsedSeconds: Double): Double =
        rounded(bytes.toDouble() * BITS_PER_BYTE / elapsedSeconds / BITS_PER_MEGABIT)

    data class Settings(
        val serverMbps: Double = DEFAULT_SERVER_MBPS,
        val viewerMbps: Double = DEFAULT_VIEWER_MBPS,
        val burstMillis: Long = DEFAULT_BURST_MILLIS,
        val logIntervalSeconds: Long = DEFAULT_LOG_INTERVAL_SECONDS,
        val verboseLogs: Boolean = false,
    ) {
        init {
            require(serverMbps.isFinite() && serverMbps in MIN_RATE_MBPS..MAX_RATE_MBPS) {
                "server-mbps must be between $MIN_RATE_MBPS and $MAX_RATE_MBPS"
            }
            require(viewerMbps.isFinite() && viewerMbps in MIN_RATE_MBPS..MAX_RATE_MBPS) {
                "viewer-mbps must be between $MIN_RATE_MBPS and $MAX_RATE_MBPS"
            }
            require(burstMillis in MIN_BURST_MILLIS..MAX_BURST_MILLIS) {
                "burst-ms must be between $MIN_BURST_MILLIS and $MAX_BURST_MILLIS"
            }
            require(logIntervalSeconds in MIN_LOG_INTERVAL_SECONDS..MAX_LOG_INTERVAL_SECONDS) {
                "log-interval-seconds must be between $MIN_LOG_INTERVAL_SECONDS and $MAX_LOG_INTERVAL_SECONDS"
            }
        }

        internal val serverRateBitsPerSecond = serverMbps * BITS_PER_MEGABIT
        internal val viewerRateBitsPerSecond = viewerMbps * BITS_PER_MEGABIT
        internal val serverCapacityBits = serverRateBitsPerSecond * burstMillis / MILLIS_PER_SECOND
        internal val viewerCapacityBits = viewerRateBitsPerSecond * burstMillis / MILLIS_PER_SECOND

        companion object {
            @JvmStatic
            fun from(config: Config): Settings {
                fun number(path: String, default: Double): Double =
                    if (config.exists(path)) {
                        requireNotNull(config.doubleOrNull(path)) { "$path must be numeric" }
                    } else {
                        default
                    }

                fun integer(path: String, default: Long): Long {
                    if (!config.exists(path)) return default
                    val value = config.longOrNull(path)
                    require(value != null && config.doubleOrNull(path) == value.toDouble()) {
                        "$path must be an integer"
                    }
                    return value
                }

                fun boolean(path: String, default: Boolean): Boolean =
                    if (config.exists(path)) {
                        requireNotNull(config.booleanOrNull(path)) { "$path must be boolean" }
                    } else {
                        default
                    }

                return Settings(
                    serverMbps = number("server-mbps", DEFAULT_SERVER_MBPS),
                    viewerMbps = number("viewer-mbps", DEFAULT_VIEWER_MBPS),
                    burstMillis = integer("burst-ms", DEFAULT_BURST_MILLIS),
                    logIntervalSeconds = integer("log-interval-seconds", DEFAULT_LOG_INTERVAL_SECONDS),
                    verboseLogs = boolean("verbose-logs", false),
                )
            }
        }
    }

    data class CounterSnapshot(
        val attempts: Long = 0,
        val admitted: Long = 0,
        val cleanupAdmitted: Long = 0,
        val sentBytes: Long = 0,
        val sentPackets: Long = 0,
        val cleanupBytes: Long = 0,
        val cleanupPackets: Long = 0,
        val channelBackpressure: Long = 0,
        val viewerRate: Long = 0,
        val serverRate: Long = 0,
        val closed: Long = 0,
    ) {
        operator fun plus(other: CounterSnapshot) =
            CounterSnapshot(
                attempts = attempts + other.attempts,
                admitted = admitted + other.admitted,
                cleanupAdmitted = cleanupAdmitted + other.cleanupAdmitted,
                sentBytes = sentBytes + other.sentBytes,
                sentPackets = sentPackets + other.sentPackets,
                cleanupBytes = cleanupBytes + other.cleanupBytes,
                cleanupPackets = cleanupPackets + other.cleanupPackets,
                channelBackpressure = channelBackpressure + other.channelBackpressure,
                viewerRate = viewerRate + other.viewerRate,
                serverRate = serverRate + other.serverRate,
                closed = closed + other.closed,
            )

        fun deferred(reason: VisualPacketAdmission): Long =
            when (reason) {
                VisualPacketAdmission.ALLOWED -> 0L
                VisualPacketAdmission.CHANNEL_BACKPRESSURE -> channelBackpressure
                VisualPacketAdmission.VIEWER_RATE -> viewerRate
                VisualPacketAdmission.SERVER_RATE -> serverRate
                VisualPacketAdmission.CLOSED -> closed
            }

        internal fun hasActivity(): Boolean =
            attempts != 0L || admitted != 0L || cleanupAdmitted != 0L || sentBytes != 0L || sentPackets != 0L ||
                cleanupBytes != 0L || cleanupPackets != 0L || channelBackpressure != 0L ||
                viewerRate != 0L || serverRate != 0L || closed != 0L

        internal fun hasRateOrBackpressureDeferrals(): Boolean =
            channelBackpressure != 0L || viewerRate != 0L || serverRate != 0L

        internal fun deferredTotal(): Long = channelBackpressure + viewerRate + serverRate + closed

        internal fun pressureDeferredTotal(): Long = channelBackpressure + viewerRate + serverRate
    }

    data class RuntimeSnapshot(
        val closed: Boolean,
        val settings: Settings,
        val viewerBuckets: Int,
        val sources: Map<String, CounterSnapshot>,
        val totals: CounterSnapshot,
    )

    private data class ReportStatus(
        val totals: CounterSnapshot,
        val transition: String?,
        val verbose: Boolean,
        val settings: Settings,
    )

    private class SourceCounters {
        val total = MutableCounters()
        val interval = MutableCounters()
        fun admit(cleanup: Boolean) {
            total.admitted++
            interval.admitted++
            if (cleanup) {
                total.cleanupAdmitted++
                interval.cleanupAdmitted++
            }
        }
    }

    private class MutableCounters {
        var attempts = 0L
        var admitted = 0L
        var cleanupAdmitted = 0L
        var sentBytes = 0L
        var sentPackets = 0L
        var cleanupBytes = 0L
        var cleanupPackets = 0L
        var channelBackpressure = 0L
        var viewerRate = 0L
        var serverRate = 0L
        var closed = 0L

        fun defer(reason: VisualPacketAdmission) {
            when (reason) {
                VisualPacketAdmission.ALLOWED -> Unit
                VisualPacketAdmission.CHANNEL_BACKPRESSURE -> channelBackpressure++
                VisualPacketAdmission.VIEWER_RATE -> viewerRate++
                VisualPacketAdmission.SERVER_RATE -> serverRate++
                VisualPacketAdmission.CLOSED -> closed++
            }
        }

        fun recordSent(bytes: Int, packets: Int, cleanup: Boolean) {
            sentBytes += bytes.toLong()
            sentPackets += packets.toLong()
            if (cleanup) {
                cleanupBytes += bytes.toLong()
                cleanupPackets += packets.toLong()
            }
        }

        fun snapshot() =
            CounterSnapshot(
                attempts = attempts,
                admitted = admitted,
                cleanupAdmitted = cleanupAdmitted,
                sentBytes = sentBytes,
                sentPackets = sentPackets,
                cleanupBytes = cleanupBytes,
                cleanupPackets = cleanupPackets,
                channelBackpressure = channelBackpressure,
                viewerRate = viewerRate,
                serverRate = serverRate,
                closed = closed,
            )

        fun drain(): CounterSnapshot = snapshot().also {
            attempts = 0
            admitted = 0
            cleanupAdmitted = 0
            sentBytes = 0
            sentPackets = 0
            cleanupBytes = 0
            cleanupPackets = 0
            channelBackpressure = 0
            viewerRate = 0
            serverRate = 0
            closed = 0
        }
    }

    private class TokenBucket(
        private var rateBitsPerSecond: Double,
        private var capacityBits: Double,
        now: Long,
    ) {
        private var tokensBits = capacityBits
        private var lastNanos = now

        fun refill(now: Long) {
            val elapsed = (now - lastNanos).coerceAtLeast(0L)
            if (elapsed > 0L) {
                tokensBits = (tokensBits + elapsed.toDouble() * rateBitsPerSecond / NANOS_PER_SECOND).coerceAtMost(capacityBits)
                lastNanos = now
            }
        }

        fun canSpend(costBits: Long): Boolean =
            tokensBits >= costBits || (tokensBits >= capacityBits && costBits.toDouble() > capacityBits)

        /** Charge may take a bucket into debt for an oversized transaction or cleanup. */
        fun charge(costBits: Long) {
            tokensBits -= costBits.toDouble()
        }

        fun update(newRate: Double, newCapacity: Double, now: Long) {
            refill(now)
            rateBitsPerSecond = newRate
            capacityBits = newCapacity
            tokensBits = tokensBits.coerceAtMost(newCapacity)
        }
    }

    companion object {
        const val CONFIG_FILE = "visual-packets.yml"
        const val DEFAULT_SERVER_MBPS = 32.0
        const val DEFAULT_VIEWER_MBPS = 2.0
        const val DEFAULT_BURST_MILLIS = 250L
        const val DEFAULT_LOG_INTERVAL_SECONDS = 30L
        const val MAX_SOURCES = 256
        const val OVERFLOW_SOURCE = "_other_"
        private const val MIN_RATE_MBPS = 0.01
        private const val MAX_RATE_MBPS = 10_000.0
        private const val MIN_BURST_MILLIS = 10L
        private const val MAX_BURST_MILLIS = 30_000L
        private const val MIN_LOG_INTERVAL_SECONDS = 30L
        private const val MAX_LOG_INTERVAL_SECONDS = 3_600L
        private const val TICKS_PER_SECOND = 20L
        private const val TOP_OVERLOAD_SOURCES = 5
        private const val BITS_PER_BYTE = 8L
        private const val BITS_PER_MEGABIT = 1_000_000.0
        private const val MILLIS_PER_SECOND = 1_000.0
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private val SOURCE_PATTERN = Regex("[a-z0-9][a-z0-9_.:-]{0,127}")

        /** Install once in ARC, before any visual packet owners are constructed. */
        @JvmStatic
        @JvmOverloads
        fun install(
            plugin: Plugin,
            config: Config = ConfigManager.of(plugin.dataFolder.toPath().resolve(ConfigManager.MODULE_YAML_DIR), CONFIG_FILE),
        ): PaperVisualPacketRuntime {
            check(Bukkit.isPrimaryThread()) { "Visual packet budget must be installed on the server thread" }
            check(plugin.server.servicesManager.load(ArcVisualPacketBudget::class.java) == null) {
                "An ARC visual packet budget is already registered; install the provider once per server"
            }
            val runtime = PaperVisualPacketRuntime(Settings.from(config), hostPlugin = plugin)
            plugin.server.servicesManager.register(ArcVisualPacketBudget::class.java, runtime, plugin, ServicePriority.Normal)
            try {
                runtime.startReporter()
            } catch (failure: Throwable) {
                runtime.close()
                throw failure
            }
            return runtime
        }
    }
}
