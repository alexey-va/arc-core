package ru.arc.paper.teleport

import io.papermc.paper.entity.TeleportFlag
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.concurrent.CompletableFuture

/**
 * Exact Paper boundary for an asynchronous local-server teleport.
 *
 * Feature code still owns destination selection, authorization, lifecycle
 * tokens and completion handling. This port only invokes the pinned Paper API.
 */
interface PaperTeleportExecutor {
    fun teleportAsync(
        entity: Entity,
        destination: Location,
        cause: PlayerTeleportEvent.TeleportCause = PlayerTeleportEvent.TeleportCause.PLUGIN,
        vararg flags: TeleportFlag,
    ): CompletableFuture<Boolean>
}

/** Production adapter bound directly to Paper's chunk-loading teleport API. */
object NativePaperTeleportExecutor : PaperTeleportExecutor {
    override fun teleportAsync(
        entity: Entity,
        destination: Location,
        cause: PlayerTeleportEvent.TeleportCause,
        vararg flags: TeleportFlag,
    ): CompletableFuture<Boolean> = entity.teleportAsync(destination, cause, *flags)
}
