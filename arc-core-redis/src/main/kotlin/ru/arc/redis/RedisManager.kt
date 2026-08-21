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
        private const val COMPARE_AND_SET_HASH_ENTRY_SCRIPT =
            """
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if ARGV[2] == 'absent' then
                if current ~= false then return 0 end
            elseif current == false or current ~= ARGV[3] then
                return 0
            end
            if ARGV[4] == 'delete' then
                redis.call('HDEL', KEYS[1], ARGV[1])
            else
                redis.call('HSET', KEYS[1], ARGV[1], ARGV[5])
            end
            return 1
            """

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
    private var lastSubscriptionReconnectFailureLogMs = 0L

    private val publishReconnectMutex = Mutex()

    @Volatile
    private var telemetrySink: RedisTelemetrySink = NoOpRedisTelemetrySink

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

    @Synchronized
    fun connect(connection: RedisConnection) {
        connect(connection.host, connection.port, connection.username, connection.password)
    }

    @Synchronized
    fun connect(
        ip: String,
        port: Int,
        userName: String?,
        password: String?,
    ) {
        val connection = RedisConnection(ip, port, userName, password)
        resetTransport()
        lastConnection = connection
        isShuttingDown = false
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        subscriptionExecutor = newSubscriptionExecutor()
        try {
            sub = createPool(connection)
            pub = createPool(connection)

            connected = true
            lastPublishReconnectAttemptMs = 0L
            lastPublishReconnectFailureLogMs = 0L
            lastSubscriptionReconnectFailureLogMs = 0L
            lastPublishNotConnectedLogMs = 0L
            logger.debug("Connected to Redis at {}:{}", ip, port)
        } catch (e: Exception) {
            logger.error("Failed to connect to Redis at {}:{}", ip, port, e)
            runCatching { sub?.close() }
            runCatching { pub?.close() }
            sub = null
            pub = null
            connected = false
            throw e
        }
    }

    private fun newSubscriptionExecutor() =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Redis-Subscription-${System.currentTimeMillis()}").apply {
                isDaemon = true
            }
        }

    private fun resetTransport() {
        connected = false
        subscriptionActive = false
        isSubscribing = false
        scope.cancel()
        subscriptionJob?.cancel()
        subscriptionJob = null
        subscriptionThread?.cancel(true)
        subscriptionThread = null
        subscriptionExecutor.shutdownNow()
        runCatching { sub?.close() }
            .onFailure { logger.warn("Failed to close Redis subscription connection", it) }
        runCatching { pub?.close() }
            .onFailure { logger.warn("Failed to close Redis publish connection", it) }
        sub = null
        pub = null
    }

    private fun createPool(connection: RedisConnection): JedisPooled = poolFactory(connection)

    private fun subscriptionConnection(): JedisPooled? {
        val current = sub
        if (current != null) {
            try {
                if (current.ping() == "PONG") return current
            } catch (e: Exception) {
                logger.warn("Redis subscription connection is unavailable; reconnecting", e)
            }
            runCatching { current.close() }
            sub = null
        }

        val connection = lastConnection ?: return null
        var replacement: JedisPooled? = null
        reportReconnect(RedisReconnectPath.SUBSCRIPTION, RedisReconnectResult.ATTEMPT)
        return try {
            replacement = createPool(connection)
            check(replacement.ping() == "PONG") { "Redis subscription PING failed" }
                sub = replacement
                lastSubscriptionReconnectFailureLogMs = 0L
                logger.info("Redis subscription connection restored")
                reportReconnect(RedisReconnectPath.SUBSCRIPTION, RedisReconnectResult.SUCCESS)
                replacement
            } catch (e: Exception) {
                reportReconnect(RedisReconnectPath.SUBSCRIPTION, RedisReconnectResult.FAILURE)
                runCatching { replacement?.close() }
                val now = clockMs()
                if (
                    lastSubscriptionReconnectFailureLogMs == 0L ||
                    now - lastSubscriptionReconnectFailureLogMs >= PUBLISH_NOT_CONNECTED_LOG_INTERVAL_MS
                ) {
                    lastSubscriptionReconnectFailureLogMs = now
                    logger.warn("Redis subscription reconnect failed", e)
                } else {
                    logger.debug("Redis subscription reconnect still unavailable: {}", e.message)
                }
                null
        }
    }

    private fun retrySubscriptionLater() {
        if (isShuttingDown) return
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (!isShuttingDown && connected) init()
        }
    }

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
            val started = System.nanoTime()
            var result = RedisOperationResult.FAILURE
            if (!ensurePublishReady()) {
                result = RedisOperationResult.UNAVAILABLE
                logPublishNotConnected(channel)
                reportOperation(RedisOperation.PUBLISH, result, started)
                return@launch
            }

            val fullMessage = RedisWire.encode(serverIdentity.name(), message)
            try {
                pub!!.publish(channel, fullMessage)
                result = RedisOperationResult.SUCCESS
            } catch (e: Exception) {
                if (e is JedisConnectionException) {
                    connected = false
                    if (ensurePublishReady()) {
                        try {
                            pub!!.publish(channel, fullMessage)
                            result = RedisOperationResult.SUCCESS
                            return@launch
                        } catch (retry: Exception) {
                            if (retry is JedisConnectionException) connected = false
                            logger.error("Error publishing to channel {} after reconnect", channel, retry)
                            return@launch
                        }
                    }
                }
                logger.error("Error publishing to channel {}", channel, e)
            } finally {
                reportOperation(RedisOperation.PUBLISH, result, started)
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
            var replacementSub: JedisPooled? = null
            var replacementPub: JedisPooled? = null
            reportReconnect(RedisReconnectPath.PUBLISH, RedisReconnectResult.ATTEMPT)
            try {
                if (sub == null) {
                    replacementSub = createPool(connection)
                }
                replacementPub = createPool(connection)
                if (replacementPub.ping() == "PONG") {
                    pub?.close()
                    pub = replacementPub
                    replacementPub = null
                    if (replacementSub != null) {
                        sub = replacementSub
                        replacementSub = null
                    }
                    connected = true
                    lastPublishReconnectAttemptMs = 0L
                    lastPublishReconnectFailureLogMs = 0L
                    lastPublishNotConnectedLogMs = 0L
                    logger.info("Redis publish connection restored")
                    if (channelList.isNotEmpty() && !subscriptionActive && !isSubscribing) {
                        scope.launch { init() }
                    }
                    reportReconnect(RedisReconnectPath.PUBLISH, RedisReconnectResult.SUCCESS)
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
            } finally {
                runCatching { replacementSub?.close() }
                runCatching { replacementPub?.close() }
            }
            connected = false
            reportReconnect(RedisReconnectPath.PUBLISH, RedisReconnectResult.FAILURE)
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
        if (!connected || isShuttingDown || pubConnection == null) {
            reportOperation(RedisOperation.SAVE_MAP, RedisOperationResult.UNAVAILABLE, System.nanoTime())
            return
        }

        scope.launch(Dispatchers.IO) {
            val started = System.nanoTime()
            var result = RedisOperationResult.FAILURE
            try {
                pubConnection.hmset(key, map)
                result = RedisOperationResult.SUCCESS
            } catch (e: Exception) {
                if (e is JedisConnectionException) connected = false
                logger.error("Error saving map to key: {}", key, e)
            } finally {
                reportOperation(RedisOperation.SAVE_MAP, result, started)
            }
        }
    }

    override fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*> {
        if (keyValuePairs.isEmpty()) return CompletableFuture.completedFuture(null)
        if (keyValuePairs.size % 2 != 0) {
            return CompletableFuture.failedFuture<Unit>(
                IllegalArgumentException("Redis hash entries must contain key/value pairs"),
            )
        }

        if (isShuttingDown) {
            return CompletableFuture.failedFuture<Unit>(
                IllegalStateException("Cannot save Redis hash '$key': Redis is not connected"),
            )
        }

        return scope
            .async(Dispatchers.IO) {
                val started = System.nanoTime()
                var result = RedisOperationResult.FAILURE
                try {
                    if (!ensurePublishReady()) {
                        error("Cannot save Redis hash '$key': Redis is not connected")
                    }
                    val pubConnection = checkNotNull(pub)
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
                    result = RedisOperationResult.SUCCESS
                } catch (e: Exception) {
                    if (e is JedisConnectionException) connected = false
                    logger.error("Error saving map entries for key: {}", key, e)
                    throw e
                } finally {
                    reportOperation(RedisOperation.SAVE_MAP_ENTRIES, result, started)
                }
            }.asCompletableFuture()
    }

    override fun loadMap(key: String): CompletableFuture<Map<String, String>> {
        if (isShuttingDown) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Cannot load Redis hash '$key': Redis is not connected"),
            )
        }

        return scope
            .async(Dispatchers.IO) {
                val started = System.nanoTime()
                var result = RedisOperationResult.FAILURE
                try {
                    if (!ensurePublishReady()) {
                        error("Cannot load Redis hash '$key': Redis is not connected")
                    }
                    val pubConnection = checkNotNull(pub)
                    pubConnection.hgetAll(key).also {
                        result = RedisOperationResult.SUCCESS
                    }
                } catch (e: Exception) {
                    if (e is JedisConnectionException) connected = false
                    logger.error("Error loading map from key: {}", key, e)
                    throw e
                } finally {
                    reportOperation(RedisOperation.LOAD_MAP, result, started)
                }
            }.asCompletableFuture()
    }

    override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> {
        if (mapKeys.isEmpty()) return CompletableFuture.completedFuture(emptyList())

        if (isShuttingDown) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Cannot load Redis hash '$key': Redis is not connected"),
            )
        }

        return scope
            .async(Dispatchers.IO) {
                val started = System.nanoTime()
                var result = RedisOperationResult.FAILURE
                try {
                    if (!ensurePublishReady()) {
                        error("Cannot load Redis hash '$key': Redis is not connected")
                    }
                    val pubConnection = checkNotNull(pub)
                    pubConnection.hmget(key, *mapKeys).also {
                        result = RedisOperationResult.SUCCESS
                    }
                } catch (e: Exception) {
                    if (e is JedisConnectionException) connected = false
                    logger.error("Error loading map entries from key: {}", key, e)
                    throw e
                } finally {
                    reportOperation(RedisOperation.LOAD_MAP_ENTRIES, result, started)
                }
            }.asCompletableFuture()
    }

    override fun compareAndSetMapEntry(
        key: String,
        mapKey: String,
        expectedValue: String?,
        replacementValue: String?,
    ): CompletableFuture<Boolean> {
        require(key.isNotBlank()) { "Redis hash key must not be blank" }
        require(mapKey.isNotBlank()) { "Redis hash field must not be blank" }
        if (isShuttingDown) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Cannot update Redis hash '$key': Redis is not connected"),
            )
        }

        return scope
            .async(Dispatchers.IO) {
                val started = System.nanoTime()
                var result = RedisOperationResult.FAILURE
                try {
                    if (!ensurePublishReady()) {
                        error("Cannot update Redis hash '$key': Redis is not connected")
                    }
                    val response =
                        checkNotNull(pub).eval(
                            COMPARE_AND_SET_HASH_ENTRY_SCRIPT,
                            listOf(key),
                            listOf(
                                mapKey,
                                if (expectedValue == null) "absent" else "present",
                                expectedValue.orEmpty(),
                                if (replacementValue == null) "delete" else "replace",
                                replacementValue.orEmpty(),
                            ),
                        )
                    result = RedisOperationResult.SUCCESS
                    (response as? Number)?.toLong() == 1L
                } catch (e: Exception) {
                    if (e is JedisConnectionException) connected = false
                    logger.error("Error comparing Redis hash entry for key: {}", key, e)
                    throw e
                } finally {
                    reportOperation(RedisOperation.COMPARE_AND_SET_MAP_ENTRY, result, started)
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

                    val subConnection = subscriptionConnection()
                    if (subConnection == null) {
                        logger.debug("Redis init(): subscription connection unavailable, retrying")
                        isSubscribing = false
                        retrySubscriptionLater()
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
                                retrySubscriptionLater()
                            }
                        }
                }
            }
    }

    fun isSubscriptionActive(): Boolean = subscriptionActive

    @Synchronized
    override fun close() {
        if (isShuttingDown) return

        isShuttingDown = true

        try {
            resetTransport()
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
            val started = System.nanoTime()
            var result = RedisOperationResult.FAILURE
            try {
                (pub?.ping() == "PONG").also { healthy ->
                    result =
                        if (healthy) {
                            RedisOperationResult.SUCCESS
                        } else {
                            RedisOperationResult.UNAVAILABLE
                        }
                }
            } catch (e: Exception) {
                logger.error("Redis health check failed", e)
                false
            } finally {
                reportOperation(RedisOperation.HEALTH_CHECK, result, started)
            }
        }

    fun installTelemetry(sink: RedisTelemetrySink) {
        telemetrySink = sink
    }

    private fun reportOperation(
        operation: RedisOperation,
        result: RedisOperationResult,
        startedNanos: Long,
    ) {
        runCatching {
            telemetrySink.onOperation(
                operation,
                result,
                (System.nanoTime() - startedNanos).coerceAtLeast(0L),
            )
        }
    }

    private fun reportReconnect(
        path: RedisReconnectPath,
        result: RedisReconnectResult,
    ) {
        runCatching {
            telemetrySink.onReconnect(path, result)
        }
    }
}
