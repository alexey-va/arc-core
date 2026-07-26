package ru.arc.core

import org.slf4j.LoggerFactory

private val moduleLog = LoggerFactory.getLogger(ModuleRegistry::class.java)

/**
 * Central registry for plugin modules — init/reload/shutdown in priority order.
 */
object ModuleRegistry {
    private val modules = mutableListOf<PluginModule>()
    private val initializedModules = mutableListOf<PluginModule>()
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
                    Result(module.name, System.currentTimeMillis() - start, null)
                } catch (e: Exception) {
                    try {
                        module.shutdown()
                    } catch (cleanupError: Exception) {
                        e.addSuppressed(cleanupError)
                        moduleLog.error("Module '${module.name}' cleanup after failed init also failed", cleanupError)
                    }
                    Result(module.name, System.currentTimeMillis() - start, e)
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
            try {
                module.reload()
                reporter.onReloadSuccess(module.name)
            } catch (e: Exception) {
                reporter.onReloadFailure(module.name, e)
                moduleLog.error("Module '${module.name}' reload failed", e)
            }
        }
        reporter.onReloadComplete()
    }

    fun shutdownAll() {
        val sorted = initializedModules.asReversed()
        val reporter = lifecycleReporter
        reporter.onShutdownStart(sorted.size)
        for (module in sorted) {
            try {
                module.shutdown()
                reporter.onShutdownSuccess(module.name)
            } catch (e: Exception) {
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

    /** Test-only reset. */
    fun resetForTests() {
        initializedModules.clear()
        modules.clear()
        initialized = false
        lifecycleReporter = NoOpModuleLifecycleReporter
    }
}
