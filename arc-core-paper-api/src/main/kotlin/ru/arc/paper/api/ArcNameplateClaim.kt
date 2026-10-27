package ru.arc.paper.api

import java.util.UUID

/**
 * A mode's exclusive ownership of a player's overhead display, including hidden phases.
 * Registered through Bukkit ServicesManager; queried on the primary thread only.
 * Ordinary nameplates yield while any registered claim owns the target.
 * Providers must answer from cached state without storage, and unregister on shutdown.
 */
fun interface ArcNameplateClaim {
    fun owns(targetId: UUID): Boolean
}
