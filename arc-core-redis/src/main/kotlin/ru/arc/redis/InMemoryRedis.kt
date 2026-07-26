package ru.arc.redis

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [RedisOperations] for unit tests — no Jedis / Docker required.
 */
class InMemoryRedis(
    private val serverIdentity: ServerIdentity = ServerIdentity { "test-server" },
) : RedisOperations {

    private val hashes = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val channelListeners = ConcurrentHashMap<String, MutableList<ChannelListener>>()
    private val publishedMessages = CopyOnWriteArrayList<PublishedMessage>()

    val saveOperations = CopyOnWriteArrayList<SaveOperation>()
    val loadOperations = CopyOnWriteArrayList<LoadOperation>()

    var failOnSave = false
    var failOnLoad = false
    var saveDelay: Long = 0
    var loadDelay: Long = 0

    data class PublishedMessage(
        val channel: String,
        val message: String,
        val originServer: String,
    )

    data class SaveOperation(
        val key: String,
        val entries: Map<String, String?>,
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class LoadOperation(
        val key: String,
        val mapKeys: List<String>,
        val timestamp: Long = System.currentTimeMillis(),
    )

    override fun publish(channel: String, message: String) {
        val origin = serverIdentity.name()
        publishedMessages.add(PublishedMessage(channel, message, origin))
        channelListeners[channel]?.forEach { listener ->
            listener.consume(channel, message, origin)
        }
    }

    /** Simulate a message from another server instance. */
    fun simulateExternalMessage(
        channel: String,
        message: String,
        originServer: String = "other-server",
    ) {
        channelListeners[channel]?.forEach { listener ->
            listener.consume(channel, message, originServer)
        }
    }

    override fun saveMap(key: String, map: Map<String, String>) {
        hashes.getOrPut(key) { ConcurrentHashMap() }.putAll(map)
    }

    override fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*> {
        if (keyValuePairs.size % 2 != 0) {
            return CompletableFuture.failedFuture<Unit>(
                IllegalArgumentException("Redis hash entries must contain key/value pairs"),
            )
        }
        val entries = mutableMapOf<String, String?>()
        for (i in keyValuePairs.indices step 2) {
            val mapKey = keyValuePairs[i]
            val value = keyValuePairs.getOrNull(i + 1)
            if (mapKey != null) {
                entries[mapKey] = value
            }
        }
        saveOperations.add(SaveOperation(key, entries))

        return CompletableFuture.supplyAsync {
            if (saveDelay > 0) Thread.sleep(saveDelay)
            if (failOnSave) throw RuntimeException("Simulated save failure")
            val hash = hashes.getOrPut(key) { ConcurrentHashMap() }
            for ((mapKey, value) in entries) {
                if (value == null) {
                    hash.remove(mapKey)
                } else {
                    hash[mapKey] = value
                }
            }
            null
        }
    }

    override fun loadMap(key: String): CompletableFuture<Map<String, String>> {
        loadOperations.add(LoadOperation(key, emptyList()))
        return CompletableFuture.supplyAsync {
            if (loadDelay > 0) Thread.sleep(loadDelay)
            if (failOnLoad) throw RuntimeException("Simulated load failure")
            hashes[key]?.toMap() ?: emptyMap()
        }
    }

    override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> {
        loadOperations.add(LoadOperation(key, mapKeys.toList()))
        return CompletableFuture.supplyAsync {
            if (loadDelay > 0) Thread.sleep(loadDelay)
            if (failOnLoad) throw RuntimeException("Simulated load failure")
            val hash = hashes[key]
            mapKeys.map { hash?.get(it) }
        }
    }

    override fun registerChannelUnique(channel: String, listener: ChannelListener) {
        channelListeners[channel] = mutableListOf(listener)
    }

    override fun unregisterChannel(channel: String, listener: ChannelListener) {
        channelListeners[channel]?.remove(listener)
    }

    override fun init() = Unit

    override fun close() {
        channelListeners.clear()
    }

    fun getHash(key: String): Map<String, String> = hashes[key]?.toMap() ?: emptyMap()

    fun setHash(key: String, data: Map<String, String>) {
        hashes[key] = ConcurrentHashMap(data)
    }

    fun clear() {
        hashes.clear()
        publishedMessages.clear()
        saveOperations.clear()
        loadOperations.clear()
    }

    fun getPublishedMessages(): List<PublishedMessage> = publishedMessages.toList()

    fun listenerCount(channel: String): Int = channelListeners[channel]?.size ?: 0
}
