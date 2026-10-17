package ru.arc.paper.api

import java.util.concurrent.CompletableFuture

/** Complete Redis connection identity used to share one provider-owned client group. */
data class ArcRedisSettings(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
) {
    init {
        require(host.isNotBlank()) { "Redis host cannot be blank" }
        require(port in 1..65_535) { "Redis port must be between 1 and 65535" }
    }

    override fun toString(): String =
        "ArcRedisSettings(host=$host, port=$port, username=$username, password=<redacted>)"
}

/** Listener invoked on the Redis subscription thread; implementations must marshal Paper work themselves. */
fun interface ArcRedisListener {
    fun onMessage(channel: String, message: String, origin: String)
}

/** A caller-owned view over a provider-owned Redis connection group. */
interface ArcRedisLease : AutoCloseable {
    /** Retain an existing lease for the consumer's terminal async flush; close it after the flush. */
    fun retainDuringShutdown() = Unit
    fun publish(channel: String, message: String)

    fun saveMap(key: String, map: Map<String, String>)

    fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*>

    fun loadMap(key: String): CompletableFuture<Map<String, String>>

    fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>>

    fun compareAndSetMapEntry(
        key: String,
        mapKey: String,
        expectedValue: String?,
        replacementValue: String?,
    ): CompletableFuture<Boolean>

    /** Keeps one listener for this lease and channel; other leases remain registered. */
    fun registerChannelUnique(channel: String, listener: ArcRedisListener)

    /** Removes this listener only when it is the listener currently registered by this lease. */
    fun unregisterChannel(channel: String, listener: ArcRedisListener)

    /** Starts or reconciles this lease's subscriptions without resetting peer leases. */
    fun init()

    /** Performs a Redis PING; use for startup/readiness checks, never a tick or request path. */
    fun healthCheck(): CompletableFuture<Boolean>

    fun isConnected(): Boolean

    fun isSubscriptionActive(): Boolean

    fun channelCount(): Int

    fun channels(): Set<String>

    override fun close()
}

/** Runtime service installed by the Paper host and resolved through Bukkit ServicesManager. */
interface ArcRedisService : AutoCloseable {
    /** Cold transport construction is worker-only; capture this service through Paper before dispatch. */
    fun acquire(owner: String, settings: ArcRedisSettings, origin: String): ArcRedisLease

    /** Acquires the host-configured primary group without disclosing its settings to the borrower. */
    fun acquirePrimary(owner: String): ArcRedisLease

    fun diagnostics(): ArcRedisDiagnostics

    override fun close()
}

/** Bounded provider health without connection credentials or channel payloads. */
data class ArcRedisDiagnostics(
    val activeLeases: Int,
    val connectionGroups: Int,
    val connectedGroups: Int,
    val activeChannels: Int,
) {
    init {
        require(activeLeases >= 0) { "Active lease count cannot be negative" }
        require(connectionGroups >= 0) { "Connection group count cannot be negative" }
        require(activeLeases == 0 || connectionGroups > 0) { "Active leases require at least one connection group" }
        require(connectedGroups in 0..connectionGroups) { "Connected group count must fit the connection group count" }
        require(activeChannels >= 0) { "Active channel count cannot be negative" }
    }
}
