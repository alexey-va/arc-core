package ru.arc.paper.redis

import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.ServicesManager
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.JvmShutdownDrain
import ru.arc.paper.api.ArcRedisDiagnostics
import ru.arc.paper.api.ArcRedisLease
import ru.arc.paper.api.ArcRedisListener
import ru.arc.paper.api.ArcRedisService
import ru.arc.paper.api.ArcRedisSettings
import ru.arc.redis.ChannelListener
import ru.arc.redis.NoOpRedisTelemetrySink
import ru.arc.redis.RedisConnection
import ru.arc.redis.RedisManager
import ru.arc.redis.RedisTelemetrySink
import ru.arc.redis.ServerIdentity
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Owns shared Redis managers for Paper plugins and registers the API service. */
class PaperRedisServiceProvider private constructor(
    private val plugin: Plugin,
    private val service: SharedPaperRedisService,
) : AutoCloseable {
    private val services: ServicesManager = plugin.server.servicesManager

    constructor(plugin: Plugin) : this(
        plugin,
        SharedPaperRedisService { connection, origin ->
            RedisManager(connection, ServerIdentity { origin })
        },
    )

    internal constructor(
        plugin: Plugin,
        managerFactory: (RedisConnection, String) -> RedisManager,
    ) : this(plugin, SharedPaperRedisService(managerFactory))

    init {
        var registered = false
        try {
            check(services.load(ArcRedisService::class.java) == null) {
                "An ArcRedisService provider is already registered"
            }
            services.register(ArcRedisService::class.java, service, plugin, ServicePriority.Normal)
            registered = true
            JvmShutdownDrain("arc-redis-provider", service.drainFuture()) { service.closeAsync() }
        } catch (failure: Throwable) {
            if (registered) services.unregister(ArcRedisService::class.java, service)
            service.closeAsync()
            throw failure
        }
    }

    /** Selects the host's default connection for future [PaperRedisClient.openPrimary] calls. */
    fun configurePrimary(settings: ArcRedisSettings, origin: String) = service.configurePrimary(settings, origin)

    /** Disables new primary acquisitions while existing leases continue on their current group. */
    fun clearPrimary() = service.clearPrimary()

    /** Installs the host's telemetry sink on all current and future managers. */
    fun installTelemetry(sink: RedisTelemetrySink) = service.installTelemetry(sink)

    fun diagnostics(): ArcRedisDiagnostics = service.diagnostics()

    override fun close() {
        services.unregister(ArcRedisService::class.java, service)
        service.close()
    }

    /** Unregister on Paper immediately; physical transport cleanup drains on its worker. */
    fun closeAsync(): CompletableFuture<Unit> {
        close()
        return service.closeAsync()
    }
}

