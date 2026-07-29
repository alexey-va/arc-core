package ru.arc.metrics.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import ru.arc.core.ModuleRuntimeStatus

class ModuleMetricsCollectorTest :
    FreeSpec({
        "exports readiness failures and lifecycle durations with bounded module labels" {
            val collector =
                ModuleMetricsCollector {
                    listOf(
                        ModuleRuntimeStatus(
                            name = "Redis",
                            ready = true,
                            initDurationMs = 250,
                        ),
                        ModuleRuntimeStatus(
                            name = "Assistant",
                            ready = false,
                            initDurationMs = 1_500,
                            failures = 2,
                        ),
                    )
                }

            val points = collector.snapshot()

            points.point("arc_modules", "state", "ready") shouldBeExactly 1.0
            points.point("arc_modules", "state", "not_ready") shouldBeExactly 1.0
            points.point("arc_module_ready", "module", "Redis") shouldBeExactly 1.0
            points.point("arc_module_failures", "module", "Assistant") shouldBeExactly 2.0
            points
                .first {
                    it.name == "arc_module_lifecycle_duration_seconds" &&
                        it.tags == mapOf("module" to "Assistant", "operation" to "init")
                }.value shouldBeExactly 1.5
            points.mapNotNull { it.tags["module"] }.toSet() shouldBe setOf("Redis", "Assistant")
        }
    })

private fun List<MetricPoint>.point(
    name: String,
    tagName: String,
    tagValue: String,
): Double = first { it.name == name && it.tags[tagName] == tagValue }.value
