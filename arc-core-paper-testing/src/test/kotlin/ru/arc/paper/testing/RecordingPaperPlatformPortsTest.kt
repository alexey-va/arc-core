package ru.arc.paper.testing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
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

    "nameplate display factory records content visibility attachment and cleanup" {
        MockBukkitTestRuntime.open().use { runtime ->
            val target = runtime.addPlayer("Target")
            val viewer = runtime.addPlayer("Viewer")
            val factory = RecordingPaperNameplateDisplayFactory()
            val display = factory.create(target, Component.text("20 ❤"))

            display.show(viewer)
            display.update(Component.text("19 ❤"))
            display.isAttachedTo(target) shouldBe true

            val record = factory.latest(target.uniqueId)!!
            record.contents shouldBe listOf(Component.text("20 ❤"), Component.text("19 ❤"))
            record.shownTo shouldBe setOf(viewer.uniqueId)

            display.hide(viewer)
            record.shownTo shouldBe emptySet()
            display.close()
            record.closed shouldBe true
            display.isAttachedTo(target) shouldBe false
        }
    }
})
