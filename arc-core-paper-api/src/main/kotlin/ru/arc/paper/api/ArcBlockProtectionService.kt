package ru.arc.paper.api

import java.util.UUID

/**
 * A feature's protected block geometry, registered through Bukkit ServicesManager.
 *
 * Calls run on Paper's primary thread. Implementations must use bounded in-memory
 * checks, without I/O or chunk loading, and fail closed while their state is not
 * ready. The registering plugin owns refresh and unregistration. Every provider
 * participates regardless of service priority; one provider cannot grant access
 * on another provider's behalf.
 */
fun interface ArcBlockProtectionService {
    fun isProtected(worldId: UUID, x: Int, y: Int, z: Int): Boolean

    /**
     * Allows a feature to authorize its own validated schematic work. [owner] is
     * the trusted plugin-supplied library owner, not a player permission or a
     * security boundary against other installed plugins. The default respects
     * the same protected geometry as every other automated editor.
     */
    fun permitsSchematicMutation(owner: String, worldId: UUID, x: Int, y: Int, z: Int): Boolean =
        !isProtected(worldId, x, y, z)
}
