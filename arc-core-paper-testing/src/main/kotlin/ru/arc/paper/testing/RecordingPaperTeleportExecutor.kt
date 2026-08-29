package ru.arc.paper.testing

import io.papermc.paper.entity.TeleportFlag
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.event.player.PlayerTeleportEvent
import ru.arc.paper.teleport.NativePaperTeleportExecutor
import ru.arc.paper.teleport.PaperTeleportExecutor
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

data class PaperTeleportObservation(
    val entityId: UUID,
    val destination: Location,
    val cause: PlayerTeleportEvent.TeleportCause,
    val flags: List<TeleportFlag>,
)

/** Records local teleports while delegating to the native Paper API by default. */
class RecordingPaperTeleportExecutor(
    private val delegate: PaperTeleportExecutor = NativePaperTeleportExecutor,
) : PaperTeleportExecutor {
    private val recorded = CopyOnWriteArrayList<PaperTeleportObservation>()

    fun observations(): List<PaperTeleportObservation> = recorded.map { it.copy(destination = it.destination.clone()) }

    fun clear() = recorded.clear()

    override fun teleportAsync(
        entity: Entity,
        destination: Location,
        cause: PlayerTeleportEvent.TeleportCause,
        vararg flags: TeleportFlag,
    ): CompletableFuture<Boolean> {
        recorded += PaperTeleportObservation(entity.uniqueId, destination.clone(), cause, flags.toList())
        return delegate.teleportAsync(entity, destination, cause, *flags)
    }
}
