package ru.arc.core

import org.slf4j.LoggerFactory

private val moduleLog = LoggerFactory.getLogger(ModuleRegistry::class.java)

/**
 * Central registry for plugin modules — init/reload/shutdown in priority order.
 */
object ModuleRegistry {
    private val modules = mutableListOf<PluginModule>()
    private var initialized = false

    fun register(module: PluginModule) {
        if (initialized) {
            moduleLog.error("Cannot register module '{}' after initialization", module.name)
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
        moduleLog.debug("Initializing {} modules", sorted.size)
        for (module in sorted) {
            val start = System.currentTimeMillis()
            try {
                module.init()
                moduleLog.debug("Module '{}' ready ({}ms)", module.name, System.currentTimeMillis() - start)
            } catch (e: Exception) {
                moduleLog.error("Module '${module.name}' failed to initialize", e)
            }
        }
        initialized = true
    }

    fun reloadAll() {
        modules.filter { it.enabled }.sortedBy { it.priority }.forEach { module ->
            try {
                module.reload()
            } catch (e: Exception) {
                moduleLog.error("Module '${module.name}' reload failed", e)
            }
        }
    }

    fun shutdownAll() {
        modules.filter { it.enabled }.sortedByDescending { it.priority }.forEach { module ->
            try {
                module.shutdown()
            } catch (e: Exception) {
                moduleLog.error("Module '${module.name}' shutdown failed", e)
            }
        }
        modules.clear()
        initialized = false
    }

    fun getModules(): List<PluginModule> = modules.toList()

    /** Test-only reset. */
    internal fun resetForTests() {
        modules.clear()
        initialized = false
    }
}
