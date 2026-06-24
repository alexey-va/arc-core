package ru.arc.core

import ru.arc.util.Logging

/**
 * Central registry for plugin modules — init/reload/shutdown in priority order.
 */
object ModuleRegistry {
    private val modules = mutableListOf<PluginModule>()
    private var initialized = false

    fun register(module: PluginModule) {
        if (initialized) {
            Logging.error("Cannot register module '{}' after initialization", module.name)
            return
        }
        modules.add(module)
    }

    fun registerAll(vararg modulesToRegister: PluginModule) {
        modulesToRegister.forEach { register(it) }
    }

    fun initAll() {
        if (initialized) {
            Logging.error("ModuleRegistry already initialized")
            return
        }
        val sorted = modules.filter { it.enabled }.sortedBy { it.priority }
        Logging.debug("Initializing {} modules", sorted.size)
        for (module in sorted) {
            val start = System.currentTimeMillis()
            try {
                module.init()
                Logging.debug("Module '{}' ready ({}ms)", module.name, System.currentTimeMillis() - start)
            } catch (e: Exception) {
                Logging.error("Module '${module.name}' failed to initialize", e)
            }
        }
        initialized = true
    }

    fun reloadAll() {
        modules.filter { it.enabled }.sortedBy { it.priority }.forEach { module ->
            try {
                module.reload()
            } catch (e: Exception) {
                Logging.error("Module '${module.name}' reload failed", e)
            }
        }
    }

    fun shutdownAll() {
        modules.filter { it.enabled }.sortedByDescending { it.priority }.forEach { module ->
            try {
                module.shutdown()
            } catch (e: Exception) {
                Logging.error("Module '${module.name}' shutdown failed", e)
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
