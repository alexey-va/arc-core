package ru.arc.core

/**
 * Paper-side wiring for arc-core (console reporters, future TaskDsl/EventBus hooks).
 */
object PaperArcRuntime {
    /**
     * Enables pretty MiniMessage module init/reload/shutdown lines on the server console.
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
