package ru.arc.core

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val moduleLog = LoggerFactory.getLogger(ModuleRegistry::class.java)

/**
 * Central registry for plugin modules — init/reload/shutdown in priority order.
 */
object ModuleRegistry {
    private val modules = mutableListOf<PluginModule>()
    private val initializedModules = mutableListOf<PluginModule>()
    private val runtimeStatuses = ConcurrentHashMap<String, ModuleRuntimeStatus>()
    private var initialized = false

    /** Set before [initAll] for platform-specific console output. */
    var lifecycleReporter: ModuleLifecycleReporter = NoOpModuleLifecycleReporter

    fun register(module: PluginModule) {
        if (initialized) {
            moduleLog.error("Cannot register module '{}' after initialization", module.name)
            return
        }
        if (modules.any { it.name == module.name }) {
            moduleLog.error("Module '{}' is already registered", module.name)
            return
        }
        modules.add(module)
        if (module.enabled) {
            runtimeStatuses.putIfAbsent(module.name, ModuleRuntimeStatus(module.name))
        }
    }

    fun registerAll(vararg modulesToRegister: PluginModule) {
        modulesToRegister.forEach { register(it) }
    }

    fun initAll() {
        if (initialized) {
            moduleLog.error("ModuleRegistry already initialized")
            return
        }
        val sorted = modules.filter { it.enabled }.sortedBy { it.priority }
        val reporter = lifecycleReporter
        reporter.onInitStart(sorted.size)

        data class Result(val name: String, val ms: Long, val error: Exception?)

        val startAll = System.currentTimeMillis()
        val results =
            sorted.map { module ->
                val start = System.currentTimeMillis()
                try {
                    module.init()
                    initializedModules.add(module)
                    val elapsed = System.currentTimeMillis() - start
                    runtimeStatuses.compute(module.name) { _, previous ->
                        (previous ?: ModuleRuntimeStatus(module.name)).copy(
                            ready = true,
                            initDurationMs = elapsed,
                        )
                    }
                    Result(module.name, elapsed, null)
                } catch (e: Exception) {
                    try {
                        module.shutdown()
                    } catch (cleanupError: Exception) {
                        e.addSuppressed(cleanupError)
                        moduleLog.error("Module '${module.name}' cleanup after failed init also failed", cleanupError)
                    }
                    val elapsed = System.currentTimeMillis() - start
                    runtimeStatuses.compute(module.name) { _, previous ->
                        (previous ?: ModuleRuntimeStatus(module.name)).copy(
                            ready = false,
                            initDurationMs = elapsed,
                            failures = (previous?.failures ?: 0) + 1,
                        )
                    }
                    Result(module.name, elapsed, e)
                }
            }
        val totalMs = System.currentTimeMillis() - startAll
        val nameWidth = results.maxOfOrNull { it.name.length }?.coerceAtLeast(12) ?: 12

        for (r in results) {
            when (val error = r.error) {
                null -> reporter.onInitModuleSuccess(r.name, nameWidth, r.ms)
                else -> reporter.onInitModuleFailure(r.name, nameWidth, r.ms, error)
            }
        }

        val failed = results.count { it.error != null }
        reporter.onInitComplete(results.size - failed, failed, totalMs)
        initialized = true
    }

    fun reloadAll() {
        if (!initialized) {
            moduleLog.error("Cannot reload ModuleRegistry before initialization")
            return
        }
        val sorted = initializedModules.toList()
        val reporter = lifecycleReporter
        reporter.onReloadStart(sorted.size)
        for (module in sorted) {
            reloadInitializedModule(module)
        }
        reporter.onReloadComplete()
    }

    /** Reload one initialized module while preserving lifecycle telemetry. */
    fun reload(module: PluginModule): Boolean {
        if (!initialized || module !in initializedModules) {
            moduleLog.error("Cannot reload uninitialized module '{}'", module.name)
            return false
        }
        return reloadInitializedModule(module)
    }

    private fun reloadInitializedModule(module: PluginModule): Boolean {
        val start = System.currentTimeMillis()
        return try {
            module.reload()
            val elapsed = System.currentTimeMillis() - start
            runtimeStatuses.compute(module.name) { _, previous ->
                (previous ?: ModuleRuntimeStatus(module.name)).copy(
                    ready = true,
                    reloadDurationMs = elapsed,
                )
            }
            lifecycleReporter.onReloadSuccess(module.name)
            true
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            runtimeStatuses.compute(module.name) { _, previous ->
                (previous ?: ModuleRuntimeStatus(module.name)).copy(
                    ready = false,
                    reloadDurationMs = elapsed,
                    failures = (previous?.failures ?: 0) + 1,
                )
            }
            lifecycleReporter.onReloadFailure(module.name, e)
            moduleLog.error("Module '${module.name}' reload failed", e)
            false
        }
    }

    fun shutdownAll() {
        val sorted = initializedModules.asReversed()
        val reporter = lifecycleReporter
        reporter.onShutdownStart(sorted.size)
        for (module in sorted) {
            val start = System.currentTimeMillis()
            try {
                module.shutdown()
                val elapsed = System.currentTimeMillis() - start
                runtimeStatuses.compute(module.name) { _, previous ->
                    (previous ?: ModuleRuntimeStatus(module.name)).copy(
                        ready = false,
                        shutdownDurationMs = elapsed,
                    )
                }
                reporter.onShutdownSuccess(module.name)
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - start
                runtimeStatuses.compute(module.name) { _, previous ->
                    (previous ?: ModuleRuntimeStatus(module.name)).copy(
                        ready = false,
                        shutdownDurationMs = elapsed,
                        failures = (previous?.failures ?: 0) + 1,
                    )
                }
                reporter.onShutdownFailure(module.name, e)
                moduleLog.error("Module '${module.name}' shutdown failed", e)
            }
        }
        initializedModules.clear()
        modules.clear()
        initialized = false
        reporter.onShutdownComplete()
    }

    fun getModules(): List<PluginModule> = modules.toList()

    fun getRuntimeStatuses(): List<ModuleRuntimeStatus> = runtimeStatuses.values.sortedBy { it.name }

    /** Test-only reset. */
    fun resetForTests() {
        initializedModules.clear()
        modules.clear()
        runtimeStatuses.clear()
        initialized = false
        lifecycleReporter = NoOpModuleLifecycleReporter
    }
}
