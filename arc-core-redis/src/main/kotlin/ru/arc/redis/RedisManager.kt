package ru.arc.redis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.JedisPubSub
import redis.clients.jedis.exceptions.JedisConnectionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Jedis-based Redis manager: pub/sub with reconnect, hash ops, coroutine async I/O.
 */
class RedisManager(
    connection: RedisConnection,
    private val serverIdentity: ServerIdentity,
    private val logger: Logger = LoggerFactory.getLogger(RedisManager::class.java),
    private val poolFactory: (RedisConnection) -> JedisPooled = ::createDefaultPool,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : JedisPubSub(), RedisOperations {

    /** Legacy constructor for tests and gradual migration. */
    constructor(
        ip: String,
        port: Int,
        userName: String?,
        password: String?,
    ) : this(
        RedisConnection(ip, port, userName, password),
        ServerIdentity { "test-server" },
    )

    companion object {
        private const val INIT_DELAY_MS = 1000L
        private const val RECONNECT_DELAY_MS = 100L
        private const val PUBLISH_RECONNECT_MIN_INTERVAL_MS = 5_000L
        private const val PUBLISH_NOT_CONNECTED_LOG_INTERVAL_MS = 30_000L

        private fun createDefaultPool(connection: RedisConnection): JedisPooled =
            if (connection.username != null && connection.password != null) {
                JedisPooled(connection.host, connection.port, connection.username, connection.password)
            } else {
                JedisPooled(connection.host, connection.port)
            }
    }

    @Volatile
    private var sub: JedisPooled? = null

    @Volatile
    private var pub: JedisPooled? = null

    @Volatile
    private var lastConnection: RedisConnection? = null

    @Volatile
    private var lastPublishNotConnectedLogMs = 0L

    private var lastPublishReconnectAttemptMs = 0L
    private var lastPublishReconnectFailureLogMs = 0L

    private val publishReconnectMutex = Mutex()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val channelListeners = ConcurrentHashMap<String, MutableList<ChannelListener>>()
    private val channelList = ConcurrentHashMap.newKeySet<String>()

    private val subscriptionMutex = Mutex()
    private var subscriptionJob: kotlinx.coroutines.Job? = null
    private var subscriptionThread: Future<*>? = null
    private var subscriptionExecutor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "Redis-Subscription-${System.currentTimeMillis()}").apply {
                isDaemon = true
            }
        }

    @Volatile
    private var isSubscribing = false

    @Volatile
    private var subscriptionActive = false

    @Volatile
    private var connected = false

    @Volatile
    private var isShuttingDown = false

    init {
        try {
            connect(connection)
        } catch (e: Exception) {
            logger.error(
                "Failed to connect to Redis at {}:{} during initialization",
                connection.host,
                connection.port,
                e,
            )
            connected = false
        }
    }

    fun connect(connection: RedisConnection) {
        connect(connection.host, connection.port, connection.username, connection.password)
    }

    fun connect(
        ip: String,
        port: Int,
        userName: String?,
        password: String?,
    ) {
        lastConnection = RedisConnection(ip, port, userName, password)
        val oldExecutor = subscriptionExecutor
        try {
            subscriptionExecutor =
                Executors.newSingleThreadExecutor { r ->
                    Thread(r, "Redis-Subscription-${System.currentTimeMillis()}").apply {
                        isDaemon = true
                    }
                }

            close()
            isShuttingDown = false
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            val connection = lastConnection!!
            sub = createPool(connection)
            pub = createPool(connection)

            connected = true
            logger.debug("Connected to Redis at {}:{}", ip, port)
            oldExecutor.shutdownNow()
        } catch (e: Exception) {
            logger.error("Failed to connect to Redis at {}:{}", ip, port, e)
            connected = false
            subscriptionExecutor.shutdownNow()
            subscriptionExecutor = oldExecutor
            throw e
        }
    }

    private fun createPool(connection: RedisConnection): JedisPooled = poolFactory(connection)

    override fun onPong(message: String) = Unit

    override fun onMessage(
        channel: String,
        message: String,
    ) {
        onMessageInternal(channel, message)
    }

    private fun onMessageInternal(
        channel: String,
        message: String,
    ) {
        try {
            val listeners = channelListeners[channel]
            if (listeners.isNullOrEmpty()) {
                logger.warn("No listeners registered for channel: {}", channel)
                return
            }

            val decoded = RedisWire.decode(message)
            if (decoded == null) {
                logger.error("Invalid message format on channel {}: {}", channel, message)
                return
            }

            val (originServer, actualMessage) = decoded
            listeners.forEach { listener ->
                try {
                    listener.consume(channel, actualMessage, originServer)
                } catch (e: Exception) {
                    logger.error("Error in channel listener for {}", channel, e)
                }
            }
        } catch (e: Exception) {
            logger.error("Error processing message on channel {}", channel, e)
        }
    }

    override fun onSubscribe(
        channel: String,
        subscribedChannels: Int,
    ) {
        subscriptionActive = true
        logger.info("Subscribed to channel: {} (total: {})", channel, subscribedChannels)
    }

    override fun onUnsubscribe(channel: String, subscribedChannels: Int) = Unit

    override fun publish(channel: String, message: String) {
        publishInternal(channel, message)
    }

    private fun publishInternal(channel: String, message: String) {
        if (isShuttingDown) return

        scope.launch(Dispatchers.IO) {
            if (!ensurePublishReady()) {
                logPublishNotConnected(channel)
                return@launch
            }

            val fullMessage = RedisWire.encode(serverIdentity.name(), message)
            try {
                pub!!.publish(channel, fullMessage)
            } catch (e: Exception) {
                if (e is JedisConnectionException) {
                    connected = false
                    if (ensurePublishReady()) {
                        try {
                            pub!!.publish(channel, fullMessage)
                            return@launch
                        } catch (retry: Exception) {
                            if (retry is JedisConnectionException) connected = false
                            logger.error("Error publishing to channel {} after reconnect", channel, retry)
                            return@launch
                        }
                    }
                }
                logger.error("Error publishing to channel {}", channel, e)
            }
        }
    }

    private suspend fun ensurePublishReady(): Boolean {
        if (connected && pub != null) return true

        return publishReconnectMutex.withLock {
            if (connected && pub != null) return true
            val connection = lastConnection ?: return false
            val now = clockMs()
            if (
                lastPublishReconnectAttemptMs > 0L &&
                now - lastPublishReconnectAttemptMs < PUBLISH_RECONNECT_MIN_INTERVAL_MS
            ) {
                return false
            }
            lastPublishReconnectAttemptMs = now
            try {
                pub?.close()
                pub = createPool(connection)
                if (pub?.ping() == "PONG") {
                    connected = true
                    lastPublishReconnectAttemptMs = 0L
                    lastPublishReconnectFailureLogMs = 0L
                    lastPublishNotConnectedLogMs = 0L
                    logger.info("Redis publish connection restored")
                    return true
                }
            } catch (e: Exception) {
                if (
                    lastPublishReconnectFailureLogMs == 0L ||
                    now - lastPublishReconnectFailureLogMs >= PUBLISH_NOT_CONNECTED_LOG_INTERVAL_MS
                ) {
                    lastPublishReconnectFailureLogMs = now
                    logger.warn("Redis publish reconnect failed", e)
                } else {
                    logger.debug("Redis publish reconnect still unavailable: {}", e.message)
                }
            }
            connected = false
            false
        }
    }

    @Synchronized
    private fun logPublishNotConnected(channel: String) {
        val now = clockMs()
        if (now - lastPublishNotConnectedLogMs < PUBLISH_NOT_CONNECTED_LOG_INTERVAL_MS) return
        lastPublishNotConnectedLogMs = now
        logger.warn("Cannot publish: Redis not connected (channel: {})", channel)
    }

    override fun saveMap(key: String, map: Map<String, String>) {
        val pubConnection = pub
        if (!connected || isShuttingDown || pubConnection == null) return

        scope.launch(Dispatchers.IO) {
            try {
                pubConnection.hmset(key, map)
            } catch (e: Exception) {
                if (e is JedisConnectionException) connected = false
                logger.error("Error saving map to key: {}", key, e)
            }
        }
    }

    override fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*> {
        if (keyValuePairs.isEmpty()) return CompletableFuture.completedFuture(null)

        val pubConnection = pub
        if (!connected || isShuttingDown || pubConnection == null) {
            return CompletableFuture.completedFuture(null)
        }

        return scope
            .async(Dispatchers.IO) {
                try {
                    val pairs =
                        buildList {
                            for (i in keyValuePairs.indices step 2) {
                                val k = keyValuePairs[i] ?: continue
                                val v = if (i + 1 < keyValuePairs.size) keyValuePairs[i + 1] else null
                                add(k to v)
                            }
                        }
                    val toDelete = pairs.filter { it.second == null }.map { it.first }.toTypedArray()
                    val toUpdate = pairs.filter { it.second != null }.associate { it.first to it.second!! }

                    if (toDelete.isNotEmpty()) pubConnection.hdel(key, *toDelete)
                    if (toUpdate.isNotEmpty()) pubConnection.hmset(key, toUpdate)
                } catch (e: Exception) {
                    if (e is JedisConnectionException) connected = false
                    logger.error("Error saving map entries for key: {}", key, e)
                }
            }.asCompletableFuture()
    }

    override fun loadMap(key: String): CompletableFuture<Map<String, String>> {
        val pubConnection = pub
        if (!connected || isShuttingDown || pubConnection == null) {
            return CompletableFuture.completedFuture(emptyMap())
        }

        return scope
            .async(Dispatchers.IO) {
                try {
                    pubConnection.hgetAll(key)
                } catch (e: Exception) {
                    if (e is JedisConnectionException) connected = false
                    logger.error("Error loading map from key: {}", key, e)
                    emptyMap()
                }
            }.asCompletableFuture()
    }

    override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> {
        if (mapKeys.isEmpty()) return CompletableFuture.completedFuture(emptyList())

        val pubConnection = pub
        if (!connected || isShuttingDown || pubConnection == null) {
            return CompletableFuture.completedFuture(List(mapKeys.size) { null })
        }

        return scope
            .async(Dispatchers.IO) {
                try {
                    pubConnection.hmget(key, *mapKeys)
                } catch (e: Exception) {
                    if (e is JedisConnectionException) connected = false
                    logger.error("Error loading map entries from key: {}", key, e)
                    List(mapKeys.size) { null }
                }
            }.asCompletableFuture()
    }

    override fun registerChannelUnique(channel: String, listener: ChannelListener) {
        channelListeners.computeIfAbsent(channel) { CopyOnWriteArrayList() }.apply {
            clear()
            add(listener)
        }
        channelList.add(channel)
    }

    override fun unregisterChannel(channel: String, listener: ChannelListener) {
        channelListeners[channel]?.remove(listener)
        if (channelListeners[channel].isNullOrEmpty()) {
            channelListeners.remove(channel)
            channelList.remove(channel)
        }
    }

    override fun init() {
        if (isShuttingDown || !connected) {
            logger.error("Redis init() skipped: isShuttingDown={}, connected={}", isShuttingDown, connected)
            return
        }

        if (isSubscribing) {
            try {
                unsubscribe()
            } catch (_: Exception) {
            }
            Thread.sleep(50)
        }
        subscriptionJob?.cancel()
        subscriptionThread?.cancel(true)
        isSubscribing = false
        subscriptionActive = false

        subscriptionJob =
            scope.launch {
                subscriptionMutex.withLock {
                    if (isSubscribing) {
                        logger.warn("Redis subscription already in progress, skipping")
                        return@withLock
                    }
                    if (channelList.isEmpty()) {
                        logger.error("Redis init(): no channels registered, aborting")
                        return@withLock
                    }

                    isSubscribing = true
                    delay(INIT_DELAY_MS)

                    if (isShuttingDown || !connected) {
                        logger.error("Redis init(): shutdown detected after delay, aborting")
                        isSubscribing = false
                        return@withLock
                    }

                    val subConnection = sub
                    if (subConnection == null) {
                        logger.error("Redis init(): sub connection is null, aborting")
                        isSubscribing = false
                        return@withLock
                    }

                    try {
                        if (subConnection.ping() != "PONG") {
                            logger.error("Redis init(): PING failed, aborting")
                            isSubscribing = false
                            return@withLock
                        }
                    } catch (e: Exception) {
                        logger.error("Redis init(): connection test failed", e)
                        isSubscribing = false
                        return@withLock
                    }

                    if (!coroutineContext.isActive || isShuttingDown || !connected) {
                        isSubscribing = false
                        return@withLock
                    }

                    if (subscriptionExecutor.isShutdown || subscriptionExecutor.isTerminated) {
                        subscriptionExecutor =
                            Executors.newSingleThreadExecutor { r ->
                                Thread(r, "Redis-Subscription-${System.currentTimeMillis()}").apply {
                                    isDaemon = true
                                }
                            }
                    }

                    val channels = channelList.toTypedArray()
                    subscriptionThread =
                        subscriptionExecutor.submit {
                            try {
                                subConnection.subscribe(this@RedisManager, *channels)
                            } catch (e: Exception) {
                                isSubscribing = false
                                subscriptionActive = false

                                if (isShuttingDown) {
                                    logger.debug("Redis subscription closed during shutdown")
                                    return@submit
                                }

                                logger.error("Redis subscription thread exception", e)
                                Thread.sleep(RECONNECT_DELAY_MS)
                                if (!isShuttingDown && connected) {
                                    scope.launch { init() }
                                }
                            }
                        }
                }
            }
    }

    fun isSubscriptionActive(): Boolean = subscriptionActive

    override fun close() {
        if (isShuttingDown) return

        isShuttingDown = true
        connected = false
        subscriptionActive = false

        try {
            scope.cancel()
            subscriptionJob?.cancel()
            subscriptionJob = null
            subscriptionThread?.cancel(true)
            subscriptionThread = null
            subscriptionExecutor.shutdownNow()
            sub?.close()
            pub?.close()
            sub = null
            pub = null
            channelListeners.clear()
            channelList.clear()
            logger.info("RedisManager closed")
        } catch (e: Exception) {
            logger.error("Error closing RedisManager", e)
        }
    }

    fun isConnected(): Boolean = connected && !isShuttingDown

    fun getChannelCount(): Int = channelList.size

    fun getChannels(): Set<String> = channelList.toSet()

    suspend fun healthCheck(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                pub?.ping() == "PONG"
            } catch (e: Exception) {
                logger.error("Redis health check failed", e)
                false
            }
        }
}
