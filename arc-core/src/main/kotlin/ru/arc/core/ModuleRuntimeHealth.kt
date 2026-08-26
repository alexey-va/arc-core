package ru.arc.core

import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState

/**
 * Converts the canonical module lifecycle telemetry into a bounded health probe.
 *
 * An unattempted module is still starting; a failed attempted module degrades
 * the host without hiding the modules that did initialize successfully.
 */
fun moduleRuntimeHealth(statuses: Collection<ModuleRuntimeStatus>): RuntimeHealthContribution {
    val allReady = statuses.isNotEmpty() && statuses.all(ModuleRuntimeStatus::ready)
    val state =
        when {
            allReady -> RuntimeHealthState.UP
            statuses.any { !it.ready && it.failures > 0L } -> RuntimeHealthState.DEGRADED
            else -> RuntimeHealthState.STARTING
        }
    return RuntimeHealthContribution(
        state = state,
        schemas = mapOf("module_runtime" to MODULE_RUNTIME_HEALTH_SCHEMA),
        dependencies = mapOf("modules" to allReady),
    )
}

const val MODULE_RUNTIME_HEALTH_SCHEMA = 1
