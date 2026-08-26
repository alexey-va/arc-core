package ru.arc.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.observability.RuntimeHealthState

class ModuleRuntimeHealthTest : FreeSpec({
    "empty or unattempted module sets remain starting" {
        moduleRuntimeHealth(emptyList()).state shouldBe RuntimeHealthState.STARTING
        moduleRuntimeHealth(listOf(ModuleRuntimeStatus("redis"))).state shouldBe RuntimeHealthState.STARTING
    }

    "a failed module degrades the host" {
        val health =
            moduleRuntimeHealth(
                listOf(
                    ModuleRuntimeStatus("config", ready = true),
                    ModuleRuntimeStatus("redis", ready = false, failures = 1),
                ),
            )

        health.state shouldBe RuntimeHealthState.DEGRADED
        health.dependencies.shouldContainExactly(mapOf("modules" to false))
    }

    "every ready module makes the module probe ready" {
        val health =
            moduleRuntimeHealth(
                listOf(
                    ModuleRuntimeStatus("config", ready = true),
                    ModuleRuntimeStatus("redis", ready = true),
                ),
            )

        health.state shouldBe RuntimeHealthState.UP
        health.dependencies.shouldContainExactly(mapOf("modules" to true))
        health.schemas.shouldContainExactly(mapOf("module_runtime" to MODULE_RUNTIME_HEALTH_SCHEMA))
    }
})
