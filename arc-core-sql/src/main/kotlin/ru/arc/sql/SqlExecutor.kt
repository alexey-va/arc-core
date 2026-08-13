package ru.arc.sql

import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** Runs blocking JDBC work away from platform event threads. */
class SqlExecutor(
    private val dataSource: DataSource,
    threads: Int,
    threadNamePrefix: String,
) : AutoCloseable {
    init {
        require(threads in 1..64) { "SQL executor threads must be between 1 and 64" }
        require(threadNamePrefix.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Unsafe SQL thread prefix" }
    }

    private val executor: ExecutorService =
        Executors.newFixedThreadPool(threads, NamedThreadFactory(threadNamePrefix))

    /** Runs blocking SQL-adjacent work that owns its own connection lifecycle, such as migrations. */
    fun <T> submit(block: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(block, executor)

    fun <T> read(block: (Connection) -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(
            {
                dataSource.connection.use { connection ->
                    connection.isReadOnly = true
                    block(connection)
                }
            },
            executor,
        )

    fun <T> write(block: (Connection) -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(
            {
                dataSource.connection.use { connection ->
                    block(connection)
                }
            },
            executor,
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
            executor,
        )

    override fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
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
