package ru.arc.velocity.core

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask as VelocityScheduledTaskHandle
import com.velocitypowered.api.scheduler.TaskStatus
import ru.arc.core.ScheduledTask
import ru.arc.core.SubtickScheduler
import ru.arc.core.TaskScheduler
import ru.arc.core.TickConstants
import ru.arc.core.durationToMillis
import ru.arc.core.isWholeTicks
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/** Velocity [SubtickScheduler] — tick emulation and native millisecond scheduling. */
class VelocitySubtickScheduler(
    private val server: ProxyServer,
    private val plugin: Any,
) : SubtickScheduler {

    private val ticks = VelocityTaskScheduler(server, plugin)
    private val tasks = CopyOnWriteArrayList<Adapter>()
    private val idGen = AtomicInteger()

    override fun runAsync(task: Runnable): ScheduledTask = ticks.runAsync(task)

    override fun runSync(task: Runnable): ScheduledTask = ticks.runSync(task)

    override fun runLater(delayTicks: Long, task: Runnable): ScheduledTask = ticks.runLater(delayTicks, task)

    override fun runLaterAsync(delayTicks: Long, task: Runnable): ScheduledTask = ticks.runLaterAsync(delayTicks, task)

    override fun runTimer(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        ticks.runTimer(delayTicks, periodTicks, task)

    override fun runTimerAsync(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        ticks.runTimerAsync(delayTicks, periodTicks, task)

    override fun runLater(duration: Duration, task: Runnable): ScheduledTask =
        scheduleMillis(durationToMillis(duration), task)

    override fun runLaterAsync(duration: Duration, task: Runnable): ScheduledTask =
        scheduleMillis(durationToMillis(duration), task)

    override fun runTimer(delay: Duration, period: Duration, task: Runnable): ScheduledTask {
        val delayMs = durationToMillis(delay)
        val periodMs = durationToMillis(period).coerceAtLeast(1)
        if (isWholeTicks(delay) && isWholeTicks(period)) {
            return runTimer(delayMs / TickConstants.TICK_MS, periodMs / TickConstants.TICK_MS, task)
        }
        return track(
            server.scheduler
                .buildTask(plugin, task)
                .delay(delayMs, TimeUnit.MILLISECONDS)
                .repeat(periodMs, TimeUnit.MILLISECONDS)
                .schedule(),
        )
    }

    override fun runTimerAsync(delay: Duration, period: Duration, task: Runnable): ScheduledTask =
        runTimer(delay, period, task)

    override fun cancelAll() {
        tasks.forEach { it.cancel() }
        tasks.clear()
        ticks.cancelAll()
    }

    private fun scheduleMillis(delayMs: Long, task: Runnable): ScheduledTask {
        if (delayMs == 0L) return runSync(task)
        if (delayMs % TickConstants.TICK_MS == 0L) {
            return runLater(delayMs / TickConstants.TICK_MS, task)
        }
        return track(
            server.scheduler
                .buildTask(plugin, task)
                .delay(delayMs, TimeUnit.MILLISECONDS)
                .schedule(),
        )
    }

    private fun track(handle: VelocityScheduledTaskHandle): ScheduledTask {
        val adapter = Adapter(idGen.incrementAndGet(), handle)
        tasks.add(adapter)
        return adapter
    }

    private class Adapter(
        override val id: Int,
        private val delegate: VelocityScheduledTaskHandle,
    ) : ScheduledTask {
        override val isCancelled: Boolean
            get() = delegate.status() == TaskStatus.CANCELLED

        override fun cancel() {
            delegate.cancel()
        }
    }
}
