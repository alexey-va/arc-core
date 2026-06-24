package ru.arc.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** In-memory scheduler for unit tests. */
class TestTaskScheduler(
    private val executor: Executor = Executor { it.run() },
) : TaskScheduler {

    private val idCounter = AtomicInteger(0)
    private val pendingTasks = CopyOnWriteArrayList<TestScheduledTask>()
    private val timerTasks = CopyOnWriteArrayList<TimerTask>()
    private var currentTick = 0L

    override fun runAsync(task: Runnable): ScheduledTask = scheduleImmediate(task)

    override fun runSync(task: Runnable): ScheduledTask = scheduleImmediate(task)

    override fun runLater(delay: Long, task: Runnable): ScheduledTask = scheduleDelayed(delay, task)

    override fun runLaterAsync(delay: Long, task: Runnable): ScheduledTask = scheduleDelayed(delay, task)

    override fun runTimer(delay: Long, period: Long, task: Runnable): ScheduledTask =
        scheduleTimer(delay, period, task)

    override fun runTimerAsync(delay: Long, period: Long, task: Runnable): ScheduledTask =
        scheduleTimer(delay, period, task)

    override fun cancelAll() {
        pendingTasks.forEach { it.cancel() }
        timerTasks.forEach { it.scheduledTask.cancel() }
        pendingTasks.clear()
        timerTasks.clear()
    }

    fun executeAll() {
        val toExecute = pendingTasks.filter { !it.isCancelled && it.executeAt <= currentTick }
        toExecute.forEach { task ->
            executor.execute(task.runnable)
            pendingTasks.remove(task)
        }
    }

    fun executeImmediate() {
        val toExecute = pendingTasks.filter { !it.isCancelled && it.executeAt == 0L }
        toExecute.forEach { task ->
            executor.execute(task.runnable)
            pendingTasks.remove(task)
        }
    }

    fun tick(ticks: Long = 1) {
        repeat(ticks.toInt()) {
            currentTick++
            executeAll()
            executeTimers()
        }
    }

    fun pendingCount(): Int = pendingTasks.count { !it.isCancelled }

    fun timerCount(): Int = timerTasks.count { !it.scheduledTask.isCancelled }

    private fun scheduleImmediate(task: Runnable): ScheduledTask {
        val scheduled = TestScheduledTask(idCounter.incrementAndGet(), task, 0L)
        pendingTasks.add(scheduled)
        return scheduled
    }

    private fun scheduleDelayed(delay: Long, task: Runnable): ScheduledTask {
        val scheduled = TestScheduledTask(idCounter.incrementAndGet(), task, currentTick + delay)
        pendingTasks.add(scheduled)
        return scheduled
    }

    private fun scheduleTimer(delay: Long, period: Long, task: Runnable): ScheduledTask {
        val scheduled = TestScheduledTask(idCounter.incrementAndGet(), task, currentTick + delay)
        val timer = TimerTask(scheduled, period, currentTick + delay)
        timerTasks.add(timer)
        return scheduled
    }

    private fun executeTimers() {
        timerTasks.filter { !it.scheduledTask.isCancelled && it.nextExecution <= currentTick }
            .forEach { timer ->
                executor.execute(timer.scheduledTask.runnable)
                timer.nextExecution = currentTick + timer.period
            }
    }

    private data class TimerTask(
        val scheduledTask: TestScheduledTask,
        val period: Long,
        var nextExecution: Long,
    )

    private class TestScheduledTask(
        override val id: Int,
        val runnable: Runnable,
        val executeAt: Long,
    ) : ScheduledTask {
        private var cancelled = false
        override val isCancelled: Boolean get() = cancelled
        override fun cancel() {
            cancelled = true
        }
    }
}
