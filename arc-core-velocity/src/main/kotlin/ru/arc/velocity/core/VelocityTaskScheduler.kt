package ru.arc.velocity.core

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask as VelocityScheduledTaskHandle
import com.velocitypowered.api.scheduler.TaskStatus
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.TickConstants
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
        trackOneShot({ wrapped -> server.scheduler.buildTask(plugin, wrapped).schedule() }, task)

    override fun runSync(task: Runnable): ScheduledTask =
        trackOneShot({ wrapped -> server.scheduler.buildTask(plugin, wrapped).schedule() }, task)

    override fun runLater(delayTicks: Long, task: Runnable): ScheduledTask =
        trackOneShot(
            { wrapped ->
                server.scheduler
                    .buildTask(plugin, wrapped)
                    .delay(delayTicks * TickConstants.TICK_MS, TimeUnit.MILLISECONDS)
                    .schedule()
            },
            task,
        )

    override fun runLaterAsync(delayTicks: Long, task: Runnable): ScheduledTask = runLater(delayTicks, task)

    override fun runTimer(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        trackRepeating(
            server.scheduler
                .buildTask(plugin, task)
                .delay(delayTicks * TickConstants.TICK_MS, TimeUnit.MILLISECONDS)
                .repeat(periodTicks * TickConstants.TICK_MS, TimeUnit.MILLISECONDS)
                .schedule(),
        )

    override fun runTimerAsync(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        runTimer(delayTicks, periodTicks, task)

    override fun cancelAll() {
        tasks.forEach { it.cancelTaskOnly() }
        tasks.clear()
    }

    internal fun trackedCount(): Int = tasks.size

    private fun trackOneShot(
        schedule: (Runnable) -> VelocityScheduledTaskHandle,
        task: Runnable,
    ): ScheduledTask {
        val registration = OneShotRegistration()
        val wrapped =
            Runnable {
                try {
                    task.run()
                } finally {
                    registration.onComplete()
                }
            }
        val handle = schedule(wrapped)
        val adapter = Adapter(idGen.incrementAndGet(), handle, ::untrack)
        tasks.add(adapter)
        registration.register(adapter)
        return adapter
    }

    private fun trackRepeating(handle: VelocityScheduledTaskHandle): ScheduledTask {
        val adapter = Adapter(idGen.incrementAndGet(), handle, ::untrack)
        tasks.add(adapter)
        return adapter
    }

    private fun untrack(adapter: Adapter) {
        tasks.remove(adapter)
    }

    private class OneShotRegistration {
        private var adapter: Adapter? = null
        private var completed = false

        fun register(value: Adapter) {
            adapter = value
            if (completed) {
                value.detach()
            }
        }

        fun onComplete() {
            completed = true
            adapter?.detach()
        }
    }

    private class Adapter(
        override val id: Int,
        private val delegate: VelocityScheduledTaskHandle,
        private val removeFromRegistry: (Adapter) -> Unit,
    ) : ScheduledTask {
        @Volatile
        private var detached = false

        fun detach() {
            if (detached) return
            detached = true
            removeFromRegistry(this)
        }

        override val isCancelled: Boolean
            get() = delegate.status() == TaskStatus.CANCELLED

        override fun cancel() {
            delegate.cancel()
            detach()
        }

        fun cancelTaskOnly() {
            delegate.cancel()
        }
    }
}
