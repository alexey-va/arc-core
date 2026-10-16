package ru.arc.paper.schematics

import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.paper.api.ArcSchematicCacheSettings
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** One tick pump enforces aggregate budgets across all consumers, never one budget per request. */
internal class SchematicMainThread(scheduler: TaskScheduler, private val settings: ArcSchematicCacheSettings) : AutoCloseable {
    internal interface Work {
        /** Returns consumed budget; [done] must become true when no work remains. */
        fun step(budget: Int): Int
        val done: Boolean
        fun fail(problem: Throwable)
    }

    private val monitor = Any()
    private val palettes = ArrayDeque<Work>()
    private val mutations = ArrayDeque<Work>()
    private var closed = false
    private var current: Work? = null
    private val task: ScheduledTask = scheduler.runTimer(1, 1, Runnable(::tick))

    fun enqueueMutation(work: Work) = enqueue(mutations, work)

    fun <T> enqueueMutation(work: Work, completion: CompletableFuture<T>): CompletableFuture<T> {
        // Release the queue's reference before request admission is released or
        // any client callback can submit another cancelled operation.
        val detached = completion.handle { value, problem ->
            synchronized(monitor) { mutations.remove(work) }
            if (problem != null) throw CompletionException(problem)
            value
        }
        enqueue(mutations, work)
        return detached
    }

    internal fun queuedMutations(): Int = synchronized(monitor) { mutations.size }

    fun <T, R> palette(values: List<T>, convert: (T) -> R): CompletableFuture<List<R>> {
        val future = CompletableFuture<List<R>>()
        val work = object : Work {
            private val result = ArrayList<R>(values.size)
            private var index = 0
            override val done get() = future.isDone
            override fun step(budget: Int): Int {
                var used = 0
                while (!future.isDone && index < values.size && used < budget) {
                    result += convert(values[index++])
                    used++
                }
                if (index == values.size) future.complete(result.toList())
                return used
            }
            override fun fail(problem: Throwable) { future.completeExceptionally(problem) }
        }
        enqueue(palettes, work)
        return future
    }

    private fun enqueue(queue: ArrayDeque<Work>, work: Work) {
        val accepted = synchronized(monitor) {
            if (closed) false else { if (!work.done) queue.addLast(work); true }
        }
        if (!accepted) work.fail(ArcSchematicException(ArcSchematicFailure.CLOSED, "Schematic provider is closed"))
    }

    private fun tick() {
        drain(palettes, settings.palettePerTick)
        drain(mutations, settings.blocksPerTick)
    }

    private fun drain(queue: ArrayDeque<Work>, maximum: Int) {
        var remaining = maximum
        while (remaining > 0) {
            val work = synchronized(monitor) {
                if (closed) null else queue.pollFirst()?.also { current = it }
            } ?: return
            try {
                val used = work.step(remaining)
                remaining -= maxOf(used, 1)
            } catch (problem: Throwable) {
                // A failed batch may already have consumed its full allowance.
                // Do not grant the same unknown remainder to another operation.
                remaining = 0
                work.fail(problem)
            }
            val rejected = synchronized(monitor) {
                current = null
                if (!closed && !work.done) queue.addLast(work)
                closed && !work.done
            }
            if (rejected) work.fail(ArcSchematicException(ArcSchematicFailure.CLOSED, "Schematic provider is closed"))
        }
    }

    override fun close() {
        val abandoned = synchronized(monitor) {
            if (closed) return
            closed = true
            (palettes.toList() + mutations.toList() + listOfNotNull(current)).also {
                palettes.clear()
                mutations.clear()
            }
        }
        task.cancel()
        val problem = ArcSchematicException(ArcSchematicFailure.CLOSED, "Schematic provider is closed")
        abandoned.forEach { it.fail(problem) }
    }
}
