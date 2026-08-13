package ru.arc.redis.activity

import ru.arc.redis.RedisOperations
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Network-wide player activity timestamps backed by one bounded Redis hash.
 *
 * Velocity is the authoritative writer. Paper collectors consume one full hash
 * read instead of issuing a database query per economy account.
 */
class PlayerActivityStore(
    private val redis: RedisOperations,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val writeTails = ConcurrentHashMap<UUID, CompletableFuture<*>>()
    private val latestRequested = ConcurrentHashMap<UUID, Long>()

    /**
     * Persist the latest observed network activity for a player.
     *
     * Writes for the same player are chained so a late async login write cannot
     * overwrite a newer disconnect or heartbeat timestamp.
     */
    fun markSeen(
        playerId: UUID,
        timestamp: Long = clockMillis(),
    ): CompletableFuture<*> {
        require(timestamp > 0L) { "Player activity timestamp must be positive" }
        val playerKey = playerId.toString()
        latestRequested.merge(playerId, timestamp, ::maxOf)
        var scheduled: CompletableFuture<*>? = null
        writeTails.compute(playerId) { _, previous ->
            val latestTimestamp = checkNotNull(latestRequested[playerId])
            val predecessor =
                previous?.handle { _, _ -> null }
                    ?: CompletableFuture.completedFuture(null)
            predecessor
                .thenCompose {
                    redis.saveMapEntries(
                        REDIS_KEY,
                        playerKey,
                        latestTimestamp.toString(),
                    )
                }.also { scheduled = it }
        }
        return checkNotNull(scheduled).also { future ->
            future.whenComplete { _, _ -> writeTails.remove(playerId, future) }
        }
    }

    /**
     * Establish the observation boundary once the Velocity writer is active.
     * A single production proxy is authoritative, so a read-before-write is
     * sufficient and keeps the Redis abstraction free of project-specific Lua.
     */
    fun ensureCoverageStartedAt(timestamp: Long = clockMillis()): CompletableFuture<Long> {
        require(timestamp > 0L) { "Player activity coverage timestamp must be positive" }
        return redis.loadMapEntries(REDIS_KEY, COVERAGE_STARTED_AT_FIELD)
            .thenCompose { values ->
                values.firstOrNull()?.toLongOrNull()?.takeIf { it > 0L }?.let {
                    return@thenCompose CompletableFuture.completedFuture(it)
                }
                redis.saveMapEntries(
                    REDIS_KEY,
                    COVERAGE_STARTED_AT_FIELD,
                    timestamp.toString(),
                ).thenApply { timestamp }
            }
    }

    fun load(): CompletableFuture<PlayerActivitySnapshot> =
        redis.loadMap(REDIS_KEY).thenApply(::decode)

    private fun decode(entries: Map<String, String>): PlayerActivitySnapshot {
        var invalidEntries = 0
        val coverageStartedAt =
            entries[COVERAGE_STARTED_AT_FIELD]
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                .also { if (entries.containsKey(COVERAGE_STARTED_AT_FIELD) && it == null) invalidEntries++ }
        val lastSeen = LinkedHashMap<UUID, Long>()
        entries.forEach { (key, value) ->
            if (key == COVERAGE_STARTED_AT_FIELD) return@forEach
            val playerId = runCatching { UUID.fromString(key) }.getOrNull()
            val timestamp = value.toLongOrNull()?.takeIf { it > 0L }
            if (playerId == null || timestamp == null) {
                invalidEntries++
            } else {
                lastSeen[playerId] = timestamp
            }
        }
        return PlayerActivitySnapshot(
            coverageStartedAt = coverageStartedAt,
            lastSeen = lastSeen,
            invalidEntries = invalidEntries,
        )
    }

    companion object {
        const val REDIS_KEY = "arc:player-activity:v1"
        private const val COVERAGE_STARTED_AT_FIELD = "_coverage_started_at"
    }
}

data class PlayerActivitySnapshot(
    val coverageStartedAt: Long?,
    val lastSeen: Map<UUID, Long>,
    val invalidEntries: Int,
)
