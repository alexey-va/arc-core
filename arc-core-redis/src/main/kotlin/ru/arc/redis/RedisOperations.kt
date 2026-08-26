package ru.arc.redis

import java.util.concurrent.CompletableFuture

/**
 * Redis pub/sub and hash operations.
 *
 * Extracted for testing via [InMemoryRedis] without a live Jedis connection.
 */
interface RedisOperations : AutoCloseable {
    fun publish(channel: String, message: String)

    fun saveMap(key: String, map: Map<String, String>)

    fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*>

    fun loadMap(key: String): CompletableFuture<Map<String, String>>

    fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>>

    /**
     * Atomically replaces one Redis hash entry when its current value exactly
     * matches [expectedValue]. A null expected value means the field must be
     * absent; a null replacement deletes the matching field.
     */
    fun compareAndSetMapEntry(
        key: String,
        mapKey: String,
        expectedValue: String?,
        replacementValue: String?,
    ): CompletableFuture<Boolean>

    fun registerChannelUnique(channel: String, listener: ChannelListener)

    fun unregisterChannel(channel: String, listener: ChannelListener)

    fun init()

    override fun close()
}
