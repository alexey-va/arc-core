package ru.arc.paper.sql

import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcSqlService
import ru.arc.paper.api.ArcSqlLease
import ru.arc.paper.api.ArcSqlSettings
import ru.arc.paper.api.ArcSqlSslMode
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import java.util.concurrent.CompletionStage

/** Installs the plugin-local SqlRuntime factory backed by the host SQL service. */
object PaperSqlServices {
    fun installConsumer(plugin: Plugin): AutoCloseable {
        return SqlConsumerBinding(plugin).install()
    }

    /** Capture on Paper; preserve existing leases until the consumer's terminal IO drain. */
    fun installConsumer(plugin: Plugin, shutdownDrain: CompletionStage<*>): AutoCloseable =
        SqlConsumerBinding(plugin, shutdownDrain).install()
}

private class SqlConsumerBinding(
    plugin: Plugin,
    private val shutdownDrain: CompletionStage<*>? = null,
) : AutoCloseable {
    // Capture native services once on Paper; factories may subsequently run on IO workers.
    private val owner = plugin.name
    private val service = plugin.server.servicesManager.load(ArcSqlService::class.java)
    private val monitor = Any()
    private val runtimes = LinkedHashMap<SqlRuntime, ArcSqlLease>()
    private var closed = false
    private lateinit var factoryHandle: AutoCloseable

    fun install(): AutoCloseable {
        factoryHandle = SqlRuntime.installFactory { config, _ -> createRuntime(config) }
        return this
    }

    private fun createRuntime(config: SqlConnectionConfig): SqlRuntime {
        synchronized(monitor) { check(!closed) { "SQL consumer binding for $owner is closed" } }
        val provider = service
            ?: error("Required ArcSqlService is not registered; refusing a private SQL pool")
        // A cold acquire can open sockets. Never hold an owning-thread monitor across it.
        val lease = provider.acquire(owner, config.toArcSqlSettings())
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
            val accepted = synchronized(monitor) {
                if (closed) false else { runtimes[runtime] = lease; true }
            }
            if (!accepted) {
                runtime.close()
                error("SQL consumer binding for $owner is closed")
            }
            return runtime
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
        val drain = shutdownDrain
        if (drain == null) owned.forEach { it.first.close() }
        else {
            owned.forEach { it.second.retainDuringShutdown() }
            drain.whenComplete { _, _ -> owned.forEach { it.first.close() } }
        }
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
