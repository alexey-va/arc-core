package ru.arc.persistence

import java.util.concurrent.CompletableFuture

/**
 * Keeps at most one write in flight and one newest value waiting behind it.
 *
 * Every submitter completes only after its value, or a newer value that
 * supersedes it, has been written. A failed write is retried implicitly by an
 * already queued newer value; without such a value the failure reaches every
 * affected waiter and [closeAsync].
 */
class CoalescingAsyncWriter<T>(
    private val writeOperation: (T) -> CompletableFuture<Unit>,
) {
    private data class Batch<T>(
        var value: T,
        val waiters: MutableList<CompletableFuture<Unit>>,
    )

    private val monitor = Any()
    private var inFlight = false
    private var accepting = true
    private var pending: Batch<T>? = null
    private val closeWaiters = mutableListOf<CompletableFuture<Unit>>()

    fun submit(value: T): CompletableFuture<Unit> {
        val waiter = CompletableFuture<Unit>()
        val first = synchronized(monitor) {
            if (!accepting) {
                waiter.completeExceptionally(IllegalStateException("Coalescing async writer is closed"))
                return waiter
            }
            if (!inFlight) {
                inFlight = true
                Batch(value, mutableListOf(waiter))
            } else {
                val queued = pending
                if (queued == null) {
                    pending = Batch(value, mutableListOf(waiter))
                } else {
                    queued.value = value
                    queued.waiters += waiter
                }
                null
            }
        }
        if (first != null) start(first)
        return waiter
    }

    fun closeAsync(): CompletableFuture<Unit> = synchronized(monitor) {
        accepting = false
        if (!inFlight) CompletableFuture.completedFuture(Unit)
        else CompletableFuture<Unit>().also(closeWaiters::add)
    }

    private fun start(batch: Batch<T>) {
        val operation = runCatching { writeOperation(batch.value) }
            .getOrElse(CompletableFuture<Unit>::failedFuture)
        operation.whenComplete { _, failure ->
            val next = synchronized(monitor) {
                pending.also { queued ->
                    pending = null
                    if (failure != null && queued != null) queued.waiters.addAll(0, batch.waiters)
                    if (queued == null) {
                        inFlight = false
                        closeWaiters.forEach { waiter ->
                            if (failure == null) waiter.complete(Unit) else waiter.completeExceptionally(failure)
                        }
                        closeWaiters.clear()
                    }
                }
            }
            if (failure == null || next == null) {
                batch.waiters.forEach { waiter ->
                    if (failure == null) waiter.complete(Unit) else waiter.completeExceptionally(failure)
                }
            }
            if (next != null) start(next)
        }
    }
}
