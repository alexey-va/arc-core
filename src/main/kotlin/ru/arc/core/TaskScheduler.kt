package ru.arc.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Platform-neutral task scheduling (delays/periods in Minecraft ticks, 50 ms each). */
interface TaskScheduler {
    fun runAsync(task: Runnable): ScheduledTask

    fun runSync(task: Runnable): ScheduledTask

    fun runLater(delayTicks: Long, task: Runnable): ScheduledTask

    fun runLaterAsync(delayTicks: Long, task: Runnable): ScheduledTask

    fun runTimer(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask

    fun runTimerAsync(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask

    fun cancelAll()
}

interface ScheduledTask {
    val id: Int
    val isCancelled: Boolean

    fun cancel()
}

/** JDK fallback — sync == same executor thread pool (for repos, tests, headless). */
class ExecutorTaskScheduler(
    private val syncExecutor: ScheduledExecutorService = SHARED_SYNC,
    private val asyncExecutor: ScheduledExecutorService = SHARED_ASYNC,
) : TaskScheduler {

    private val tasks = CopyOnWriteArrayList<ExecutorScheduledTask>()
    private val idGen = AtomicInteger()

    override fun runAsync(task: Runnable): ScheduledTask =
        schedule(asyncExecutor, 0, 0, task, repeating = false)

    override fun runSync(task: Runnable): ScheduledTask =
        schedule(syncExecutor, 0, 0, task, repeating = false)

    override fun runLater(delayTicks: Long, task: Runnable): ScheduledTask =
        schedule(syncExecutor, delayTicks, 0, task, repeating = false)

    override fun runLaterAsync(delayTicks: Long, task: Runnable): ScheduledTask =
        schedule(asyncExecutor, delayTicks, 0, task, repeating = false)

    override fun runTimer(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        schedule(syncExecutor, delayTicks, periodTicks, task, repeating = true)

    override fun runTimerAsync(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        schedule(asyncExecutor, delayTicks, periodTicks, task, repeating = true)

    override fun cancelAll() {
        tasks.forEach { it.cancel() }
        tasks.clear()
    }

    private fun schedule(
        executor: ScheduledExecutorService,
        delayTicks: Long,
        periodTicks: Long,
        task: Runnable,
        repeating: Boolean,
    ): ScheduledTask {
        val handle = ExecutorScheduledTask(idGen.incrementAndGet())
        tasks.add(handle)
        val delayMs = ticksToMillis(delayTicks)
        val periodMs = ticksToMillis(periodTicks)
        handle.future = if (repeating) {
            executor.scheduleAtFixedRate(
                {
                    if (!handle.isCancelled) task.run()
                },
                delayMs,
                periodMs.coerceAtLeast(1),
                TimeUnit.MILLISECONDS,
            )
        } else {
            executor.schedule(
                {
                    if (!handle.isCancelled) task.run()
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        }
        return handle
    }

    private class ExecutorScheduledTask(override val id: Int) : ScheduledTask {
        @Volatile
        var future: java.util.concurrent.ScheduledFuture<*>? = null

        @Volatile
        private var cancelled = false

        override val isCancelled: Boolean
            get() = cancelled

        override fun cancel() {
            cancelled = true
            future?.cancel(false)
        }
    }

    companion object {
        private val SHARED_SYNC = Executors.newSingleThreadScheduledExecutor()
        private val SHARED_ASYNC = Executors.newScheduledThreadPool(4)

        fun ticksToMillis(ticks: Long): Long = ticks * 50L
    }
}

/** In-memory scheduler for unit tests. */
class TestTaskScheduler : TaskScheduler {
    private val tasks = CopyOnWriteArrayList<TestScheduledTask>()
    private val idGen = AtomicInteger()
    private var currentTick = 0L

    override fun runAsync(task: Runnable): ScheduledTask = enqueue(0, 0, task, async = true, repeat = false)

    override fun runSync(task: Runnable): ScheduledTask = enqueue(0, 0, task, async = false, repeat = false)

    override fun runLater(delayTicks: Long, task: Runnable): ScheduledTask =
        enqueue(delayTicks, 0, task, async = false, repeat = false)

    override fun runLaterAsync(delayTicks: Long, task: Runnable): ScheduledTask =
        enqueue(delayTicks, 0, task, async = true, repeat = false)

    override fun runTimer(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        enqueue(delayTicks, periodTicks, task, async = false, repeat = true)

    override fun runTimerAsync(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        enqueue(delayTicks, periodTicks, task, async = true, repeat = true)

    override fun cancelAll() {
        tasks.forEach { it.cancelled = true }
        tasks.clear()
    }

    fun tick(ticks: Long = 1) {
        repeat(ticks.toInt()) {
            currentTick++
            tasks.filter { !it.cancelled && it.fireTick == currentTick }.forEach { it.task.run() }
            tasks.filter { !it.cancelled && it.repeat && currentTick >= it.fireTick && it.period > 0 }
                .filter { (currentTick - it.fireTick) % it.period == 0L && currentTick != it.fireTick }
                .forEach { it.task.run() }
        }
    }

    fun pendingCount(): Int = tasks.count { !it.cancelled && it.fireTick > currentTick }

    private fun enqueue(
        delayTicks: Long,
        periodTicks: Long,
        task: Runnable,
        async: Boolean,
        repeat: Boolean,
    ): ScheduledTask {
        val handle = TestScheduledTask(
            id = idGen.incrementAndGet(),
            fireTick = currentTick + delayTicks,
            period = periodTicks,
            repeat = repeat,
            async = async,
            task = task,
        )
        tasks.add(handle)
        return object : ScheduledTask {
            override val id: Int = handle.id
            override val isCancelled: Boolean get() = handle.cancelled
            override fun cancel() {
                handle.cancelled = true
            }
        }
    }

    private data class TestScheduledTask(
        val id: Int,
        val fireTick: Long,
        val period: Long,
        val repeat: Boolean,
        val async: Boolean,
        val task: Runnable,
        @Volatile var cancelled: Boolean = false,
    )
}
