package ru.arc.paper.protection

import org.bukkit.Bukkit
import org.bukkit.block.Block
import ru.arc.paper.api.ArcBlockProtectionService
import java.util.UUID

/**
 * Checks every currently registered feature protection immediately before a
 * direct world mutation. Service priority cannot override a denial. No providers
 * means no restriction; off-thread calls, disabled registered providers and
 * provider failures deny access. This helper never loads chunks or caches services.
 */
object PaperBlockProtection {
    @JvmStatic
    fun isProtected(worldId: UUID, x: Int, y: Int, z: Int): Boolean =
        !allPermit { !it.isProtected(worldId, x, y, z) }

    @JvmStatic
    fun isProtected(block: Block): Boolean {
        // Do not dereference a Bukkit world or block from an asynchronous caller.
        if (!Bukkit.isPrimaryThread()) return true
        return isProtected(block.world.uid, block.x, block.y, block.z)
    }

    @JvmStatic
    fun permitsSchematicMutation(owner: String, worldId: UUID, x: Int, y: Int, z: Int): Boolean =
        allPermit { it.permitsSchematicMutation(owner, worldId, x, y, z) }

    private inline fun allPermit(permits: (ArcBlockProtectionService) -> Boolean): Boolean {
        if (!Bukkit.isPrimaryThread()) return false
        return try {
            Bukkit.getServicesManager().getRegistrations(ArcBlockProtectionService::class.java)
                .all { it.plugin.isEnabled && permits(it.provider) }
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
    }
}
