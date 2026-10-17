package ru.arc.paper.api

import java.util.UUID

/**
 * Optional canonical AFK status, registered by the plugin that owns that policy.
 *
 * Consumers resolve this service on the Paper main thread. [isAfk] must be a
 * bounded, in-memory read: no network, database, placeholder evaluation or idle
 * timeout inference. An absent provider means that no AFK pause is requested.
 * Core hosts this shared API class; consumers keep it compile-only.
 */
fun interface ArcAfkProvider {
    fun isAfk(playerId: UUID): Boolean
}
