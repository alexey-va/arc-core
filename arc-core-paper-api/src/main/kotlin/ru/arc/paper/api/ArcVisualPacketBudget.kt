package ru.arc.paper.api

/**
 * Server-wide admission budget for opt-in plugin visuals, registered by ARC.
 *
 * Consumers compile against this API; only the host embeds it. All methods are
 * thread-safe and never inspect Bukkit state. [connection] is the connection
 * identity, held weakly by the provider, so all plugin sources share one viewer
 * budget without retaining players. Bytes are encoded protocol payload bytes,
 * before compression, framing, encryption and TCP overhead (not wire bytes).
 *
 * A deferred transaction must not be written or acknowledged as delivered.
 * Its owner retains only its latest desired state and retries on the connection
 * event loop. Cleanup bypasses rate budgets, but must wait for a writable channel.
 */
interface ArcVisualPacketBudget {
    fun acquire(
        source: String,
        connection: Any,
        bytes: Int,
        packets: Int,
        cleanup: Boolean,
        writable: Boolean,
    ): VisualPacketAdmission

    /** Records only payloads handed to the transport, never an admission attempt. */
    fun recordSent(source: String, bytes: Int, packets: Int, cleanup: Boolean)
}

enum class VisualPacketAdmission {
    ALLOWED,
    CHANNEL_BACKPRESSURE,
    VIEWER_RATE,
    SERVER_RATE,
    CLOSED,
}
