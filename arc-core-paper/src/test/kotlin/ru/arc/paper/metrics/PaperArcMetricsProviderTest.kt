package ru.arc.paper.metrics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import ru.arc.paper.api.ArcMetricCounter
import ru.arc.paper.api.ArcMetricGauge
import ru.arc.paper.api.ArcMetricTimer
import ru.arc.paper.api.ArcMetricsScope
import ru.arc.paper.api.ArcMetricsService
import ru.arc.paper.metrics.client.ApiOnlyMetricsClient
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit

class PaperArcMetricsProviderTest : FreeSpec({
    "counter gauge and timer use stable owner and caller tags" {
        SimpleMeterRegistry().withRegistry { registry ->
            PaperArcMetricsProvider(registry).closeAfter { provider ->
                val scope = provider.open("ArcFarms")
                val tags = mapOf("action" to "récolte")
                val counter = scope.counter("arc_farms_actions", "Completed actions", tags)
                val gauge = scope.gauge("arc_farms_workers", "Current workers", emptyMap())
                val timer = scope.timer("arc_farms_action_duration", "Action duration", tags)

                counter.increment()
                counter.increment(2.0)
                gauge.set(4.5)
                timer.record(25L, TimeUnit.MILLISECONDS)

                registry.find("arc_farms_actions").tag("owner", "ArcFarms").tag("action", "récolte").counter()!!.count() shouldBe 3.0
                registry.find("arc_farms_workers").tag("owner", "ArcFarms").gauge()!!.value() shouldBe 4.5
                registry.find("arc_farms_action_duration").tag("owner", "ArcFarms").tag("action", "récolte").timer()!!.totalTime(TimeUnit.MILLISECONDS) shouldBe 25.0
                registry.find("arc_metrics_consumer_scopes").gauge()!!.value() shouldBe 1.0
            }
        }
    }

    "closing an owner removes only its meters and makes late handles inert" {
        SimpleMeterRegistry().withRegistry { registry ->
            PaperArcMetricsProvider(registry).closeAfter { provider ->
                val first = provider.open("arc_farms")
                val second = provider.open("arc_ranks")
                val lateCounter = first.counter("shared_operations", "Completed operations", emptyMap())
                val otherCounter = second.counter("shared_operations", "Completed operations", emptyMap())
                lateCounter.increment()
                otherCounter.increment()

                first.close()
                lateCounter.increment()

                registry.find("shared_operations").tag("owner", "arc_farms").counter() shouldBe null
                registry.find("shared_operations").tag("owner", "arc_ranks").counter()!!.count() shouldBe 1.0
                registry.find("arc_metrics_consumer_scopes").gauge()!!.value() shouldBe 1.0
                provider.activeOwnerCount() shouldBe 1
            }
        }
    }

    "rebind moves meters and preserves handles and the current gauge value" {
        SimpleMeterRegistry().withRegistry { original ->
            SimpleMeterRegistry().withRegistry { replacement ->
                PaperArcMetricsProvider(original).closeAfter { provider ->
                    val scope = provider.open("arc_farms")
                    val counter = scope.counter("arc_farms_actions", "Completed actions", emptyMap())
                    val gauge = scope.gauge("arc_farms_workers", "Current workers", emptyMap())
                    val timer = scope.timer("arc_farms_action_duration", "Action duration", emptyMap())
                    counter.increment()
                    gauge.set(6.0)
                    timer.record(10L, TimeUnit.MILLISECONDS)

                    provider.rebind(replacement)
                    counter.increment()
                    timer.record(20L, TimeUnit.MILLISECONDS)

                    original.find("arc_farms_actions").tag("owner", "arc_farms").counter() shouldBe null
                    replacement.find("arc_farms_actions").tag("owner", "arc_farms").counter()!!.count() shouldBe 1.0
                    replacement.find("arc_farms_workers").tag("owner", "arc_farms").gauge()!!.value() shouldBe 6.0
                    replacement.find("arc_farms_action_duration").tag("owner", "arc_farms").timer()!!.count() shouldBe 1L
                    replacement.find("arc_metrics_consumer_scopes").gauge()!!.value() shouldBe 1.0
                }
            }
        }
    }

    "missing registry drops measurements without buffering and later binds existing handles" {
        SimpleMeterRegistry().withRegistry { registry ->
            PaperArcMetricsProvider(null).closeAfter { provider ->
                val scope = provider.open("arc_farms")
                val counter = scope.counter("arc_farms_actions", "Completed actions", emptyMap())
                val gauge = scope.gauge("arc_farms_workers", "Current workers", emptyMap())
                counter.increment()
                gauge.set(3.0)

                provider.rebind(registry)
                counter.increment()

                registry.find("arc_farms_actions").tag("owner", "arc_farms").counter()!!.count() shouldBe 1.0
                registry.find("arc_farms_workers").tag("owner", "arc_farms").gauge()!!.value() shouldBe 3.0
            }
        }
    }

    "invalid and duplicate owners receive harmless scopes" {
        SimpleMeterRegistry().withRegistry { registry ->
            PaperArcMetricsProvider(registry).closeAfter { provider ->
                val accepted = provider.open("arc_farms")
                val duplicate = provider.open("arc_farms")
                val invalid = provider.open("bad\u0000owner")
                accepted.counter("arc_farms_actions", "Completed actions", emptyMap()).increment()
                duplicate.counter("duplicate_actions", "Ignored duplicate", emptyMap()).increment()
                invalid.counter("invalid_actions", "Ignored invalid owner", emptyMap()).increment()

                registry.find("arc_farms_actions").tag("owner", "arc_farms").counter()!!.count() shouldBe 1.0
                registry.find("duplicate_actions").counter() shouldBe null
                registry.find("invalid_actions").counter() shouldBe null
                provider.activeOwnerCount() shouldBe 1
            }
        }
    }

    "labels and per-owner series stay bounded" {
        SimpleMeterRegistry().withRegistry { registry ->
            PaperArcMetricsProvider(registry).closeAfter { provider ->
                val scope = provider.open("arc_farms")
                val nineTags = (0 until 9).associate { "tag$it" to "value$it" }
                scope.counter("too_many_labels", "Rejected labels", nineTags).increment()
                repeat(128) { index ->
                    scope.counter("bounded_series", "Bounded series", mapOf("result" to "r$index")).increment()
                }
                scope.counter("bounded_series", "Bounded series", mapOf("result" to "overflow")).increment()

                registry.find("too_many_labels").counter() shouldBe null
                registry.meters.count { it.id.name == "bounded_series" } shouldBe 128
            }
        }
    }

    "one Prometheus scrape exposes all meter types and owner cleanup survives registry rebind" {
        PrometheusMeterRegistry(PrometheusConfig.DEFAULT).withRegistry { original ->
            PrometheusMeterRegistry(PrometheusConfig.DEFAULT).withRegistry { replacement ->
                PaperArcMetricsProvider(original).closeAfter { provider ->
                    val farms = provider.open("ArcFarms")
                    val ranks = provider.open("ArcRanks")
                    val farmsCounter = farms.counter("arc_farms_actions", "Completed actions", mapOf("action" to "récolte"))
                    val farmsGauge = farms.gauge("arc_farms_workers", "Current workers", emptyMap())
                    val farmsTimer = farms.timer("arc_farms_action_duration", "Action duration", emptyMap())
                    val ranksCounter = ranks.counter("shared_operations", "Completed operations", emptyMap())
                    farmsCounter.increment(2.0)
                    farmsGauge.set(4.0)
                    farmsTimer.record(15L, TimeUnit.MILLISECONDS)
                    ranksCounter.increment()

                    val firstScrape = original.scrape()
                    sampleLine(firstScrape, "arc_farms_actions_total", "ArcFarms").contains("action=\"récolte\"") shouldBe true
                    sampleLine(firstScrape, "arc_farms_workers", "ArcFarms").isNotEmpty() shouldBe true
                    sampleLine(firstScrape, "arc_farms_action_duration_seconds_count", "ArcFarms").isNotEmpty() shouldBe true
                    sampleLine(firstScrape, "shared_operations_total", "ArcRanks").isNotEmpty() shouldBe true

                    farms.close()
                    val afterOwnerClose = original.scrape()
                    metricLines(afterOwnerClose, "ArcFarms").isEmpty() shouldBe true
                    metricLines(afterOwnerClose, "ArcRanks").isNotEmpty() shouldBe true

                    provider.rebind(replacement)
                    ranksCounter.increment()
                    metricLines(original.scrape(), "ArcRanks").isEmpty() shouldBe true
                    val reboundScrape = replacement.scrape()
                    sampleLine(reboundScrape, "shared_operations_total", "ArcRanks").isNotEmpty() shouldBe true
                }
            }
        }
    }

    "API-only consumer calls the provider across a classloader without Micrometer" {
        SimpleMeterRegistry().withRegistry { registry ->
            PaperArcMetricsProvider(registry).closeAfter { provider ->
                ApiOnlyConsumerLoader(
                    arrayOf(ApiOnlyMetricsClient::class.java.protectionDomain.codeSource.location),
                    ArcMetricsService::class.java.classLoader,
                ).closeAfter { loader ->
                    val adapter = loader.loadClass(API_ONLY_CLIENT_CLASS)
                    val method = adapter.getMethod("record", ArcMetricsService::class.java)

                    (adapter.classLoader !== provider.javaClass.classLoader) shouldBe true
                    loader.loadClass(ArcMetricsService::class.java.name) shouldBe ArcMetricsService::class.java
                    shouldThrow<ClassNotFoundException> { loader.loadClass("io.micrometer.core.instrument.MeterRegistry") }
                    apiContractTypes().flatMap { type ->
                        type.methods.flatMap { method -> listOf(method.returnType) + method.parameterTypes }
                    }.any { it.name.startsWith("io.micrometer.") } shouldBe false
                    (method.returnType.name.startsWith("io.micrometer.") || method.parameterTypes.any { it.name.startsWith("io.micrometer.") }) shouldBe false

                    method.invoke(null, provider)

                    registry.find("arc_farms_actions").tag("owner", "ArcFarms").counter()!!.count() shouldBe 3.0
                    registry.find("arc_farms_workers").tag("owner", "ArcFarms").gauge()!!.value() shouldBe 2.5
                    registry.find("arc_farms_action_duration").tag("owner", "ArcFarms").timer()!!.count() shouldBe 1L
                }
            }
        }
    }
})

