package ru.arc.core

/**
 * Plugin feature module with standardized lifecycle.
 * Lifecycle: init() → [reload()] → shutdown()
 */
interface PluginModule {
    val name: String
    val priority: Int get() = 100
    val enabled: Boolean get() = true

    fun init()

    fun reload() {
        shutdown()
        init()
    }

    fun shutdown()
}
