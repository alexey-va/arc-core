package ru.arc.metrics.core

import ru.arc.core.ModuleRegistry
import ru.arc.core.ModuleRuntimeStatus

/** Converts the finite ModuleRegistry lifecycle set into cached metric points. */
class ModuleMetricsCollector(
    private val statuses: () -> List<ModuleRuntimeStatus> = ModuleRegistry::getRuntimeStatuses,
) {
    fun snapshot(): List<MetricPoint> {
        val current = statuses()
        return buildList {
            add(
                MetricPoint(
                    "arc_modules",
                    "Registered plugin modules by readiness state",
                    current.count { it.ready }.toDouble(),
                    mapOf("state" to "ready"),
                ),
            )
            add(
                MetricPoint(
                    "arc_modules",
                    "Registered plugin modules by readiness state",
                    current.count { !it.ready }.toDouble(),
                    mapOf("state" to "not_ready"),
                ),
            )
            for (status in current) {
                val tags = mapOf("module" to status.name)
                add(
                    MetricPoint(
                        "arc_module_ready",
                        "Plugin module readiness",
                        if (status.ready) 1.0 else 0.0,
                        tags,
                    ),
                )
                add(
                    MetricPoint(
                        "arc_module_failures",
                        "Plugin module lifecycle failures since process start",
                        status.failures.toDouble(),
                        tags,
                    ),
                )
                status.initDurationMs?.let {
                    add(duration(status.name, "init", it))
                }
                status.reloadDurationMs?.let {
                    add(duration(status.name, "reload", it))
                }
                status.shutdownDurationMs?.let {
                    add(duration(status.name, "shutdown", it))
                }
            }
        }
    }

    private fun duration(
        module: String,
        operation: String,
        durationMs: Long,
    ) = MetricPoint(
        "arc_module_lifecycle_duration_seconds",
        "Last plugin module lifecycle operation duration",
        durationMs.coerceAtLeast(0L) / 1_000.0,
        mapOf("module" to module, "operation" to operation),
    )
}
