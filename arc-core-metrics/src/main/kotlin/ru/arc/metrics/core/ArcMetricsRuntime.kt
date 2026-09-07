package ru.arc.metrics.core

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Tags
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.LoggerFactory
import ru.arc.core.ScheduledTask
import ru.arc.core.repeatingAsync
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Shared metrics runtime. Core sampling runs asynchronously; platform modules
 * provide their own thread-correct snapshots through [recordSnapshot].
 */
class ArcMetricsRuntime(
    val config: MetricsConfig,
    val identity: MetricsIdentity,
    dataPath: Path,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(ArcMetricsRuntime::class.java)
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val gauges: CachedGaugeStore

    private val coreCollector = JvmSystemMetricsCollector(dataPath)
    private val moduleCollector = ModuleMetricsCollector()
    private val failures = ConcurrentHashMap<String, Counter>()
    private val registeredMeters = AtomicInteger()
    private var httpServer: MetricsHttpServer? = null
    private var fastTask: ScheduledTask? = null
    private var heavyTask: ScheduledTask? = null

    init {
        registry.config()
            .onMeterAdded { registeredMeters.incrementAndGet() }
            .onMeterRemoved { registeredMeters.decrementAndGet() }
        registry.config().commonTags(
            Tags.of(
                "application",
                identity.application,
                "platform",
                identity.platform,
                "server_name",
                identity.serverName,
            ),
        )
        gauges = CachedGaugeStore(registry)
        gauges.applySnapshot(
            "identity",
            listOf(
                MetricPoint(
                    "arc_application_info",
                    "Static ARC application identity",
                    1.0,
                    mapOf("version" to identity.version),
                ),
            ),
        )
    }

    val enabled: Boolean
        get() = config.enabled

    fun start() {
        if (!enabled) return
        check(httpServer == null) { "Metrics runtime already started" }

        recordSnapshot("core-fast", "core") {
            coreCollector.fastSnapshot(config.includeJvm, config.includeSystem) +
                moduleCollector.snapshot()
        }
        httpServer = MetricsHttpServer(registry, config).also { it.start() }

        fastTask =
            repeatingAsync(
                config.sampleIntervalSeconds.seconds,
                delay = config.sampleIntervalSeconds.seconds,
            ) {
                recordSnapshot("core-fast", "core") {
                    coreCollector.fastSnapshot(config.includeJvm, config.includeSystem) +
                        moduleCollector.snapshot()
                }
            }
        heavyTask =
            repeatingAsync(
                config.heavySampleIntervalSeconds.seconds,
                delay = 10.seconds,
            ) {
                recordSnapshot("core-heavy", "core-heavy") {
                    coreCollector.heavySnapshot(config.includeDisk)
                }
            }
    }

    fun recordSnapshot(
        source: String,
        tier: String,
        snapshot: () -> Collection<MetricPoint>,
    ) {
        val started = System.nanoTime()
        try {
            gauges.applySnapshot(source, snapshot())
        } catch (failure: Throwable) {
            failures
                .computeIfAbsent(tier) {
                    Counter
                        .builder("arc_metrics_sample_failures")
                        .tag("tier", tier)
                        .register(registry)
                }.increment()
            log.warn("Metrics {} snapshot failed", source, failure)
        } finally {
            val elapsed = (System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000_000.0
            gauges.applySnapshot(
                "status-$source",
                listOf(
                    MetricPoint(
                        "arc_metrics_sample_duration_seconds",
                        "Last metrics snapshot duration",
                        elapsed,
                        mapOf("tier" to tier),
                    ),
                    MetricPoint(
                        "arc_metrics_last_sample_timestamp_seconds",
                        "Last metrics snapshot attempt timestamp",
                        clock.instant().epochSecond.toDouble(),
                        mapOf("tier" to tier),
                    ),
                ),
            )
            updateRegistryStats()
        }
    }

    private fun updateRegistryStats() {
        val stats = gauges.stats()
        gauges.applySnapshot(
            "metrics-registry",
            listOf(
                MetricPoint(
                    "arc_metrics_cached_series",
                    "Cached metric series by current activity state",
                    stats.activeSeries.toDouble(),
                    mapOf("state" to "active"),
                ),
                MetricPoint(
                    "arc_metrics_cached_series",
                    "Cached metric series by current activity state",
                    stats.staleSeries.toDouble(),
                    mapOf("state" to "stale"),
                ),
                MetricPoint(
                    "arc_metrics_registered_meters",
                    "Meters registered in the application registry",
                    registeredMeters.get().toDouble(),
                ),
                MetricPoint(
                    "arc_metrics_snapshot_sources",
                    "Cached snapshot sources",
                    stats.sources.toDouble(),
                ),
            ),
        )
    }

    override fun close() {
        fastTask?.cancel()
        heavyTask?.cancel()
        fastTask = null
        heavyTask = null
        httpServer?.stop()
        httpServer = null
        registry.close()
    }
}
