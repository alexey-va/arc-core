package ru.arc.redis

import java.util.concurrent.CompletableFuture

/**
 * Redis pub/sub and hash operations.
 *
 * Extracted for testing via [InMemoryRedis] without a live Jedis connection.
 */
interface RedisOperations {
    fun publish(channel: String, message: String)

    fun saveMap(key: String, map: Map<String, String>)

    fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*>

    fun loadMap(key: String): CompletableFuture<Map<String, String>>

    fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>>

    fun registerChannelUnique(channel: String, listener: ChannelListener)

    fun unregisterChannel(channel: String, listener: ChannelListener)

    fun init()

    fun close()
}
