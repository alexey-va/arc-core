package ru.arc.sql

import com.zaxxer.hikari.HikariDataSource
import java.util.concurrent.CompletableFuture

/** Owns one Hikari pool and its bounded JDBC worker executor. */
class SqlRuntime private constructor(
    val dataSource: HikariDataSource,
    val executor: SqlExecutor,
) : AutoCloseable {
    fun health(): CompletableFuture<SqlHealth> =
        executor.read { connection ->
            connection.prepareStatement("SELECT 1").use { statement ->
                statement.executeQuery().use { result ->
                    SqlHealth(ready = result.next() && result.getInt(1) == 1)
                }
            }
        }.exceptionally { failure -> SqlHealth(ready = false, detail = failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName) }

    override fun close() {
        executor.close()
        dataSource.close()
    }

    companion object {
        fun create(
            config: SqlConnectionConfig,
            runtimeName: String,
        ): SqlRuntime {
            require(runtimeName.matches(Regex("[A-Za-z0-9_.-]{1,55}"))) { "Unsafe SQL runtime name" }
            val dataSource = SqlDataSourceFactory.create(config, "$runtimeName-pool")
            return SqlRuntime(
                dataSource = dataSource,
                executor = SqlExecutor(dataSource, config.maximumPoolSize, "$runtimeName-sql"),
            )
        }
    }
}

data class SqlHealth(
    val ready: Boolean,
    val detail: String? = null,
)
