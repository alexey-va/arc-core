package ru.arc.metrics.core

import com.sun.management.OperatingSystemMXBean
import com.sun.management.UnixOperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/** Produces bounded-cardinality JVM, process, host, and plugin-data disk snapshots. */
class JvmSystemMetricsCollector(
    private val dataPath: Path,
) {
    private val memory = ManagementFactory.getMemoryMXBean()
    private val threads = ManagementFactory.getThreadMXBean()
    private val classes = ManagementFactory.getClassLoadingMXBean()
    private val runtime = ManagementFactory.getRuntimeMXBean()
    private val operatingSystem = ManagementFactory.getOperatingSystemMXBean()

    fun fastSnapshot(
        includeJvm: Boolean,
        includeSystem: Boolean,
    ): List<MetricPoint> =
        buildList {
            add(point("arc_application_uptime_seconds", "Application uptime", runtime.uptime / 1_000.0))
            add(
                point(
                    "arc_application_start_time_seconds",
                    "Application start time since Unix epoch",
                    runtime.startTime / 1_000.0,
                ),
            )
            add(
                point(
                    "arc_runtime_available_processors",
                    "Processors available to the JVM",
                    operatingSystem.availableProcessors.toDouble(),
                ),
            )

            if (includeJvm) addJvmMetrics()
            if (includeSystem) addSystemMetrics()
        }

    fun heavySnapshot(includeDisk: Boolean): List<MetricPoint> =
        buildList {
            val deadlocked = threads.findDeadlockedThreads()?.size ?: 0
            add(point("arc_jvm_deadlocked_threads", "Detected JVM deadlocked threads", deadlocked.toDouble()))
            if (includeDisk) addDiskMetrics()
        }

    private fun MutableList<MetricPoint>.addJvmMetrics() {
        val heap = memory.heapMemoryUsage
        val nonHeap = memory.nonHeapMemoryUsage

        add(memoryPoint("heap", "used", heap.used))
        add(memoryPoint("heap", "committed", heap.committed))
        add(memoryPoint("heap", "max", heap.max))
        add(memoryPoint("nonheap", "used", nonHeap.used))
        add(memoryPoint("nonheap", "committed", nonHeap.committed))
        add(memoryPoint("nonheap", "max", nonHeap.max))

        // Compatibility names already used by RusCrafting dashboards and alerts.
        add(point("arc_jvm_heap_used_bytes", "JVM heap memory used", number(heap.used)))
        add(point("arc_jvm_heap_max_bytes", "JVM maximum heap memory", number(heap.max)))
        add(point("arc_jvm_nonheap_used_bytes", "JVM non-heap memory used", number(nonHeap.used)))

        add(threadPoint("live", threads.threadCount.toDouble()))
        add(threadPoint("daemon", threads.daemonThreadCount.toDouble()))
        add(threadPoint("peak", threads.peakThreadCount.toDouble()))
        add(classPoint("loaded", classes.loadedClassCount.toDouble()))
        add(classPoint("unloaded_total", classes.unloadedClassCount.toDouble()))

        for (collector in ManagementFactory.getGarbageCollectorMXBeans()) {
            val tags = mapOf("gc" to collector.name)
            add(
                point(
                    "arc_jvm_gc_collection_count",
                    "JVM garbage-collection cycles",
                    number(collector.collectionCount),
                    tags,
                ),
            )
            add(
                point(
                    "arc_jvm_gc_collection_time_seconds",
                    "JVM time spent in garbage collection",
                    number(collector.collectionTime) / 1_000.0,
                    tags,
                ),
            )
        }

        for (pool in ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean::class.java)) {
            val tags = mapOf("pool" to pool.name)
            add(point("arc_jvm_buffer_pool_count", "JVM buffer count", number(pool.count), tags))
            add(
                point(
                    "arc_jvm_buffer_pool_used_bytes",
                    "JVM buffer-pool memory used",
                    number(pool.memoryUsed),
                    tags,
                ),
            )
            add(
                point(
                    "arc_jvm_buffer_pool_capacity_bytes",
                    "JVM buffer-pool total capacity",
                    number(pool.totalCapacity),
                    tags,
                ),
            )
        }
    }

    private fun MutableList<MetricPoint>.addSystemMetrics() {
        add(
            point(
                "arc_system_load_average",
                "System load average for the last minute",
                operatingSystem.systemLoadAverage,
            ),
        )
        val extended = operatingSystem as? OperatingSystemMXBean ?: return
        add(point("arc_process_cpu_load_ratio", "Recent JVM process CPU load from 0 to 1", ratio(extended.processCpuLoad)))
        add(point("arc_system_cpu_load_ratio", "Recent host CPU load from 0 to 1", ratio(extended.cpuLoad)))
        add(
            point(
                "arc_process_cpu_time_seconds",
                "CPU time used by the JVM process",
                number(extended.processCpuTime) / 1_000_000_000.0,
            ),
        )
        add(
            point(
                "arc_process_virtual_memory_bytes",
                "Virtual memory committed to the JVM process",
                number(extended.committedVirtualMemorySize),
            ),
        )
        add(systemMemoryPoint("physical_total", extended.totalMemorySize))
        add(systemMemoryPoint("physical_free", extended.freeMemorySize))
        add(systemMemoryPoint("swap_total", extended.totalSwapSpaceSize))
        add(systemMemoryPoint("swap_free", extended.freeSwapSpaceSize))

        val unix = extended as? UnixOperatingSystemMXBean ?: return
        add(
            point(
                "arc_process_open_file_descriptors",
                "Open file descriptors held by the JVM process",
                number(unix.openFileDescriptorCount),
            ),
        )
        add(
            point(
                "arc_process_max_file_descriptors",
                "Maximum file descriptors available to the JVM process",
                number(unix.maxFileDescriptorCount),
            ),
        )
    }

    private fun MutableList<MetricPoint>.addDiskMetrics() {
        val store = Files.getFileStore(dataPath)
        val tags = mapOf("path" to "plugin_data")
        add(point("arc_disk_space_bytes", "Filesystem capacity", number(store.totalSpace), tags + ("kind" to "total")))
        add(point("arc_disk_space_bytes", "Filesystem capacity", number(store.usableSpace), tags + ("kind" to "usable")))
        add(
            point(
                "arc_disk_space_bytes",
                "Filesystem capacity",
                number(store.unallocatedSpace),
                tags + ("kind" to "unallocated"),
            ),
        )
    }

    private fun memoryPoint(
        area: String,
        kind: String,
        value: Long,
    ) = point(
        "arc_jvm_memory_bytes",
        "JVM memory by area and allocation kind",
        number(value),
        mapOf("area" to area, "kind" to kind),
    )

    private fun threadPoint(
        state: String,
        value: Double,
    ) = point("arc_jvm_threads", "JVM thread count", value, mapOf("state" to state))

    private fun classPoint(
        state: String,
        value: Double,
    ) = point("arc_jvm_classes", "JVM class-loading count", value, mapOf("state" to state))

    private fun systemMemoryPoint(
        kind: String,
        value: Long,
    ) = point("arc_system_memory_bytes", "Host memory by kind", number(value), mapOf("kind" to kind))

    private fun point(
        name: String,
        description: String,
        value: Double,
        tags: Map<String, String> = emptyMap(),
    ) = MetricPoint(name, description, value, tags)

    private fun number(value: Long): Double = if (value < 0L) Double.NaN else value.toDouble()

    private fun ratio(value: Double): Double = if (value < 0.0) Double.NaN else value.coerceIn(0.0, 1.0)
}
