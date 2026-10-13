package ru.arc.paper.redis

import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcRedisListener
import ru.arc.paper.api.ArcRedisLease
import ru.arc.paper.api.ArcRedisService
import ru.arc.paper.api.ArcRedisSettings
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisConnection
import ru.arc.redis.RedisOperations
import java.util.concurrent.CompletableFuture

/** Local RedisOperations adapter backed by ARC's shared service lease. */
class PaperRedisClient private constructor(
    private val lease: ArcRedisLease,
) : RedisOperations {
    companion object {
        /** Opens a lease for the supplied connection and wire origin. */
        @JvmStatic
        fun open(plugin: Plugin, connection: RedisConnection, origin: String): PaperRedisClient {
            val service = requireService(plugin)
            val settings =
                ArcRedisSettings(
                    host = connection.host,
                    port = connection.port,
                    username = connection.username,
                    password = connection.password,
                )
            return PaperRedisClient(service.acquire(plugin.name, settings, origin))
        }

        /** Opens a lease over the primary Redis connection configured by the ARC host. */
        @JvmStatic
        fun openPrimary(plugin: Plugin): PaperRedisClient =
            PaperRedisClient(requireService(plugin).acquirePrimary(plugin.name))

        private fun requireService(plugin: Plugin): ArcRedisService =
            checkNotNull(plugin.server.servicesManager.load(ArcRedisService::class.java)) {
                "Required ArcRedisService is not registered; declare the ARC host plugin dependency"
            }
    }

    override fun publish(channel: String, message: String) = lease.publish(channel, message)

    override fun saveMap(key: String, map: Map<String, String>) = lease.saveMap(key, map)

    override fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*> =
        lease.saveMapEntries(key, *keyValuePairs)

    override fun loadMap(key: String): CompletableFuture<Map<String, String>> = lease.loadMap(key)

    override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> =
        lease.loadMapEntries(key, *mapKeys)

    override fun compareAndSetMapEntry(
        key: String,
        mapKey: String,
        expectedValue: String?,
        replacementValue: String?,
    ): CompletableFuture<Boolean> =
        lease.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue)

    override fun registerChannelUnique(channel: String, listener: ChannelListener) =
        synchronized(registeredListeners) {
            val adapted = ArcRedisListener { actualChannel, message, origin ->
                listener.consume(actualChannel, message, origin)
            }
            lease.registerChannelUnique(channel, adapted)
            registeredListeners[channel] = listener to adapted
        }

    override fun unregisterChannel(channel: String, listener: ChannelListener) {
        // Preserve the exact listener identity registered by this adapter.
        synchronized(registeredListeners) {
            val registered = registeredListeners[channel] ?: return
            if (registered.first !== listener) return
            lease.unregisterChannel(channel, registered.second)
            registeredListeners.remove(channel)
        }
    }

    override fun init() = lease.init()

    override fun close() {
        try {
            lease.close()
        } finally {
            synchronized(registeredListeners) { registeredListeners.clear() }
        }
    }

    fun healthCheck(): CompletableFuture<Boolean> = lease.healthCheck()

    fun isConnected(): Boolean = lease.isConnected()

    fun isSubscriptionActive(): Boolean = lease.isSubscriptionActive()

    fun channelCount(): Int = lease.channelCount()

    fun channels(): Set<String> = lease.channels()

    fun getChannelCount(): Int = lease.channelCount()

    fun getChannels(): Set<String> = lease.channels()

    private val registeredListeners = linkedMapOf<String, Pair<ChannelListener, ArcRedisListener>>()
}
