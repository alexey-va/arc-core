package ru.arc.core

import org.bukkit.plugin.Plugin
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/** Paper [SubtickScheduler] — Bukkit ticks plus millisecond-precision hops to the main thread. */
class PaperSubtickScheduler(
    private val delegate: BukkitTaskScheduler,
    private val plugin: Plugin,
) : SubtickScheduler {

    private val subtickExecutor =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "arc-paper-subtick").apply { isDaemon = true }
        }
    private val subtickTasks = CopyOnWriteArrayList<SubtickHandle>()
    private val idGen = AtomicInteger()

    override fun runAsync(task: Runnable): ScheduledTask = delegate.runAsync(task)

    override fun runSync(task: Runnable): ScheduledTask = delegate.runSync(task)

    override fun runLater(delayTicks: Long, task: Runnable): ScheduledTask = delegate.runLater(delayTicks, task)

    override fun runLaterAsync(delayTicks: Long, task: Runnable): ScheduledTask = delegate.runLaterAsync(delayTicks, task)

    override fun runTimer(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        delegate.runTimer(delayTicks, periodTicks, task)

    override fun runTimerAsync(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        delegate.runTimerAsync(delayTicks, periodTicks, task)

    override fun runLater(duration: Duration, task: Runnable): ScheduledTask {
        val ms = durationToMillis(duration)
        if (ms == 0L) return runSync(task)
        if (isWholeTicks(duration)) {
            return runLater(ms / TickConstants.TICK_MS, task)
        }
        return scheduleSubtick(ms, 0L, sync = true, repeating = false, task)
    }

    override fun runLaterAsync(duration: Duration, task: Runnable): ScheduledTask {
        val ms = durationToMillis(duration)
        if (ms == 0L) return runAsync(task)
        if (isWholeTicks(duration)) {
            return runLaterAsync(ms / TickConstants.TICK_MS, task)
        }
        return scheduleSubtick(ms, 0L, sync = false, repeating = false, task)
    }

    override fun runTimer(delay: Duration, period: Duration, task: Runnable): ScheduledTask {
        val delayMs = durationToMillis(delay)
        val periodMs = durationToMillis(period).coerceAtLeast(1)
        if (isWholeTicks(delay) && isWholeTicks(period)) {
            return runTimer(delayMs / TickConstants.TICK_MS, periodMs / TickConstants.TICK_MS, task)
        }
        return scheduleSubtick(delayMs, periodMs, sync = true, repeating = true, task)
    }

    override fun runTimerAsync(delay: Duration, period: Duration, task: Runnable): ScheduledTask {
        val delayMs = durationToMillis(delay)
        val periodMs = durationToMillis(period).coerceAtLeast(1)
        if (isWholeTicks(delay) && isWholeTicks(period)) {
            return runTimerAsync(delayMs / TickConstants.TICK_MS, periodMs / TickConstants.TICK_MS, task)
        }
        return scheduleSubtick(delayMs, periodMs, sync = false, repeating = true, task)
    }

    override fun cancelAll() {
        subtickTasks.forEach { it.cancel() }
        subtickTasks.clear()
        delegate.cancelAll()
    }

    private fun scheduleSubtick(
        delayMs: Long,
        periodMs: Long,
        sync: Boolean,
        repeating: Boolean,
        task: Runnable,
    ): ScheduledTask {
        val handle = SubtickHandle(idGen.incrementAndGet())
        subtickTasks.add(handle)
        val runnable =
            Runnable {
                if (handle.isCancelled) return@Runnable
                if (sync) {
                    delegate.runSync(task)
                } else {
                    task.run()
                }
            }
        handle.future =
            if (repeating) {
                subtickExecutor.scheduleAtFixedRate(runnable, delayMs, periodMs, TimeUnit.MILLISECONDS)
            } else {
                subtickExecutor.schedule(runnable, delayMs, TimeUnit.MILLISECONDS)
            }
        return handle
    }

    private class SubtickHandle(override val id: Int) : ScheduledTask {
        @Volatile
        var future: java.util.concurrent.ScheduledFuture<*>? = null

        @Volatile
        private var cancelled = false

        override val isCancelled: Boolean get() = cancelled

        override fun cancel() {
            cancelled = true
            future?.cancel(false)
        }
    }
}
