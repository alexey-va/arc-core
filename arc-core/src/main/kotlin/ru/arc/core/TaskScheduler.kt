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
        tasks.forEach { it.cancelTaskOnly() }
        tasks.clear()
    }

    internal fun trackedCount(): Int = tasks.size

    private fun schedule(
        executor: ScheduledExecutorService,
        delayTicks: Long,
        periodTicks: Long,
        task: Runnable,
        repeating: Boolean,
    ): ScheduledTask {
        val handle = ExecutorScheduledTask(idGen.incrementAndGet(), ::untrack)
        tasks.add(handle)
        val delayMs = TickConstants.ticksToMillis(delayTicks)
        val periodMs = TickConstants.ticksToMillis(periodTicks).coerceAtLeast(1)
        val runner =
            Runnable {
                try {
                    if (!handle.isCancelled) task.run()
                } finally {
                    if (!repeating) handle.detach()
                }
            }
        handle.future =
            if (repeating) {
                executor.scheduleAtFixedRate(runner, delayMs, periodMs, TimeUnit.MILLISECONDS)
            } else {
                executor.schedule(runner, delayMs, TimeUnit.MILLISECONDS)
            }
        return handle
    }

    private fun untrack(handle: ExecutorScheduledTask) {
        tasks.remove(handle)
    }

    private class ExecutorScheduledTask(
        override val id: Int,
        private val removeFromRegistry: (ExecutorScheduledTask) -> Unit,
    ) : ScheduledTask {
        @Volatile
        var future: java.util.concurrent.ScheduledFuture<*>? = null

        @Volatile
        private var cancelled = false

        @Volatile
        private var detached = false

        override val isCancelled: Boolean
            get() = cancelled

        fun detach() {
            if (detached) return
            detached = true
            removeFromRegistry(this)
        }

        override fun cancel() {
            cancelled = true
            future?.cancel(false)
            detach()
        }

        fun cancelTaskOnly() {
            cancelled = true
            future?.cancel(false)
        }
    }

    companion object {
        private val SHARED_SYNC = Executors.newSingleThreadScheduledExecutor()
        private val SHARED_ASYNC = Executors.newScheduledThreadPool(4)
    }
}
