package ru.arc.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

import kotlin.time.Duration

/** In-memory scheduler for unit tests. */
class TestTaskScheduler(
    private val executor: Executor = Executor { it.run() },
) : SubtickScheduler {

    private val idCounter = AtomicInteger(0)
    private val pendingTasks = CopyOnWriteArrayList<TestScheduledTask>()
    private val timerTasks = CopyOnWriteArrayList<TimerTask>()
    private var currentTimeMs = 0L

    override fun runAsync(task: Runnable): ScheduledTask = scheduleImmediate(task)

    override fun runSync(task: Runnable): ScheduledTask = scheduleImmediate(task)

    override fun runLater(delayTicks: Long, task: Runnable): ScheduledTask =
        scheduleDelayed(TickConstants.ticksToMillis(delayTicks), task)

    override fun runLaterAsync(delayTicks: Long, task: Runnable): ScheduledTask =
        scheduleDelayed(TickConstants.ticksToMillis(delayTicks), task)

    override fun runTimer(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        scheduleTimer(TickConstants.ticksToMillis(delayTicks), TickConstants.ticksToMillis(periodTicks), task)

    override fun runTimerAsync(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        scheduleTimer(TickConstants.ticksToMillis(delayTicks), TickConstants.ticksToMillis(periodTicks), task)

    override fun runLater(duration: Duration, task: Runnable): ScheduledTask =
        scheduleDelayed(durationToMillis(duration), task)

    override fun runLaterAsync(duration: Duration, task: Runnable): ScheduledTask =
        scheduleDelayed(durationToMillis(duration), task)

    override fun runTimer(delay: Duration, period: Duration, task: Runnable): ScheduledTask =
        scheduleTimer(durationToMillis(delay), durationToMillis(period).coerceAtLeast(1), task)

    override fun runTimerAsync(delay: Duration, period: Duration, task: Runnable): ScheduledTask =
        runTimer(delay, period, task)

    override fun cancelAll() {
        pendingTasks.forEach { it.cancel() }
        timerTasks.forEach { it.scheduledTask.cancel() }
        pendingTasks.clear()
        timerTasks.clear()
    }

    fun executeAll() {
        val toExecute = pendingTasks.filter { !it.isCancelled && it.executeAtMs <= currentTimeMs }
        toExecute.forEach { task ->
            executor.execute(task.runnable)
            pendingTasks.remove(task)
        }
    }

    fun executeImmediate() {
        val toExecute = pendingTasks.filter { !it.isCancelled && it.executeAtMs == 0L }
        toExecute.forEach { task ->
            executor.execute(task.runnable)
            pendingTasks.remove(task)
        }
    }

    fun tick(ticks: Long = 1) {
        repeat(ticks.toInt()) {
            currentTimeMs += TickConstants.TICK_MS
            executeAll()
            executeTimersStep()
        }
    }

    fun advanceMs(millis: Long) {
        if (millis <= 0) {
            executeImmediate()
            return
        }
        currentTimeMs += millis
        executeAll()
        executeTimersCatchUp()
    }

    fun pendingCount(): Int = pendingTasks.count { !it.isCancelled }

    fun timerCount(): Int = timerTasks.count { !it.scheduledTask.isCancelled }

    private fun scheduleImmediate(task: Runnable): ScheduledTask {
        val scheduled = TestScheduledTask(idCounter.incrementAndGet(), task, 0L)
        pendingTasks.add(scheduled)
        return scheduled
    }

    private fun scheduleDelayed(delayMs: Long, task: Runnable): ScheduledTask {
        val scheduled = TestScheduledTask(idCounter.incrementAndGet(), task, currentTimeMs + delayMs)
        pendingTasks.add(scheduled)
        return scheduled
    }

    private fun scheduleTimer(delayMs: Long, periodMs: Long, task: Runnable): ScheduledTask {
        val scheduled = TestScheduledTask(idCounter.incrementAndGet(), task, currentTimeMs + delayMs)
        val timer = TimerTask(scheduled, periodMs.coerceAtLeast(1), currentTimeMs + delayMs)
        timerTasks.add(timer)
        return scheduled
    }

    private fun executeTimersStep() {
        timerTasks.filter { !it.scheduledTask.isCancelled && it.nextExecutionMs <= currentTimeMs }
            .forEach { timer ->
                executor.execute(timer.scheduledTask.runnable)
                timer.nextExecutionMs = currentTimeMs + timer.periodMs
            }
    }

    private fun executeTimersCatchUp() {
        while (true) {
            val due = timerTasks.filter { !it.scheduledTask.isCancelled && it.nextExecutionMs <= currentTimeMs }
            if (due.isEmpty()) return
            due.forEach { timer ->
                executor.execute(timer.scheduledTask.runnable)
                timer.nextExecutionMs += timer.periodMs
            }
        }
    }

    private data class TimerTask(
        val scheduledTask: TestScheduledTask,
        val periodMs: Long,
        var nextExecutionMs: Long,
    )

    private class TestScheduledTask(
        override val id: Int,
        val runnable: Runnable,
        val executeAtMs: Long,
    ) : ScheduledTask {
        private var cancelled = false
        override val isCancelled: Boolean get() = cancelled
        override fun cancel() {
            cancelled = true
        }
    }
}
