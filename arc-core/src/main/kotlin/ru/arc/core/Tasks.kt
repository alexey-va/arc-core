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
            installed?.close()
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
        installed?.close()
        installed = null
    }

    inline fun <T> withScheduler(testScheduler: TaskScheduler, block: () -> T): T {
        val previous = installed
        install(testScheduler, cancelPrevious = false)
        return try {
            block()
        } finally {
            testScheduler.close()
            if (previous != null) {
                install(previous, cancelPrevious = false)
            } else {
                installed = null
            }
        }
    }
}
