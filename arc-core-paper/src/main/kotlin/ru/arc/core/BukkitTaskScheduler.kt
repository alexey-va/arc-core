package ru.arc.core

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.CopyOnWriteArrayList

/** Production [TaskScheduler] using Bukkit scheduler. */
class BukkitTaskScheduler(private val plugin: Plugin) : TaskScheduler {

    private val tasks = CopyOnWriteArrayList<BukkitScheduledTask>()

    override fun runAsync(task: Runnable): ScheduledTask =
        trackOneShot({ wrapped -> Bukkit.getScheduler().runTaskAsynchronously(plugin, wrapped) }, task)

    override fun runSync(task: Runnable): ScheduledTask =
        trackOneShot({ wrapped -> Bukkit.getScheduler().runTask(plugin, wrapped) }, task)

    override fun runLater(delayTicks: Long, task: Runnable): ScheduledTask =
        trackOneShot({ wrapped -> Bukkit.getScheduler().runTaskLater(plugin, wrapped, delayTicks) }, task)

    override fun runLaterAsync(delayTicks: Long, task: Runnable): ScheduledTask =
        trackOneShot({ wrapped -> Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, wrapped, delayTicks) }, task)

    override fun runTimer(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        trackRepeating(Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks))

    override fun runTimerAsync(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        trackRepeating(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks))

    override fun cancelAll() {
        tasks.forEach { it.cancelTaskOnly() }
        tasks.clear()
    }

    internal fun trackedCount(): Int = tasks.size

    private fun trackOneShot(
        schedule: (Runnable) -> BukkitTask,
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
        val bukkitTask = schedule(wrapped)
        val scheduled = BukkitScheduledTask(bukkitTask, ::untrack)
        tasks.add(scheduled)
        registration.register(scheduled)
        return scheduled
    }

    private fun trackRepeating(bukkitTask: BukkitTask): ScheduledTask {
        val scheduled = BukkitScheduledTask(bukkitTask, ::untrack)
        tasks.add(scheduled)
        return scheduled
    }

    private fun untrack(scheduled: BukkitScheduledTask) {
        tasks.remove(scheduled)
    }

    private class OneShotRegistration {
        private var task: BukkitScheduledTask? = null
        private var completed = false

        fun register(value: BukkitScheduledTask) {
            task = value
            if (completed) {
                value.detach()
            }
        }

        fun onComplete() {
            completed = true
            task?.detach()
        }
    }

    private class BukkitScheduledTask(
        private val task: BukkitTask,
        private val removeFromRegistry: (BukkitScheduledTask) -> Unit,
    ) : ScheduledTask {
        @Volatile
        private var detached = false

        fun detach() {
            if (detached) return
            detached = true
            removeFromRegistry(this)
        }

        override val id: Int get() = task.taskId
        override val isCancelled: Boolean get() = task.isCancelled

        override fun cancel() {
            task.cancel()
            detach()
        }

        fun cancelTaskOnly() {
            task.cancel()
        }
    }
}
