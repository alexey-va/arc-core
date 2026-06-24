package ru.arc.core

/**
 * Velocity-side wiring for arc-core (console reporters, future domain event hooks).
 */
object VelocityArcRuntime {
    /**
     * Enables pretty MiniMessage module init/reload/shutdown lines on the proxy console.
     * Call once during plugin startup, before [ModuleRegistry.initAll].
     */
    @JvmStatic
    fun installModuleLifecycleReporting(
        consoleLog: (String) -> Unit,
        logError: (String, Throwable) -> Unit,
    ) {
        ModuleRegistry.lifecycleReporter =
            PrettyModuleLifecycleReporter(consoleLog = consoleLog, logError = logError)
    }
}
