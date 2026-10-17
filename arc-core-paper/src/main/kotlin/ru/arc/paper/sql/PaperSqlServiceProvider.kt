package ru.arc.paper.sql

import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.JvmShutdownDrain
import ru.arc.paper.api.ArcSqlGroupSnapshot
import ru.arc.paper.api.ArcSqlLease
import ru.arc.paper.api.ArcSqlService
import ru.arc.paper.api.ArcSqlSettings
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlExecutor
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import java.io.PrintWriter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import java.util.IdentityHashMap
import javax.sql.DataSource

/** Registers ARC's shared SQL pools and releases them after borrower work drains. */
class PaperSqlServiceProvider private constructor(
    plugin: Plugin,
    private val service: SharedPaperSqlService,
) : AutoCloseable {
    private val services = plugin.server.servicesManager

    constructor(plugin: Plugin) : this(
        plugin,
        SharedPaperSqlService(SqlRuntime::createOwned, plugin.logger, DEFAULT_SQL_SHUTDOWN_TIMEOUT_MS),
    )

    internal constructor(
        plugin: Plugin,
        shutdownTimeoutMs: Long = DEFAULT_SQL_SHUTDOWN_TIMEOUT_MS,
        runtimeFactory: (SqlConnectionConfig, String) -> SqlRuntime,
    ) : this(plugin, SharedPaperSqlService(runtimeFactory, plugin.logger, shutdownTimeoutMs))

    init {
        var registered = false
        try {
            check(services.load(ArcSqlService::class.java) == null) {
                "An ArcSqlService provider is already registered"
            }
            services.register(ArcSqlService::class.java, service, plugin, ServicePriority.Normal)
            registered = true
            JvmShutdownDrain("arc-sql-provider", service.drainFuture()) { service.shutdownAsync() }
        } catch (failure: Throwable) {
            if (registered) services.unregister(ArcSqlService::class.java, service)
            service.shutdownAsync()
            throw failure
        }
    }

    fun snapshot(): List<ArcSqlGroupSnapshot> = service.snapshot()

    override fun close() {
        services.unregister(ArcSqlService::class.java, service)
        service.shutdownAsync()
    }

    /** Detach on Paper immediately; completion observes accepted work and physical pool drain. */
    fun closeAsync(): CompletableFuture<Unit> {
        close()
        return service.shutdownAsync()
    }
}

