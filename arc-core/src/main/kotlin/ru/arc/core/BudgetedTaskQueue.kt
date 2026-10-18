package ru.arc.core

import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.function.LongSupplier

/**
 * One round-robin count/time budget for an owning thread's resumable native work.
 * Share one queue across the runtime's requests; each step must have a fixed small
 * bound. The clock is checked between steps and cannot preempt a native call.
 *
 * Construct, submit and close on the owning thread. Close this queue before
 * restarting/closing [scope]; a scope alone cannot complete abandoned futures.
 * [submit] never runs a step inline. Pending work is retained until completion or
 * explicit failure/cancellation; the tick budget limits execution, not admission.
 * Producers should chain/batch requests through their futures to control backlog.
 * Completion means the last step finished, not that persistence is durable.
 * [maximumPending] is a legacy allocation hint, retained for binary compatibility.
 */
class BudgetedTaskQueue @JvmOverloads constructor(
    private val scope: LifecycleTaskScope,
    private val maximumSteps: Int = 512,
    private val maximumNanos: Long = 4_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
    private val maximumPending: Int = 4096,
) : AutoCloseable {
    private data class Work(
        val current: () -> Boolean,
        val step: () -> Boolean,
        val result: CompletableFuture<Unit>,
    )

    private val pending = ArrayDeque<Work>(maximumPending)
    private var scheduled = false
    private var closed = false
    private var executing = false
    private var tickClock: LongSupplier? = null
    private var budgetTick = 0L
    private var usedSteps = 0
    private var usedNanos = 0L

    init {
        require(maximumSteps > 0 && maximumNanos > 0 && maximumPending > 0)
    }

    /** A step returns true when its operation is complete, otherwise it yields. */
    fun submit(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> {
        if (closed) return CompletableFuture.failedFuture(CancellationException("Native work queue is closed"))
        val result = CompletableFuture<Unit>()
        pending.addLast(Work(isCurrent, step, result))
        schedule()
        return result
    }

    /**
     * Explicit low-latency pump on the owning thread. [submit] itself still never executes inline.
     * The first caller supplies the platform's tick identity for this queue's lifetime.
     * Every pump and scheduled continuation then spends the SAME count/time allowance for that tick;
     * a later caller cannot reset it. Existing queued requests retain round-robin ordering.
     */
    fun drainAvailable(currentTick: LongSupplier) {
        if (closed || executing) return
        if (tickClock == null) {
            tickClock = currentTick
            budgetTick = currentTick.asLong
            // Retain any allowance already consumed before opting into explicit tick accounting.
        }
        drain()
    }

    /** A single bounded native stage, admitted from any thread through the lifecycle scope. */
    fun <T> call(isCurrent: () -> Boolean, operation: () -> T): CompletableFuture<T> =
        scope.callSync {
            val result = CompletableFuture<T>()
            var value: T? = null
            submit(isCurrent) { value = operation(); true }.whenComplete { _, failure ->
                if (failure != null) result.completeExceptionally(failure)
                else result.complete(value)
            }
            result
        }.thenCompose { it }

    private fun schedule() {
        if (scheduled || closed || pending.isEmpty()) return
        scheduled = true
        try {
            checkNotNull(scope.runLater(1L, ::tick)) { "Native work queue was not scheduled" }
        } catch (failure: Throwable) {
            scheduled = false
            failAll(failure)
        }
    }

    private fun tick() {
        scheduled = false
        drain()
    }

    private fun drain() {
        val now = tickClock?.asLong
        if (now == null || now != budgetTick) {
            if (now != null) budgetTick = now
            usedSteps = 0
            usedNanos = 0L
        }
        val started = nanoTime()
        while (!closed && pending.isNotEmpty() && usedSteps < maximumSteps &&
            nanoTime() - started < maximumNanos - usedNanos) {
            val work = pending.removeFirst()
            usedSteps++
            if (work.result.isDone) continue
            executing = true
            try {
                if (!work.current()) {
                    work.result.completeExceptionally(CancellationException("Native work context changed"))
                } else if (work.step()) {
                    work.result.complete(Unit)
                } else if (!closed && !work.result.isDone) {
                    pending.addLast(work)
                } else if (!work.result.isDone) {
                    work.result.completeExceptionally(CancellationException("Native work queue is closed"))
                }
            } catch (failure: Throwable) {
                work.result.completeExceptionally(failure)
            } finally {
                executing = false
            }
        }
        usedNanos += (nanoTime() - started).coerceIn(0L, maximumNanos - usedNanos)
        schedule()
    }

    private fun failAll(failure: Throwable) {
        while (pending.isNotEmpty()) pending.removeFirst().result.completeExceptionally(failure)
    }

    override fun close() {
        if (closed) return
        closed = true
        failAll(CancellationException("Native work queue is closed"))
    }
}
