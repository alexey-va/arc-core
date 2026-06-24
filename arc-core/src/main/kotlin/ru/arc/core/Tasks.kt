package ru.arc.core

/** Global task scheduler — swap in tests via [withScheduler]. */
object Tasks {
    @Volatile
    var scheduler: TaskScheduler = ExecutorTaskScheduler()

    fun reset() {
        scheduler.cancelAll()
        scheduler = ExecutorTaskScheduler()
    }

    inline fun <T> withScheduler(testScheduler: TaskScheduler, block: () -> T): T {
        val previous = scheduler
        scheduler = testScheduler
        return try {
            block()
        } finally {
            scheduler.cancelAll()
            scheduler = previous
        }
    }
}

fun sync(task: Runnable): ScheduledTask = Tasks.scheduler.runSync(task)

fun async(task: Runnable): ScheduledTask = Tasks.scheduler.runAsync(task)

fun delayed(delayTicks: Long, task: Runnable): ScheduledTask =
    Tasks.scheduler.runLater(delayTicks, task)

inline fun delayed(delayTicks: Long, crossinline block: () -> Unit): ScheduledTask =
    delayed(delayTicks, Runnable { block() })

fun repeating(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
    Tasks.scheduler.runTimer(delayTicks, periodTicks, task)

inline fun repeating(delayTicks: Long, periodTicks: Long, crossinline block: () -> Unit): ScheduledTask =
    repeating(delayTicks, periodTicks, Runnable { block() })
