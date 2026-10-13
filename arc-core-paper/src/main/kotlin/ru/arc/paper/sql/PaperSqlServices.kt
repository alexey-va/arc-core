package ru.arc.paper.sql

import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcSqlService
import ru.arc.paper.api.ArcSqlSettings
import ru.arc.paper.api.ArcSqlSslMode
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode

/** Installs the plugin-local SqlRuntime factory backed by the host SQL service. */
object PaperSqlServices {
    fun installConsumer(plugin: Plugin): AutoCloseable {
        return SqlConsumerBinding(plugin).install()
    }
}

private class SqlConsumerBinding(
    private val plugin: Plugin,
) : AutoCloseable {
    private val monitor = Any()
    private val runtimes = LinkedHashSet<SqlRuntime>()
    private var closed = false
    private lateinit var factoryHandle: AutoCloseable

    fun install(): AutoCloseable {
        factoryHandle = SqlRuntime.installFactory { config, _ -> createRuntime(config) }
        return this
    }

    private fun createRuntime(config: SqlConnectionConfig): SqlRuntime = synchronized(monitor) {
        check(!closed) { "SQL consumer binding for ${plugin.name} is closed" }
        val service = plugin.server.servicesManager.load(ArcSqlService::class.java)
            ?: error("Required ArcSqlService is not registered; refusing a private SQL pool")
        val lease = service.acquire(plugin.name, config.toArcSqlSettings())
        lateinit var runtime: SqlRuntime
        val release = AutoCloseable {
            try {
                lease.close()
            } finally {
                synchronized(monitor) { runtimes.remove(runtime) }
            }
        }
        try {
            runtime = SqlRuntime.borrow(
                dataSource = lease.dataSource,
                executor = lease.executor,
                completionExecutor = lease.completionExecutor,
                lease = release,
                evictConnection = lease::evictConnection,
            )
            runtimes += runtime
            runtime
        } catch (failure: Throwable) {
            lease.close()
            throw failure
        }
    }

    override fun close() {
        factoryHandle.close()
        val owned = synchronized(monitor) {
            if (closed) return
            closed = true
            runtimes.toList().also { runtimes.clear() }
        }
        owned.forEach(SqlRuntime::close)
    }
}

private fun SqlConnectionConfig.toArcSqlSettings() =
    ArcSqlSettings(
        host = host,
        port = port,
        database = database,
        username = username,
        password = password,
        sslMode = ArcSqlSslMode.valueOf(sslMode.name),
        minimumIdle = minimumIdle,
        maximumPoolSize = maximumPoolSize,
        connectionTimeoutMs = connectionTimeoutMs,
        socketTimeoutMs = socketTimeoutMs,
        validationTimeoutMs = validationTimeoutMs,
        maxLifetimeMs = maxLifetimeMs,
        failFast = failFast,
    )
