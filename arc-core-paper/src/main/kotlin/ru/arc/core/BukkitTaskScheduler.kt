package ru.arc.core

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.CopyOnWriteArrayList

/** Production [TaskScheduler] using Bukkit scheduler. */
class BukkitTaskScheduler(private val plugin: Plugin) : TaskScheduler {

    private val tasks = CopyOnWriteArrayList<BukkitScheduledTask>()

    override fun runAsync(task: Runnable): ScheduledTask =
        track(Bukkit.getScheduler().runTaskAsynchronously(plugin, task))

    override fun runSync(task: Runnable): ScheduledTask =
        track(Bukkit.getScheduler().runTask(plugin, task))

    override fun runLater(delay: Long, task: Runnable): ScheduledTask =
        track(Bukkit.getScheduler().runTaskLater(plugin, task, delay))

    override fun runLaterAsync(delay: Long, task: Runnable): ScheduledTask =
        track(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay))

    override fun runTimer(delay: Long, period: Long, task: Runnable): ScheduledTask =
        track(Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period))

    override fun runTimerAsync(delay: Long, period: Long, task: Runnable): ScheduledTask =
        track(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period))

    override fun cancelAll() {
        tasks.forEach { it.cancel() }
        tasks.clear()
    }

    private fun track(bukkitTask: BukkitTask): ScheduledTask {
        val scheduled = BukkitScheduledTask(bukkitTask)
        tasks.add(scheduled)
        return scheduled
    }

    private class BukkitScheduledTask(private val task: BukkitTask) : ScheduledTask {
        override val id: Int get() = task.taskId
        override val isCancelled: Boolean get() = task.isCancelled
        override fun cancel() = task.cancel()
    }
}
