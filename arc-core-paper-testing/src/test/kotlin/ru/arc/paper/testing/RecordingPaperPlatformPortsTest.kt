package ru.arc.paper.testing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.event.player.PlayerTeleportEvent

class RecordingPaperPlatformPortsTest : FreeSpec({
    "player-data persistence records and reaches the patched native boundary" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("Persistent")
            val persistence = RecordingPaperPlayerDataPersistence()

            persistence.persist(player)

            persistence.playerIds() shouldBe listOf(player.uniqueId)
            persistence.count(player) shouldBe 1
            runtime.playerDataSaveCount(player) shouldBe 1
        }
    }

    "teleport executor snapshots the request and reaches the entity" {
        MockBukkitTestRuntime.open().use { runtime ->
            val world = runtime.addSimpleWorld("destination")
            val player = runtime.addPlayer("Traveller")
            val destination = Location(world, 12.5, 80.0, -4.5, 90f, 10f)
            val teleports = RecordingPaperTeleportExecutor()

            teleports.teleportAsync(player, destination).join() shouldBe true

            teleports.observations() shouldBe listOf(
                PaperTeleportObservation(
                    entityId = player.uniqueId,
                    destination = destination,
                    cause = PlayerTeleportEvent.TeleportCause.PLUGIN,
                    flags = emptyList(),
                ),
            )
            player.location shouldBe destination
        }
    }
})
