package ru.arc.redis.resourcepack

object ResourcePackPublication {
    const val CHANNEL = "arc.resourcepack.published"
    const val ACK_KEY = "arc:resourcepack:hash-refresh-acks"
    private const val VERSION_PREFIX = "v1:"
    private const val PAYLOAD_LENGTH = 100
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    private val REQUEST_ID_PATTERN = Regex("^[0-9a-f]{32}$")

    data class Request(
        val sha256: String,
        val requestId: String,
    )

    fun encode(
        sha256: String,
        requestId: String,
    ): String {
        val normalized = sha256.trim().lowercase()
        require(SHA256_PATTERN.matches(normalized)) { "Invalid resource-pack SHA-256" }
        val normalizedRequestId = requestId.trim().lowercase()
        require(REQUEST_ID_PATTERN.matches(normalizedRequestId)) { "Invalid resource-pack request ID" }
        return "$VERSION_PREFIX$normalized:$normalizedRequestId"
    }

    fun decode(payload: String): Request? {
        if (payload.length != PAYLOAD_LENGTH) return null
        if (!payload.startsWith(VERSION_PREFIX)) return null
        val parts = payload.removePrefix(VERSION_PREFIX).split(':')
        if (parts.size != 2) return null
        val sha256 = parts[0]
        val requestId = parts[1]
        if (!SHA256_PATTERN.matches(sha256) || !REQUEST_ID_PATTERN.matches(requestId)) return null
        return Request(sha256, requestId)
    }

    fun encodeAcknowledgement(request: Request): String =
        "$VERSION_PREFIX${request.requestId}:${request.sha256}"
}
