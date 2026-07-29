package ru.arc.metrics.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

class CachedGaugeStoreTest :
    FreeSpec({
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
    })