private class SharedPaperSqlService(
    private val runtimeFactory: (SqlConnectionConfig, String) -> SqlRuntime,
    private val logger: Logger,
    private val shutdownTimeoutMs: Long,
) : ArcSqlService {
    private data class PendingCounts(
        val groups: Long,
        val borrowers: Long,
        val tasks: Long,
        val completionTasks: Long,
        val openConnections: Long,
        val closingPools: Long,
        val cleanupFailures: Long,
    )

    private val monitor = Any()
    private val groups = LinkedHashMap<ArcSqlSettings, SqlGroup>()
    private val creating = LinkedHashMap<ArcSqlSettings, CompletableFuture<Unit>>()
    private val acceptedLease = ThreadLocal<Lease?>()
    private val shutdownComplete = CompletableFuture<Unit>()
    private val cleanup = BoundedAsyncExecutor("arc-sql-close", 1, MAX_CONNECTION_GROUPS, false)
    private var drainScheduled = false
    private var closed = false
    private var nextGroupId = 1L
    private var retiringGroups = 0L
    private var cleanupFailures = 0L

    init {
        require(shutdownTimeoutMs >= 0L) { "SQL shutdown timeout must not be negative" }
    }

    override fun acquire(owner: String, settings: ArcSqlSettings): ArcSqlLease {
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "SQL lease owner must contain 1 to $MAX_OWNER_LENGTH characters"
        }
        while (true) {
            var createId: Long? = null
            val prepared = synchronized(monitor) {
                check(!closed) { "ArcSqlService is closed" }
                groups[settings]?.let { group -> return newLease(group, owner) }
                creating[settings] ?: run {
                    check(groups.size + creating.size + retiringGroups < MAX_CONNECTION_GROUPS) {
                        "ArcSqlService connection capacity reached"
                    }
                    CompletableFuture<Unit>().also {
                        creating[settings] = it
                        createId = nextGroupId++
                    }
                }
            }
            if (createId == null) {
                // Only acquisition workers wait; diagnostics and shutdown never share this wait.
                prepared.join()
                continue
            }
            try {
                val group = createGroup(settings, createId)
                synchronized(monitor) {
                    if (closed) maybeRetire(group) else groups[settings] = group
                    creating.remove(settings, prepared)
                    completeShutdownIfDrained()
                }
                prepared.complete(Unit)
            } catch (failure: Throwable) {
                synchronized(monitor) {
                    creating.remove(settings, prepared)
                    completeShutdownIfDrained()
                }
                prepared.completeExceptionally(failure)
                throw failure
            }
        }
    }

    /** Caller holds monitor; no pool or native operations occur here. */
    private fun newLease(group: SqlGroup, owner: String): ArcSqlLease {
        val lease = Lease(group, owner)
        group.borrowers++
        group.leases += lease
        if (owner in group.owners || group.owners.size < MAX_OWNER_LABELS) {
            group.owners[owner] = (group.owners[owner] ?: 0) + 1
            lease.ownerTracked = true
        } else {
            group.unattributedBorrowers++
        }
        return lease
    }

    override fun snapshot(): List<ArcSqlGroupSnapshot> = synchronized(monitor) {
        groups.values
            .sortedBy { it.id }
            .map { group ->
                ArcSqlGroupSnapshot(
                    groupId = group.id,
                    host = group.settings.host,
                    port = group.settings.port,
                    database = group.settings.database,
                    minimumIdle = group.settings.minimumIdle,
                    maximumPoolSize = group.settings.maximumPoolSize,
                    borrowers = group.borrowers,
                    inFlightTasks = group.inFlightTasks,
                    inFlightCompletionTasks = group.inFlightCompletionTasks,
                    openConnections = group.openConnections,
                    owners = group.owners.keys.sorted(),
                    unattributedBorrowers = group.unattributedBorrowers,
                )
            }
    }

    fun drainFuture(): CompletableFuture<Unit> = shutdownComplete.copy()

    fun shutdownAsync(): CompletableFuture<Unit> {
        val leases = synchronized(monitor) {
            if (closed) return shutdownComplete.copy()
            closed = true
            groups.values.flatMap { it.leases }.filterNot { it.retainedDuringShutdown }
        }
        leases.forEach(Lease::close)
        synchronized(monitor) {
            groups.values.toList().forEach(::maybeRetire)
            completeShutdownIfDrained()
        }
        CompletableFuture.delayedExecutor(shutdownTimeoutMs, TimeUnit.MILLISECONDS).execute {
            if (!shutdownComplete.isDone) warnShutdownPending("is still draining")
        }
        return shutdownComplete.copy()
    }

    /** Blocking worker-only construction, deliberately outside the provider monitor. */
    private fun createGroup(settings: ArcSqlSettings, id: Long): SqlGroup {
        val runtimeName = "arc-sql-$id"
        val runtime = runtimeFactory(settings.toSqlConnectionConfig(), runtimeName)
        return try {
            SqlGroup(
                id = id,
                settings = settings,
                runtime = runtime,
                completionExecutor = SqlExecutor(runtime.dataSource, COMPLETION_THREADS, "$runtimeName-complete"),
            )
        } catch (failure: Throwable) {
            runtime.close()
            throw failure
        }
    }

    private fun execute(lease: Lease, command: Runnable, completion: Boolean) {
        synchronized(monitor) {
            if ((closed && !lease.retainedDuringShutdown) || !lease.open || lease.group.retiring) {
                throw RejectedExecutionException("SQL lease is closed")
            }
            val group = lease.group
            if (completion) group.inFlightCompletionTasks++ else group.inFlightTasks++
            val delegate = if (completion) group.completionExecutor else group.runtime.executor
            try {
                delegate.execute {
                    val previous = acceptedLease.get()
                    acceptedLease.set(lease)
                    try {
                        command.run()
                    } finally {
                        if (previous == null) acceptedLease.remove() else acceptedLease.set(previous)
                        taskFinished(group, completion)
                    }
                }
            } catch (failure: Throwable) {
                if (completion) group.inFlightCompletionTasks-- else group.inFlightTasks--
                maybeRetire(group)
                throw failure
            }
        }
    }

    private fun taskFinished(group: SqlGroup, completion: Boolean) {
        synchronized(monitor) {
            if (completion) group.inFlightCompletionTasks-- else group.inFlightTasks--
            maybeRetire(group)
        }
    }

    private fun release(lease: Lease) {
        synchronized(monitor) {
            if (!lease.open) return
            lease.open = false
            val group = lease.group
            group.leases.remove(lease)
            group.borrowers--
            if (lease.ownerTracked) {
                val remaining = group.owners.getValue(lease.owner) - 1
                if (remaining == 0) group.owners.remove(lease.owner) else group.owners[lease.owner] = remaining
            } else {
                group.unattributedBorrowers--
            }
            maybeRetire(group)
        }
    }

    private fun beginConnection(lease: Lease) {
        synchronized(monitor) {
            if (lease.group.retiring || (closed && !lease.retainedDuringShutdown && acceptedLease.get() !== lease) ||
                (!lease.open && acceptedLease.get() !== lease)
            ) {
                throw SQLException("SQL lease is closed")
            }
            lease.group.openConnections++
        }
    }

    private fun connectionClosed(group: SqlGroup) {
        synchronized(monitor) {
            group.openConnections--
            maybeRetire(group)
        }
    }

    private fun releaseConnection(tracked: TrackedConnection, evict: Boolean) {
        val lease = tracked.lease
        synchronized(tracked) {
            if (!tracked.released.compareAndSet(false, true)) return
            try {
                if (evict) lease.group.runtime.evictConnection(tracked.target) else tracked.target.close()
            } finally {
                synchronized(monitor) {
                    if (lease.connections.remove(tracked.wrapper) != null) {
                        lease.group.openConnections--
                        maybeRetire(lease.group)
                    }
                }
            }
        }
    }

    private fun evictConnection(lease: Lease, connection: Connection) {
        val tracked = synchronized(monitor) {
            lease.connections[connection] ?: throw SQLException("Connection is not owned by this SQL lease")
        }
        releaseConnection(tracked, evict = true)
    }

    private fun maybeRetire(group: SqlGroup) {
        if (group.retiring || group.borrowers != 0 || group.inFlightTasks != 0 ||
            group.inFlightCompletionTasks != 0 || group.openConnections != 0
        ) return
        group.retiring = true
        if (groups[group.settings] === group) groups.remove(group.settings)
        retiringGroups++
        cleanup.execute {
                var success = true
                try {
                    try {
                        group.completionExecutor.close()
                    } catch (failure: Throwable) {
                        success = false
                        logger.warning("Shared SQL completion pool cleanup failed (${failure.javaClass.simpleName})")
                    }
                    try {
                        group.runtime.close()
                    } catch (failure: Throwable) {
                        success = false
                        logger.warning("Shared SQL pool cleanup failed (${failure.javaClass.simpleName})")
                    }
                } finally {
                    synchronized(monitor) {
                        retiringGroups--
                        if (!success) cleanupFailures++
                        completeShutdownIfDrained()
                    }
                }
        }
    }

    /** Called only while holding [monitor]; arbitrary future continuations run outside it. */
    private fun completeShutdownIfDrained() {
        if (!closed || groups.isNotEmpty() || creating.isNotEmpty() || retiringGroups != 0L || drainScheduled) return
        drainScheduled = true
        val failures = cleanupFailures
        cleanup.execute {
            if (failures == 0L) shutdownComplete.complete(Unit)
            else shutdownComplete.completeExceptionally(IllegalStateException("Shared SQL pool cleanup failed"))
        }
        cleanup.close()
    }

    private fun warnShutdownPending(reason: String) {
        val counts = synchronized(monitor) {
            val active = groups.values
            PendingCounts(
                groups = active.size.toLong() + retiringGroups,
                borrowers = active.sumOf { it.borrowers.toLong() },
                tasks = active.sumOf { it.inFlightTasks.toLong() },
                completionTasks = active.sumOf { it.inFlightCompletionTasks.toLong() },
                openConnections = active.sumOf { it.openConnections.toLong() },
                closingPools = retiringGroups,
                cleanupFailures = cleanupFailures,
            )
        }
        logger.warning(
            "Shared SQL provider shutdown $reason; asynchronous cleanup remains " +
                "(groups=${counts.groups}, borrowers=${counts.borrowers}, tasks=${counts.tasks}, " +
                "completionTasks=${counts.completionTasks}, openConnections=${counts.openConnections}, " +
                "closingPools=${counts.closingPools}, cleanupFailures=${counts.cleanupFailures})",
        )
    }

    private inner class Lease(
        val group: SqlGroup,
        val owner: String,
    ) : ArcSqlLease {
        @Volatile
        var open = true

        var retainedDuringShutdown = false

        var ownerTracked = false
        val connections = IdentityHashMap<Connection, TrackedConnection>()

        override val dataSource: DataSource = LeaseDataSource(this)
        override val executor = Executor { command -> execute(this, command, completion = false) }
        override val completionExecutor = Executor { command -> execute(this, command, completion = true) }

        override fun retainDuringShutdown() = synchronized(monitor) {
            if (!retainedDuringShutdown) {
                check(!closed && open) { "SQL lease cannot begin a drain after provider shutdown" }
                retainedDuringShutdown = true
            }
        }

        override fun close() = release(this)

        override fun evictConnection(connection: Connection) = this@SharedPaperSqlService.evictConnection(this, connection)
    }

    private inner class LeaseDataSource(private val lease: Lease) : DataSource {
        private val delegate get() = lease.group.runtime.dataSource

        override fun getConnection(): Connection {
            beginConnection(lease)
            val connection = try {
                delegate.connection
            } catch (failure: Throwable) {
                connectionClosed(lease.group)
                throw failure
            }
            return try {
                trackedConnection(lease, connection)
            } catch (failure: Throwable) {
                runCatching { connection.close() }
                connectionClosed(lease.group)
                throw failure
            }
        }

        override fun getConnection(username: String, password: String): Connection {
            if (username != lease.group.settings.username || password != lease.group.settings.password) {
                throw SQLException("Shared SQL leases do not support credential overrides")
            }
            return getConnection()
        }

        override fun getLogWriter(): PrintWriter? = delegate.logWriter

        override fun setLogWriter(out: PrintWriter?) {
            throw SQLFeatureNotSupportedException("A shared SQL pool's log writer is provider-owned")
        }

        override fun setLoginTimeout(seconds: Int) {
            throw SQLFeatureNotSupportedException("A shared SQL pool's login timeout is provider-owned")
        }

        override fun getLoginTimeout(): Int = delegate.loginTimeout

        override fun getParentLogger(): Logger = delegate.parentLogger

        override fun <T : Any?> unwrap(iface: Class<T>): T =
            if (iface.isInstance(this)) iface.cast(this)
            else throw SQLException("Underlying shared SQL pool is not exposed")

        override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
    }

    private fun trackedConnection(lease: Lease, target: Connection): Connection {
        val group = lease.group
        val tracked = TrackedConnection(lease, target)
        lateinit var wrapper: Connection
        wrapper = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { proxy, method, arguments ->
            if (tracked.released.get()) {
                when (method.name) {
                    "isClosed" -> return@newProxyInstance true
                    "close", "abort" -> return@newProxyInstance null
                    "equals" -> return@newProxyInstance proxy === arguments?.firstOrNull()
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "toString" -> return@newProxyInstance "ArcSqlConnection(group=${group.id}, closed=true)"
                    else -> throw SQLException("SQL connection lease has been released")
                }
            }
            when (method.name) {
                "close" -> {
                    releaseConnection(tracked, evict = false)
                    null
                }
                "abort" -> {
                    releaseConnection(tracked, evict = true)
                    null
                }
                "unwrap" -> {
                    val iface = arguments!![0] as Class<*>
                    if (iface.isInstance(proxy)) iface.cast(proxy)
                    else throw SQLException("Underlying shared SQL connection is not exposed")
                }
                "isWrapperFor" -> (arguments!![0] as Class<*>).isInstance(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ArcSqlConnection(group=${group.id}, closed=${tracked.released.get()})"
                else -> invokeTarget(target, method, arguments)
            }
        } as Connection
        tracked.wrapper = wrapper
        synchronized(monitor) { lease.connections[wrapper] = tracked }
        return wrapper
    }

    private inner class TrackedConnection(
        val lease: Lease,
        val target: Connection,
    ) {
        val released = AtomicBoolean()
        lateinit var wrapper: Connection
    }

    private fun invokeTarget(target: Any, method: java.lang.reflect.Method, arguments: Array<out Any?>?): Any? =
        try {
            method.invoke(target, *(arguments ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }

    private class SqlGroup(
        val id: Long,
        val settings: ArcSqlSettings,
        val runtime: SqlRuntime,
        val completionExecutor: SqlExecutor,
    ) {
        val leases = LinkedHashSet<Lease>()
        val owners = LinkedHashMap<String, Int>()
        var borrowers = 0
        var inFlightTasks = 0
        var inFlightCompletionTasks = 0
        var openConnections = 0
        var unattributedBorrowers = 0
        var retiring = false
    }

    private fun ArcSqlSettings.toSqlConnectionConfig() =
        SqlConnectionConfig(
            host = host,
            port = port,
            database = database,
            username = username,
            password = password,
            sslMode = SqlSslMode.valueOf(sslMode.name),
            minimumIdle = minimumIdle,
            maximumPoolSize = maximumPoolSize,
            connectionTimeoutMs = connectionTimeoutMs,
            socketTimeoutMs = socketTimeoutMs,
            validationTimeoutMs = validationTimeoutMs,
            maxLifetimeMs = maxLifetimeMs,
            failFast = failFast,
        )

    private companion object {
        const val MAX_CONNECTION_GROUPS = 128
        const val MAX_OWNER_LABELS = 32
        const val MAX_OWNER_LENGTH = 64
        const val COMPLETION_THREADS = 2
    }
}

private const val DEFAULT_SQL_SHUTDOWN_TIMEOUT_MS = 5_000L
