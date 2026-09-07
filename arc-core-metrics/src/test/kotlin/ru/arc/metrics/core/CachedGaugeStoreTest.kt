package ru.arc.metrics.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CachedGaugeStoreTest :
    FreeSpec({
        "slow registration does not block existing snapshots or stats" {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val store = CachedGaugeStore(registry)
            val fastPoint = MetricPoint("arc_fast", "fast", 1.0)
            store.applySnapshot("fast", listOf(fastPoint))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            registry.config().onMeterAdded { meter ->
                if (meter.id.name == "arc_slow") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val slow = executor.submit { store.applySnapshot("slow", listOf(MetricPoint("arc_slow", "slow", 3.0))) }
                entered.await(2, TimeUnit.SECONDS) shouldBe true
                val fast = executor.submit {
                    store.applySnapshot("fast", listOf(fastPoint.copy(value = 2.0)))
                    store.stats()
                }
                fast.get(500, TimeUnit.MILLISECONDS)
                store.value("arc_fast") shouldBe 2.0
                release.countDown()
                slow.get(2, TimeUnit.SECONDS)
                store.stats().activeSeries shouldBe 2
            } finally {
                release.countDown()
                executor.shutdownNow()
                registry.close()
            }
        }

        "scrapes cached values and zeroes labels missing from the next snapshot" {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val store = CachedGaugeStore(registry)
            val tags = mapOf("world" to "survival")

            store.applySnapshot(
                "paper",
                listOf(MetricPoint("arc_test_entities", "test entities", 42.0, tags)),
            )

            store.value("arc_test_entities", tags) shouldBe 42.0
            registry.scrape() shouldContain "arc_test_entities{world=\"survival\"} 42.0"

            store.applySnapshot("paper", emptyList())
            store.value("arc_test_entities", tags) shouldBe 0.0
            store.stats().apply {
                totalSeries shouldBe 1
                activeSeries shouldBe 0
                staleSeries shouldBe 1
                sources shouldBe 1
            }
        }

        "rejects invalid metric names before registration" {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val store = CachedGaugeStore(registry)

            shouldThrow<IllegalArgumentException> {
                store.applySnapshot("bad", listOf(MetricPoint("bad metric", "bad", 1.0)))
            }
        }

        "keeps shared series active until every source drops it" {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val store = CachedGaugeStore(registry)
            val point = MetricPoint("arc_test_entities", "test entities", 42.0)

            store.applySnapshot("paper", listOf(point))
            store.applySnapshot("proxy", listOf(point.copy(value = 7.0)))
            store.applySnapshot("paper", emptyList())

            store.value(point.name) shouldBe 7.0
            store.stats() shouldBe
                CachedGaugeStore.Stats(
                    totalSeries = 1,
                    activeSeries = 1,
                    staleSeries = 0,
                    sources = 2,
                )

            store.clearSource("proxy")
            store.stats() shouldBe CachedGaugeStore.Stats(totalSeries = 1, activeSeries = 0, staleSeries = 1, sources = 1)
        }
    })
