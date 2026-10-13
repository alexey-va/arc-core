package ru.arc.sql

import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** Owns or borrows SQL resources behind the same async JDBC API. */
class SqlRuntime private constructor(
    val dataSource: DataSource,
    val executor: SqlExecutor,
    internal val ownsPool: Boolean,
    private val sharedCompletionExecutor: Executor?,
    private val borrowedConnectionEvictor: ((Connection) -> Unit)?,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    fun health(): CompletableFuture<SqlHealth> =
        executor.read { connection ->
            connection.prepareStatement("SELECT 1").use { statement ->
                statement.executeQuery().use { result ->
                    SqlHealth(ready = result.next() && result.getInt(1) == 1)
                }
            }
        }.exceptionally { failure -> SqlHealth(ready = false, detail = failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName) }

    /** Discards a retained JDBC session without depending on the provider's pool classloader. */
    fun evictConnection(connection: Connection) {
        borrowedConnectionEvictor?.let {
            it(connection)
            return
        }
        val ownedPool = dataSource as? HikariDataSource
        if (ownedPool != null) {
            ownedPool.evictConnection(connection)
        } else {
            connection.abort(DIRECT_EXECUTOR)
        }
    }

    /** Uses the provider reserve lane when borrowed, otherwise retains the caller's local sizing. */
    fun createCompletionExecutor(threads: Int, threadNamePrefix: String): SqlExecutor =
        sharedCompletionExecutor?.let { SqlExecutor(dataSource, it) }
            ?: SqlExecutor(dataSource, threads, threadNamePrefix)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            executor.close()
        } finally {
            release()
        }
    }

    companion object {
        private data class FactorySlot(
            val token: Any,
            val factory: (SqlConnectionConfig, String) -> SqlRuntime,
        )

        private val factoryMonitor = Any()
        @Volatile private var installedFactory: FactorySlot? = null
        private val DIRECT_EXECUTOR = Executor(Runnable::run)

        fun create(
            config: SqlConnectionConfig,
            runtimeName: String,
        ): SqlRuntime {
            validateRuntimeName(runtimeName)
            val factory = synchronized(factoryMonitor) { installedFactory?.factory }
            return factory?.invoke(config, runtimeName) ?: createOwned(config, runtimeName)
        }

        /** Installs a plugin-local factory and restores standalone behavior when removed. */
        fun installFactory(factory: (SqlConnectionConfig, String) -> SqlRuntime): AutoCloseable {
            val token = Any()
            synchronized(factoryMonitor) {
                check(installedFactory == null) { "A SQL runtime factory is already installed" }
                installedFactory = FactorySlot(token, factory)
            }
            return AutoCloseable {
                synchronized(factoryMonitor) {
                    if (installedFactory?.token === token) installedFactory = null
                }
            }
        }

        /** Creates a standalone pool without consulting the plugin-local factory. */
        fun createOwned(
            config: SqlConnectionConfig,
            runtimeName: String,
        ): SqlRuntime {
            validateRuntimeName(runtimeName)
            val dataSource = SqlDataSourceFactory.create(config, "$runtimeName-pool")
            return ownPool(dataSource, config.maximumPoolSize, runtimeName)
        }

        /** Keeps Hikari ownership explicit and makes pool eviction testable without a live database. */
        internal fun ownPool(
            dataSource: HikariDataSource,
            executorThreads: Int,
            runtimeName: String,
        ): SqlRuntime {
            validateRuntimeName(runtimeName)
            return try {
                SqlRuntime(
                    dataSource = dataSource,
                    executor = SqlExecutor(dataSource, executorThreads, "$runtimeName-sql"),
                    ownsPool = true,
                    sharedCompletionExecutor = null,
                    borrowedConnectionEvictor = null,
                    release = dataSource::close,
                )
            } catch (failure: Throwable) {
                dataSource.close()
                throw failure
            }
        }

        /** Wraps a provider-owned datasource and executor in a consumer-owned runtime facade. */
        fun borrow(
            dataSource: DataSource,
            executor: Executor,
            completionExecutor: Executor,
            lease: AutoCloseable,
            evictConnection: (Connection) -> Unit,
        ): SqlRuntime =
            try {
                SqlRuntime(
                    dataSource = dataSource,
                    executor = SqlExecutor(dataSource, executor),
                    ownsPool = false,
                    sharedCompletionExecutor = completionExecutor,
                    borrowedConnectionEvictor = evictConnection,
                    release = lease::close,
                )
            } catch (failure: Throwable) {
                lease.close()
                throw failure
            }

        private fun validateRuntimeName(runtimeName: String) {
            require(runtimeName.matches(Regex("[A-Za-z0-9_.-]{1,55}"))) { "Unsafe SQL runtime name" }
        }
    }
}

data class SqlHealth(
    val ready: Boolean,
    val detail: String? = null,
)
