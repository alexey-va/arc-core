package ru.arc.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * [TaskScheduler] with millisecond / [Duration] precision.
 *
 * Paper uses Bukkit for whole ticks and executor hops for subtick;
 * Velocity maps directly to millisecond scheduling.
 */
interface SubtickScheduler : TaskScheduler {
    fun runLater(duration: Duration, task: Runnable): ScheduledTask

    fun runLaterAsync(duration: Duration, task: Runnable): ScheduledTask

    fun runTimer(delay: Duration, period: Duration, task: Runnable): ScheduledTask

    fun runTimerAsync(delay: Duration, period: Duration, task: Runnable): ScheduledTask
}

fun durationToTicks(duration: Duration, tickMs: Long = TickConstants.TICK_MS): Long {
    val ms = duration.inWholeMilliseconds.coerceAtLeast(0)
    return if (ms == 0L) 0L else (ms + tickMs - 1) / tickMs
}

fun isWholeTicks(duration: Duration, tickMs: Long = TickConstants.TICK_MS): Boolean {
    val ms = duration.inWholeMilliseconds
    return ms == 0L || ms % tickMs == 0L
}

fun durationToMillis(duration: Duration): Long = duration.inWholeMilliseconds.coerceAtLeast(0)

fun millisToDuration(ms: Long): Duration = ms.coerceAtLeast(0).milliseconds

/** Runs [task] after [delay], using subtick API when [scheduler] supports it and delay is not whole ticks. */
fun scheduleDelayed(
    scheduler: TaskScheduler,
    delay: Duration,
    async: Boolean,
    task: Runnable,
): ScheduledTask {
    val subtick = scheduler as? SubtickScheduler
    if (subtick != null && !isWholeTicks(delay)) {
        return if (async) subtick.runLaterAsync(delay, task) else subtick.runLater(delay, task)
    }
    val ticks = durationToTicks(delay)
    return if (async) scheduler.runLaterAsync(ticks, task) else scheduler.runLater(ticks, task)
}
