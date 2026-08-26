package ru.arc.core

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletionStage

/**
 * Owns scheduled callbacks for one reloadable runtime epoch.
 *
 * [restart] invalidates the previous epoch before cancelling its handles. A
 * callback that races with reload therefore observes a stale [Token] and does
 * not enter the replacement runtime. The scope does not hide task failures and
 * may be reopened after [cancelAll]; [close] is terminal.
 */
class LifecycleTaskScope(
    private val scheduler: TaskScheduler = Tasks.scheduler,
    initiallyActive: Boolean = true,
) : AutoCloseable {
    class Token internal constructor(
        internal val owner: LifecycleTaskScope,
        internal val generation: Long,
    )

    private class Entry(
        val token: Token,
        val repeating: Boolean,
    ) {
        @Volatile
        var handle: ScheduledTask? = null
    }

    private val monitor = Any()
    private val tracked = Collections.newSetFromMap(IdentityHashMap<Entry, Boolean>())
    private var generation = if (initiallyActive) 1L else 0L
    private var active = initiallyActive
    private var closed = false

    /** Invalidates and cancels the current epoch, then opens a fresh one. */
    fun restart(): Token {
        cancelTracked(reactivate = true)
        return token()
    }

    /** Opens an inactive scope without silently cancelling an active epoch. */
    fun activate(): Token = synchronized(monitor) {
        check(!closed) { "Lifecycle task scope is closed" }
        check(!active) { "Lifecycle task scope is already active" }
        generation = nextGeneration(generation)
        active = true
        Token(this, generation)
    }

    fun token(): Token = synchronized(monitor) {
        check(!closed) { "Lifecycle task scope is closed" }
        check(active) { "Lifecycle task scope is not active" }
        Token(this, generation)
    }

    fun isCurrent(token: Token): Boolean = synchronized(monitor) { isCurrentLocked(token) }

    fun runSync(task: () -> Unit): ScheduledTask? = currentToken()?.let { runSync(it, task) }

    fun runSync(token: Token, task: () -> Unit): ScheduledTask? =
        schedule(token, repeating = false, scheduler::runSync, task)

    fun runAsync(task: () -> Unit): ScheduledTask? = currentToken()?.let { runAsync(it, task) }

    fun runAsync(token: Token, task: () -> Unit): ScheduledTask? =
        schedule(token, repeating = false, scheduler::runAsync, task)

    fun runLater(delayTicks: Long, task: () -> Unit): ScheduledTask? =
        currentToken()?.let { runLater(it, delayTicks, task) }

    fun runLater(token: Token, delayTicks: Long, task: () -> Unit): ScheduledTask? {
        require(delayTicks >= 0L) { "Lifecycle task delay must not be negative" }
        return schedule(token, repeating = false, { runnable -> scheduler.runLater(delayTicks, runnable) }, task)
    }

    fun runLaterAsync(delayTicks: Long, task: () -> Unit): ScheduledTask? =
        currentToken()?.let { runLaterAsync(it, delayTicks, task) }

    fun runLaterAsync(token: Token, delayTicks: Long, task: () -> Unit): ScheduledTask? {
        require(delayTicks >= 0L) { "Lifecycle async task delay must not be negative" }
        return schedule(token, repeating = false, { runnable -> scheduler.runLaterAsync(delayTicks, runnable) }, task)
    }

    fun runTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTask? =
        currentToken()?.let { runTimer(it, delayTicks, periodTicks, task) }

    fun runTimer(token: Token, delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTask? {
        require(delayTicks >= 0L) { "Lifecycle timer delay must not be negative" }
        require(periodTicks >= 1L) { "Lifecycle timer period must be positive" }
        return schedule(token, repeating = true, { runnable -> scheduler.runTimer(delayTicks, periodTicks, runnable) }, task)
    }

    fun runTimerAsync(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTask? =
        currentToken()?.let { runTimerAsync(it, delayTicks, periodTicks, task) }

    fun runTimerAsync(token: Token, delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTask? {
        require(delayTicks >= 0L) { "Lifecycle async timer delay must not be negative" }
        require(periodTicks >= 1L) { "Lifecycle async timer period must be positive" }
        return schedule(token, repeating = true, { runnable -> scheduler.runTimerAsync(delayTicks, periodTicks, runnable) }, task)
    }

    /** Invalidates the current epoch before cancelling its task handles. */
    fun cancelAll() {
        cancelTracked(reactivate = false)
    }

    internal fun trackedCount(): Int = synchronized(monitor) { tracked.size }

    override fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
        }
        cancelTracked(reactivate = false, allowClosed = true)
    }

    private fun schedule(
        token: Token,
        repeating: Boolean,
        submit: (Runnable) -> ScheduledTask,
        task: () -> Unit,
    ): ScheduledTask? {
        val entry = Entry(token, repeating)
        synchronized(monitor) {
            if (!isCurrentLocked(token)) return null
            tracked += entry
        }
        val runnable = Runnable {
            try {
                if (synchronized(monitor) { isCurrentLocked(token) }) task()
            } finally {
                if (!entry.repeating) synchronized(monitor) { tracked.remove(entry) }
            }
        }
        val handle = try {
            submit(runnable)
        } catch (failure: RuntimeException) {
            synchronized(monitor) { tracked.remove(entry) }
            throw failure
        }
        entry.handle = handle
        val cancelledBeforeAttach = synchronized(monitor) { entry !in tracked || !isCurrentLocked(token) }
        if (cancelledBeforeAttach) handle.cancel()
        return handle.takeUnless { cancelledBeforeAttach }
    }

    private fun cancelTracked(
        reactivate: Boolean,
        allowClosed: Boolean = false,
    ) {
        val cancelled = synchronized(monitor) {
            if (!allowClosed) check(!closed) { "Lifecycle task scope is closed" }
            active = false
            generation = nextGeneration(generation)
            tracked.toList().also { tracked.clear() }
        }
        var firstFailure: RuntimeException? = null
        cancelled.forEach { entry ->
            try {
                entry.handle?.cancel()
            } catch (failure: RuntimeException) {
                val previous = firstFailure
                if (previous == null) firstFailure = failure else previous.addSuppressed(failure)
            }
        }
        if (reactivate) synchronized(monitor) {
            check(!closed) { "Lifecycle task scope is closed" }
            generation = nextGeneration(generation)
            active = true
        }
        firstFailure?.let { throw IllegalStateException("Could not cancel every lifecycle task", it) }
    }

    private fun currentToken(): Token? = synchronized(monitor) {
        if (!closed && active) Token(this, generation) else null
    }

    private fun isCurrentLocked(token: Token): Boolean =
        token.owner === this && !closed && active && token.generation == generation

    private fun nextGeneration(current: Long): Long = if (current == Long.MAX_VALUE) 1L else current + 1L
}

/**
 * Marshals a completion callback through [LifecycleTaskScope]. A stale epoch
 * intentionally drops the callback instead of mutating a replacement runtime.
 */
fun <T> CompletionStage<T>.whenCompleteSync(
    scope: LifecycleTaskScope,
    token: LifecycleTaskScope.Token = scope.token(),
    action: (value: T?, failure: Throwable?) -> Unit,
): CompletionStage<T> = whenComplete { value, failure -> scope.runSync(token) { action(value, failure) } }
