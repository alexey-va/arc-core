package ru.arc.core

/**
 * Optional hooks for module init/reload/shutdown — used for pretty console output on Paper/Velocity.
 * Default is [NoOpModuleLifecycleReporter] so unit tests stay quiet.
 */
interface ModuleLifecycleReporter {
    fun onInitStart(moduleCount: Int) {}

    fun onInitModuleSuccess(name: String, nameWidth: Int, ms: Long) {}

    fun onInitModuleFailure(name: String, nameWidth: Int, ms: Long, error: Exception) {}

    fun onInitComplete(ok: Int, failed: Int, totalMs: Long) {}

    fun onReloadStart(moduleCount: Int) {}

    fun onReloadSuccess(name: String) {}

    fun onReloadFailure(name: String, error: Exception) {}

    fun onReloadComplete() {}

    fun onShutdownStart(moduleCount: Int) {}

    fun onShutdownSuccess(name: String) {}

    fun onShutdownFailure(name: String, error: Exception) {}

    fun onShutdownComplete() {}
}

object NoOpModuleLifecycleReporter : ModuleLifecycleReporter
