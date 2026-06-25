package ru.arc.core

/** Global task scheduler — install once at plugin bootstrap via [install]. */
object Tasks {
    @PublishedApi
    @Volatile
    internal var installed: TaskScheduler? = null

    fun install(
        scheduler: TaskScheduler,
        cancelPrevious: Boolean = true,
    ) {
        if (cancelPrevious) {
            installed?.cancelAll()
        }
        installed = scheduler
    }

    val scheduler: TaskScheduler
        get() =
            installed
                ?: error(
                    "Tasks.install() not called — use PaperArcRuntime.installScheduling() " +
                        "or VelocityArcRuntime.installScheduling() before ModuleRegistry.initAll()",
                )

    fun reset() {
        installed = null
    }

    inline fun <T> withScheduler(testScheduler: TaskScheduler, block: () -> T): T {
        val previous = installed
        install(testScheduler)
        return try {
            block()
        } finally {
            if (previous != null) {
                install(previous, cancelPrevious = false)
            } else {
                installed = null
            }
        }
    }
}
