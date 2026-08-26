package ru.arc.redis.safety

import java.util.concurrent.ConcurrentHashMap

enum class MessageClaimResult {
    ACCEPTED,
    DUPLICATE,
    CAPACITY_EXCEEDED,
}

/** Bounded in-memory replay guard with rollback-safe wall-clock expiry. */
class RecentMessageDeduplicator(
    private val ttlMillis: Long,
    private val maxEntries: Int = 10_000,
) {
    private val expiresAtById = ConcurrentHashMap<String, Long>()

    init {
        require(ttlMillis in 1..MAX_TTL_MILLIS) { "Message deduplication TTL must be between 1 ms and 24 hours" }
        require(maxEntries in 1..1_000_000) { "Message deduplication capacity must be between 1 and 1000000" }
    }

    fun claim(messageId: String, nowMillis: Long): MessageClaimResult {
        validateMessageId(messageId)
        require(nowMillis >= 0L) { "Message observation time must not be negative" }
        purgeExpired(nowMillis)
        synchronized(expiresAtById) {
            val existing = expiresAtById[messageId]
            if (existing != null && existing > nowMillis) return MessageClaimResult.DUPLICATE
            if (expiresAtById.size >= maxEntries && !expiresAtById.containsKey(messageId)) {
                return MessageClaimResult.CAPACITY_EXCEEDED
            }
            val expiry = if (nowMillis > Long.MAX_VALUE - ttlMillis) Long.MAX_VALUE else nowMillis + ttlMillis
            expiresAtById[messageId] = expiry
            return MessageClaimResult.ACCEPTED
        }
    }

    fun purgeExpired(nowMillis: Long): Int = synchronized(expiresAtById) {
        require(nowMillis >= 0L) { "Message observation time must not be negative" }
        var removed = 0
        expiresAtById.entries.removeIf { (_, expiresAt) ->
            (expiresAt <= nowMillis).also { if (it) removed++ }
        }
        removed
    }

    fun size(): Int = expiresAtById.size

    private fun validateMessageId(messageId: String) {
        require(messageId.length in 1..256) { "Message id must contain 1 to 256 characters" }
        require(messageId.all { it.isLetterOrDigit() || it in ":._-" }) { "Message id contains unsafe characters" }
    }

    private companion object {
        const val MAX_TTL_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
