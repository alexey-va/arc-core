package ru.arc.velocity.core

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask as VelocityScheduledTaskHandle
import com.velocitypowered.api.scheduler.TaskStatus
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Velocity [TaskScheduler] — sync on proxy main thread. */
class VelocityTaskScheduler(
    private val server: ProxyServer,
    private val plugin: Any,
) : TaskScheduler {

    private val tasks = CopyOnWriteArrayList<Adapter>()
    private val idGen = AtomicInteger()

    override fun runAsync(task: Runnable): ScheduledTask =
        track(server.scheduler.buildTask(plugin, task).schedule())

    override fun runSync(task: Runnable): ScheduledTask =
        track(server.scheduler.buildTask(plugin, task).schedule())

    override fun runLater(delayTicks: Long, task: Runnable): ScheduledTask =
        track(
            server.scheduler
                .buildTask(plugin, task)
                .delay(delayTicks * 50, TimeUnit.MILLISECONDS)
                .schedule(),
        )

    override fun runLaterAsync(delayTicks: Long, task: Runnable): ScheduledTask =
        runLater(delayTicks, task)

    override fun runTimer(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        track(
            server.scheduler
                .buildTask(plugin, task)
                .delay(delayTicks * 50, TimeUnit.MILLISECONDS)
                .repeat(periodTicks * 50, TimeUnit.MILLISECONDS)
                .schedule(),
        )

    override fun runTimerAsync(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        runTimer(delayTicks, periodTicks, task)

    override fun cancelAll() {
        tasks.forEach { it.cancel() }
        tasks.clear()
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
