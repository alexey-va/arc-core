package ru.arc.core

import com.velocitypowered.api.proxy.ProxyServer

/**
 * Velocity-side wiring for arc-core (console reporters, scheduling).
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

    /** Installs [VelocitySubtickScheduler] as [Tasks.scheduler]. Call before [ModuleRegistry.initAll]. */
    @JvmStatic
    fun installScheduling(server: ProxyServer, plugin: Any) {
        Tasks.install(ru.arc.velocity.core.VelocitySubtickScheduler(server, plugin))
    }
}
