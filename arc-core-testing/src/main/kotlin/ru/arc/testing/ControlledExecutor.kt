package ru.arc.testing

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * FIFO executor advanced only by the owning test.
 *
 * Tasks run on the caller of [runNext]/[runAll]. Closing rejects new work and
 * clears pending work so one test cannot leak callbacks into another.
 */
class ControlledExecutor(
    private val maxPendingTasks: Int = 100_000,
) : Executor, AutoCloseable {
    private val monitor = Any()
    private val pending = ArrayDeque<Runnable>()
    private var closed = false

    init {
        require(maxPendingTasks in 1..1_000_000) { "Controlled executor capacity must be between 1 and 1000000" }
    }

    override fun execute(command: Runnable) = synchronized(monitor) {
        if (closed) throw RejectedExecutionException("Controlled executor is closed")
        if (pending.size >= maxPendingTasks) throw RejectedExecutionException("Controlled executor queue is full")
        pending.addLast(command)
    }

    fun runNext(): Boolean {
        val task = synchronized(monitor) { pending.pollFirst() } ?: return false
        task.run()
        return true
    }

    /** Runs until idle and rejects an accidentally self-rescheduling infinite fixture. */
    fun runAll(maxTasks: Int = maxPendingTasks): Int {
        require(maxTasks in 1..1_000_000) { "Controlled executor run limit must be between 1 and 1000000" }
        var executed = 0
        while (runNext()) {
            executed++
            check(executed <= maxTasks) { "Controlled executor exceeded its run limit" }
        }
        return executed
    }

    fun pendingCount(): Int = synchronized(monitor) { pending.size }

    override fun close() = synchronized(monitor) {
        if (closed) return@synchronized
        closed = true
        pending.clear()
    }
}