private class SharedPaperRedisService(
    private val managerFactory: (RedisConnection, String) -> RedisManager,
) : ArcRedisService {
    private val monitor = Any()
    private val groups = linkedMapOf<ConnectionIdentity, RedisGroup>()
    private val creating = linkedMapOf<ConnectionIdentity, CompletableFuture<Unit>>()
    private val cleanup = BoundedAsyncExecutor("arc-redis-close", 1, MAX_CONNECTION_GROUPS, false)
    private val drained = CompletableFuture<Unit>()
    private var drainScheduled = false
    private var retiring = 0
    private var cleanupFailure: Throwable? = null
    private var primary: ConnectionIdentity? = null
    private var telemetrySink: RedisTelemetrySink = NoOpRedisTelemetrySink
    private var closed = false

    override fun acquire(owner: String, settings: ArcRedisSettings, origin: String): ArcRedisLease {
        require(owner.isNotBlank()) { "Redis lease owner cannot be blank" }
        require(origin.isNotBlank()) { "Redis origin cannot be blank" }
        val identity = ConnectionIdentity(settings, origin)
        while (true) {
            var creator = false
            val prepared = synchronized(monitor) {
                check(!closed) { "ArcRedisService is closed" }
                groups[identity]?.let { group ->
                    return Lease(group, owner).also { group.leases += it }
                }
                creating[identity] ?: run {
                    check(groups.size + creating.size + retiring < MAX_CONNECTION_GROUPS) {
                        "ArcRedisService connection capacity reached"
                    }
                    CompletableFuture<Unit>().also { creating[identity] = it; creator = true }
                }
            }
            if (!creator) {
                // This is a worker-only cold acquisition, never a diagnostics/native lock.
                prepared.join()
                continue
            }
            try {
                val group = createGroup(identity)
                synchronized(monitor) {
                    if (closed) retire(group) else {
                        group.manager.installTelemetry(telemetrySink)
                        groups[identity] = group
                    }
                    creating.remove(identity, prepared)
                    completeDrain()
                }
                prepared.complete(Unit)
            } catch (failure: Throwable) {
                synchronized(monitor) {
                    creating.remove(identity, prepared)
                    completeDrain()
                }
                prepared.completeExceptionally(failure)
                throw failure
            }
        }
    }

    override fun acquirePrimary(owner: String): ArcRedisLease {
        val identity = synchronized(monitor) {
            check(!closed) { "ArcRedisService is closed" }
            checkNotNull(primary) { "ARC primary Redis connection is not configured" }
        }
        return acquire(owner, identity.settings, identity.origin)
    }

    override fun diagnostics(): ArcRedisDiagnostics = synchronized(monitor) {
        ArcRedisDiagnostics(
            activeLeases = groups.values.sumOf { it.leases.size },
            connectionGroups = groups.size,
            connectedGroups = groups.values.count { it.manager.isConnected() },
            activeChannels = groups.values.sumOf { it.manager.getChannelCount() },
        )
    }

    fun configurePrimary(settings: ArcRedisSettings, origin: String) = synchronized(monitor) {
        check(!closed) { "ArcRedisService is closed" }
        require(origin.isNotBlank()) { "Redis origin cannot be blank" }
        primary = ConnectionIdentity(settings, origin)
    }

    fun clearPrimary() = synchronized(monitor) {
        check(!closed) { "ArcRedisService is closed" }
        primary = null
    }

    fun installTelemetry(sink: RedisTelemetrySink) = synchronized(monitor) {
        check(!closed) { "ArcRedisService is closed" }
        telemetrySink = sink
        groups.values.forEach { it.manager.installTelemetry(sink) }
    }

    override fun close() {
        val closing = synchronized(monitor) {
            if (closed) return
            closed = true
            primary = null
            telemetrySink = NoOpRedisTelemetrySink
            groups.values.flatMap { it.leases }.filterNot { it.retainedDuringShutdown }
        }
        closing.forEach(::release)
        synchronized(monitor) { completeDrain() }
    }

    fun drainFuture(): CompletableFuture<Unit> = drained.copy()

    fun closeAsync(): CompletableFuture<Unit> {
        close()
        return drained.copy()
    }

    /** Only short bookkeeping is performed under monitor; no socket close can enter it. */
    private fun retire(group: RedisGroup) {
        group.listeners.clear()
        group.dispatchers.clear()
        retiring++
        cleanup.submit {
            group.manager.installTelemetry(NoOpRedisTelemetrySink)
            group.manager.close()
        }.whenComplete { _, failure ->
            synchronized(monitor) {
                retiring--
                if (failure != null) {
                    cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
                }
                completeDrain()
            }
        }
    }

    private fun completeDrain() {
        if (!closed || groups.isNotEmpty() || creating.isNotEmpty() || retiring != 0 || drainScheduled) return
        drainScheduled = true
        val failure = cleanupFailure
        cleanup.execute {
            if (failure == null) drained.complete(Unit) else drained.completeExceptionally(failure)
        }
        cleanup.close()
    }

    private fun createGroup(identity: ConnectionIdentity): RedisGroup {
        val settings = identity.settings
        val manager =
            managerFactory(
                RedisConnection(settings.host, settings.port, settings.username, settings.password),
                identity.origin,
            )
        return RedisGroup(identity, manager)
    }

    private fun register(
        lease: Lease,
        channel: String,
        listener: ArcRedisListener,
    ) = synchronized(monitor) {
        require(channel.isNotBlank()) { "Redis channel cannot be blank" }
        checkLeaseOpen(lease)
        lease.listeners[channel] = listener
        val channelListeners = lease.group.listeners.getOrPut(channel) { linkedMapOf() }
        channelListeners[lease] = listener
        if (lease.group.dispatchers[channel] == null) {
            val dispatcher = ChannelListener { actualChannel, message, origin ->
                dispatch(lease.group, actualChannel, message, origin)
            }
            lease.group.dispatchers[channel] = dispatcher
            lease.group.manager.registerChannelUnique(channel, dispatcher)
        }
        lease.group.manager.reconcileSubscriptions()
    }

    private fun unregister(
        lease: Lease,
        channel: String,
        listener: ArcRedisListener,
    ) = synchronized(monitor) {
        if (!lease.open || lease.listeners[channel] !== listener) return@synchronized
        lease.listeners.remove(channel)
        val channelListeners = lease.group.listeners[channel] ?: return@synchronized
        channelListeners.remove(lease)
        if (channelListeners.isEmpty()) {
            lease.group.listeners.remove(channel)
            val dispatcher = lease.group.dispatchers.remove(channel)
            if (dispatcher != null) lease.group.manager.unregisterChannel(channel, dispatcher)
        }
        lease.group.manager.reconcileSubscriptions()
    }

    private fun release(lease: Lease) = synchronized(monitor) {
        if (!lease.open) return@synchronized
        lease.open = false
        lease.listeners.keys.toList().forEach { channel ->
            val channelListeners = lease.group.listeners[channel]
            channelListeners?.remove(lease)
            if (channelListeners.isNullOrEmpty()) {
                lease.group.listeners.remove(channel)
                val dispatcher = lease.group.dispatchers.remove(channel)
                if (dispatcher != null) lease.group.manager.unregisterChannel(channel, dispatcher)
            }
        }
        lease.listeners.clear()
        lease.group.leases.remove(lease)
        lease.group.manager.reconcileSubscriptions()
        if (lease.group.leases.isEmpty()) {
            groups.remove(lease.group.identity, lease.group)
            retire(lease.group)
        }
        completeDrain()
    }

    private fun dispatch(
        group: RedisGroup,
        channel: String,
        message: String,
        origin: String,
    ) {
        val recipients = synchronized(monitor) {
            group.listeners[channel]?.entries?.map { it.key to it.value }.orEmpty()
        }
        recipients.forEach { (lease, listener) ->
            if (lease.open) {
                runCatching { listener.onMessage(channel, message, origin) }
                    .onFailure { failure ->
                        LOGGER.log(Level.WARNING, "Redis listener failed for ${lease.owner} on $channel", failure)
                    }
            }
        }
    }

    private fun checkLeaseOpen(lease: Lease) {
        check((!closed || lease.retainedDuringShutdown) && lease.open) { "Redis lease for ${lease.owner} is closed" }
    }

    private fun <T> failedLeaseOperation(lease: Lease): CompletableFuture<T> =
        CompletableFuture.failedFuture(IllegalStateException("Redis lease for ${lease.owner} is closed"))

    private inner class Lease(
        val group: RedisGroup,
        val owner: String,
    ) : ArcRedisLease {
        @Volatile
        var open = true
        var retainedDuringShutdown = false

        val listeners = linkedMapOf<String, ArcRedisListener>()

        override fun retainDuringShutdown() = synchronized(monitor) {
            if (!retainedDuringShutdown) {
                check(!closed && open) { "Redis lease cannot begin a drain after provider shutdown" }
                retainedDuringShutdown = true
            }
        }

        override fun publish(channel: String, message: String) {
            synchronized(monitor) { checkLeaseOpen(this) }
            group.manager.publish(channel, message)
        }

        override fun saveMap(key: String, map: Map<String, String>) {
            synchronized(monitor) { checkLeaseOpen(this) }
            group.manager.saveMap(key, map)
        }

        override fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*> =
            if (isOpen()) group.manager.saveMapEntries(key, *keyValuePairs) else failedLeaseOperation<Any?>(this)

        override fun loadMap(key: String): CompletableFuture<Map<String, String>> =
            if (isOpen()) group.manager.loadMap(key) else failedLeaseOperation(this)

        override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> =
            if (isOpen()) group.manager.loadMapEntries(key, *mapKeys) else failedLeaseOperation(this)

        override fun compareAndSetMapEntry(
            key: String,
            mapKey: String,
            expectedValue: String?,
            replacementValue: String?,
        ): CompletableFuture<Boolean> =
            if (isOpen()) group.manager.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue)
            else failedLeaseOperation(this)

        override fun registerChannelUnique(channel: String, listener: ArcRedisListener) =
            register(this, channel, listener)

        override fun unregisterChannel(channel: String, listener: ArcRedisListener) =
            unregister(this, channel, listener)

        override fun init() {
            synchronized(monitor) { checkLeaseOpen(this) }
            group.manager.reconcileSubscriptions()
        }

        override fun healthCheck(): CompletableFuture<Boolean> =
            if (isOpen()) group.manager.healthCheckAsync() else failedLeaseOperation(this)

        override fun isConnected(): Boolean = isOpen() && group.manager.isConnected()

        override fun isSubscriptionActive(): Boolean = isOpen() && group.manager.isSubscriptionActive()

        override fun channelCount(): Int = synchronized(monitor) { if (isOpen()) listeners.size else 0 }

        override fun channels(): Set<String> = synchronized(monitor) { if (isOpen()) listeners.keys.toSet() else emptySet() }

        override fun close() = release(this)

        private fun isOpen(): Boolean = synchronized(monitor) { (!closed || retainedDuringShutdown) && open }
    }

    private inner class RedisGroup(
        val identity: ConnectionIdentity,
        val manager: RedisManager,
    ) {
        val leases = linkedSetOf<Lease>()
        val listeners = linkedMapOf<String, LinkedHashMap<Lease, ArcRedisListener>>()
        val dispatchers = linkedMapOf<String, ChannelListener>()
    }

    private data class ConnectionIdentity(
        val settings: ArcRedisSettings,
        val origin: String,
    )

    private companion object {
        const val MAX_CONNECTION_GROUPS = 128
        val LOGGER: java.util.logging.Logger = java.util.logging.Logger.getLogger(SharedPaperRedisService::class.java.name)
    }
}
