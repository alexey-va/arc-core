package ru.arc.core

import org.bukkit.plugin.Plugin

/**
 * Paper-side wiring for arc-core (console reporters, scheduling).
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

    /** Installs [PaperSubtickScheduler] as [Tasks.scheduler]. Call before [ModuleRegistry.initAll]. */
    @JvmStatic
    fun installScheduling(plugin: Plugin) {
        Tasks.install(PaperSubtickScheduler(BukkitTaskScheduler(plugin), plugin))
    }
}