private const val API_ONLY_CLIENT_CLASS = "ru.arc.paper.metrics.client.ApiOnlyMetricsClient"

private inline fun <T : AutoCloseable, R> T.closeAfter(block: (T) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }

private inline fun <T : MeterRegistry, R> T.withRegistry(block: (T) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }

private fun apiContractTypes(): List<Class<*>> =
    listOf(
        ArcMetricsService::class.java,
        ArcMetricsScope::class.java,
        ArcMetricCounter::class.java,
        ArcMetricGauge::class.java,
        ArcMetricTimer::class.java,
        PaperArcMetrics::class.java,
    )

private fun metricLines(scrape: String, owner: String): List<String> =
    scrape.lineSequence().filter { !it.startsWith('#') && it.contains("owner=\"$owner\"") }.toList()

private fun sampleLine(scrape: String, family: String, owner: String): String =
    metricLines(scrape, owner).first { it.startsWith(family) }

private class ApiOnlyConsumerLoader(urls: Array<URL>, parent: ClassLoader?) : URLClassLoader(urls, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith("io.micrometer.")) throw ClassNotFoundException(name)
        if (name == API_ONLY_CLIENT_CLASS) synchronized(getClassLoadingLock(name)) {
            val loaded = findLoadedClass(name) ?: findClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
        return super.loadClass(name, resolve)
    }
}
