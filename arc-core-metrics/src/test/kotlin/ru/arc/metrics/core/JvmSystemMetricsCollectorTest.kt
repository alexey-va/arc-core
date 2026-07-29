package ru.arc.metrics.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.shouldBeGreaterThan
import java.nio.file.Files

class JvmSystemMetricsCollectorTest :
    FreeSpec({
        "exports rich bounded core and disk snapshots" {
            val collector = JvmSystemMetricsCollector(Files.createTempDirectory("arc-metrics-disk"))
            val fast = collector.fastSnapshot(includeJvm = true, includeSystem = true)
            val heavy = collector.heavySnapshot(includeDisk = true)
            val names = (fast + heavy).map { it.name }.toSet()

            names shouldContain "arc_jvm_heap_used_bytes"
            names shouldContain "arc_jvm_gc_collection_count"
            names shouldContain "arc_jvm_threads"
            names shouldContain "arc_process_cpu_load_ratio"
            names shouldContain "arc_system_memory_bytes"
            names shouldContain "arc_disk_space_bytes"
            names shouldContain "arc_jvm_deadlocked_threads"
            fast.first { it.name == "arc_application_uptime_seconds" }.value shouldBeGreaterThan 0.0
        }
    })
