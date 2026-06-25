package ru.arc.redis

/** Cross-server pub/sub message framing (serverName + payload). */
object RedisWire {
    const val SERVER_DELIMITER = "<>#<>#<>"

    fun encode(serverName: String, payload: String): String = "$serverName$SERVER_DELIMITER$payload"

    fun decode(full: String): Pair<String, String>? {
        val parts = full.split(SERVER_DELIMITER, limit = 2)
        if (parts.size != 2) return null
        return parts[0] to parts[1]
    }
}
