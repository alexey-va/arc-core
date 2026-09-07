package ru.arc.metrics.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.arc.config.Config
import java.nio.file.Path

class ArcMetricsRuntimeTest : FreeSpec({
    "registered meter count follows additions duplicates and removals" {
        ArcMetricsRuntime(
            MetricsConfig(mockk<Config>(relaxed = true)),
            MetricsIdentity("test", "test", "test", "test"),
            Path.of("."),
        ).use { runtime ->
            fun sample() = runtime.recordSnapshot("test", "test") { emptyList() }
            sample()
            sample()
            runtime.gauges.value("arc_metrics_registered_meters") shouldBe runtime.registry.meters.size.toDouble()
            val counter = runtime.registry.counter("test_added")
            runtime.registry.counter("test_added")
            sample()
            runtime.gauges.value("arc_metrics_registered_meters") shouldBe runtime.registry.meters.size.toDouble()
            runtime.registry.remove(counter)
            sample()
            runtime.gauges.value("arc_metrics_registered_meters") shouldBe runtime.registry.meters.size.toDouble()
        }
    }
})
