package ru.arc.core

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lifecycle-owned blocking I/O/pure computation workers with bounded admission.
 * Never runs work on the submitting thread, including overload and shutdown.
 * [close] stops admission without waiting and drains every accepted operation;
 * [closeAsync] observes that drain. Cancellation of a returned future does not
 * undo an accepted operation: consumers retain their durable idempotency rules.
 * A JVM shutdown hook observes the owner's drain for up to 120 seconds, including
 * explicit System.exit; it does not seal another owner's still-needed cleanup
 * lane. The lifecycle owner must invoke close/closeAsync before returning from
 * disable. This is best-effort termination, not a substitute for durable journals.
 * Set [protectJvmShutdown] to false only when another owner protects the FULL
 * drain including this executor (for example a shared provider cleanup lane).
 * Native platform state must be captured/applied on its owning thread instead.
 */
class BoundedAsyncExecutor @JvmOverloads constructor(
    name: String,
    threads: Int = 1,
    capacity: Int = 256,
    protectJvmShutdown: Boolean = true,
) : Executor, AutoCloseable {
    private val terminated = CompletableFuture<Unit>()
    private val admission = Object()
    private var closing = false
    private var accepted = 0
    @Volatile private var terminalFailure: Throwable? = null
    private val threadIds = AtomicInteger()
    private val executor: ThreadPoolExecutor

    init {
        require(name.isNotBlank() && threads > 0 && capacity in 1 until Int.MAX_VALUE)
        executor = object : ThreadPoolExecutor(
            threads, threads, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(capacity + 1),
            { action -> Thread(action, "$name-${threadIds.incrementAndGet()}").apply { isDaemon = false } },
            AbortPolicy(),
        ) {
            override fun terminated() {
                val failure = terminalFailure
                if (failure == null) terminated.complete(Unit) else terminated.completeExceptionally(failure)
            }
        }
        try { if (protectJvmShutdown) JvmShutdownDrain(name, terminated) }
        catch (failure: RuntimeException) { executor.shutdown(); throw failure }
    }

    override fun execute(command: Runnable) {
        synchronized(admission) {
            if (closing || executor.queue.remainingCapacity() <= 1) {
                throw RejectedExecutionException("Async worker admission is closed or full")
            }
            accepted++
            try {
                executor.execute {
                    try { command.run() }
                    finally { synchronized(admission) { accepted--; admission.notifyAll() } }
                }
            } catch (failure: RuntimeException) {
                accepted--
                throw failure
            }
        }
    }

    fun <T> submit(operation: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        try {
            execute {
                try {
                    result.complete(operation())
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        } catch (failure: RuntimeException) {
            result.completeExceptionally(failure)
        }
        return result
    }

    fun closeAsync(): CompletableFuture<Unit> {
        close()
        return terminated.copy()
    }

    /**
     * Seals admission, drains accepted work and runs a terminal flush on this
     * executor, even when it was empty. The terminal slot is reserved and cannot
     * be consumed by ordinary work. Invoke on the FIRST close; a late finalizer
     * is rejected rather than silently lost or run on the caller.
     */
    fun closeAsync(finalizer: () -> Unit): CompletableFuture<Unit> {
        synchronized(admission) {
            if (closing) return CompletableFuture.failedFuture(IllegalStateException("Async worker is already closing"))
            closing = true
            executor.execute {
                try {
                    synchronized(admission) { while (accepted != 0) admission.wait() }
                    finalizer()
                } catch (failure: Throwable) {
                    terminalFailure = failure
                    if (failure is InterruptedException) Thread.currentThread().interrupt()
                }
            }
            executor.shutdown()
        }
        return terminated.copy()
    }

    override fun close() {
        synchronized(admission) {
            if (closing) return
            closing = true
            executor.shutdown()
        }
    }
}
