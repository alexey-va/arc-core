package ru.arc.sql

import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** Runs blocking JDBC work away from platform event threads. */
class SqlExecutor private constructor(
    private val dataSource: DataSource,
    private val executor: Executor,
    private val ownedExecutor: ExecutorService?,
) : AutoCloseable, Executor {
    constructor(dataSource: DataSource, threads: Int, threadNamePrefix: String) : this(
        dataSource,
        newExecutor(threads, threadNamePrefix),
        true,
    )

    /** Wraps a host-owned executor; closing this facade never closes that executor. */
    constructor(dataSource: DataSource, executor: Executor) : this(dataSource, executor, false)

    private constructor(dataSource: DataSource, executor: Executor, ownsExecutor: Boolean) : this(
        dataSource,
        executor,
        if (ownsExecutor) executor as ExecutorService else null,
    )

    private val closed = AtomicBoolean()
    private val admission = Any()
    private var accepted = 0
    private val drained = CompletableFuture<Unit>()

    override fun execute(command: Runnable) {
        synchronized(admission) {
            if (closed.get()) throw RejectedExecutionException("SQL executor is closed")
            accepted++
        }
        val released = AtomicBoolean()
        fun release() { if (released.compareAndSet(false, true)) finished() }
        try {
            executor.execute {
                try { command.run() } finally { release() }
            }
        } catch (failure: Throwable) {
            release()
            throw failure
        }
    }

    private fun finished() {
        val complete = synchronized(admission) { --accepted; closed.get() && accepted == 0 }
        // User continuations must never execute under the admission monitor.
        if (complete) drained.complete(Unit)
    }

    /**
     * Seals this facade without waiting; observes every accepted task leaving its
     * operation and JDBC cleanup. It does not close a borrowed executor, pool or
     * lease, and is not a claim that each individual operation succeeded. Owners
     * first drain higher-level ledgers/completion lanes, await this stage on their
     * cleanup worker, then release the runtime/lease. Use this instead of the
     * legacy synchronous close while owned work is still pending. Observer
     * cancellation cannot cancel accepted work or another observer's drain.
     */
    fun closeAsync(): CompletableFuture<Unit> {
        if (closed.compareAndSet(false, true)) ownedExecutor?.shutdown()
        if (synchronized(admission) { accepted == 0 }) drained.complete(Unit)
        return drained.copy()
    }

    /** Runs blocking SQL-adjacent work that owns its own connection lifecycle, such as migrations. */
    fun <T> submit(block: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(block, this)

    fun <T> read(block: (Connection) -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(
            {
                dataSource.connection.use { connection ->
                    connection.isReadOnly = true
                    block(connection)
                }
            },
            this,
        )

    fun <T> write(block: (Connection) -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(
            {
                dataSource.connection.use { connection ->
                    block(connection)
                }
            },
            this,
        )

    fun <T> transaction(block: (Connection) -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(
            {
                dataSource.connection.use { connection ->
                    val previousAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    var primaryFailure: Throwable? = null
                    try {
                        val result = block(connection)
                        connection.commit()
                        result
                    } catch (failure: Throwable) {
                        primaryFailure = failure
                        runCatching { connection.rollback() }
                            .onFailure(failure::addSuppressed)
                        throw failure
                    } finally {
                        runCatching { connection.autoCommit = previousAutoCommit }
                            .onFailure { restoreFailure ->
                                primaryFailure?.addSuppressed(restoreFailure) ?: throw restoreFailure
                            }
                    }
                }
            },
            this,
        )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (synchronized(admission) { accepted == 0 }) drained.complete(Unit)
        val owned = ownedExecutor ?: return
        owned.shutdown()
        try {
            if (!owned.awaitTermination(5, TimeUnit.SECONDS)) owned.shutdownNow()
        } catch (_: InterruptedException) {
            owned.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        fun newExecutor(threads: Int, threadNamePrefix: String): ExecutorService {
            require(threads in 1..64) { "SQL executor threads must be between 1 and 64" }
            require(threadNamePrefix.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Unsafe SQL thread prefix" }
            return Executors.newFixedThreadPool(threads, NamedThreadFactory(threadNamePrefix))
        }
    }

    private class NamedThreadFactory(
        private val prefix: String,
    ) : ThreadFactory {
        private val sequence = AtomicInteger()

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "$prefix-${sequence.incrementAndGet()}").apply { isDaemon = true }
    }
}
