package ru.arc.paper.nameplate

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketDisplayViewer
import ru.arc.paper.display.PacketDisplayAttachment
import ru.arc.paper.display.PacketDisplayView
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.display.RecordingPacketBackend
import ru.arc.paper.display.TestPacketEventsApi
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.logging.Logger

class PacketPaperNameplateDisplayFactoryTest : FreeSpec({
    "matches native styling and keeps each plate private until explicitly shown" {
        MockBukkitTestRuntime.open().use { paper ->
            val target = paper.addPlayer("Target")
            val viewer = paper.addPlayer("Viewer")
            val other = paper.addPlayer("Other")
            val options = PaperNameplateOptions(
                lineWidth = 160,
                viewRange = 0.75F,
                scale = 0.8F,
                verticalOffset = 0.25F,
                shadowed = false,
                backgroundColor = Color.fromARGB(96, 10, 20, 30),
                brightness = Display.Brightness(15, 15),
            )
            val fixture = PacketNameplateFixture(options)
            val content = Component.text("Ready")
            val nameplate = fixture.factory.create(target, content)
            val display = fixture.display

            nameplate.targetId shouldBe target.uniqueId
            nameplate.isAttachedTo(target) shouldBe true
            display.attachmentPlayer shouldBe target
            display.text() shouldBe content
            display.billboard shouldBe Display.Billboard.CENTER
            display.alignment shouldBe TextDisplay.TextAlignment.CENTER
            display.lineWidth shouldBe options.lineWidth
            display.viewRange shouldBe options.viewRange
            display.transformation.translation shouldBe Vector3f(0.0F, 0.25F, 0.0F)
            display.transformation.scale shouldBe Vector3f(0.8F, 0.8F, 0.8F)
            display.transformation.leftRotation shouldBe Quaternionf()
            display.transformation.rightRotation shouldBe Quaternionf()
            display.isShadowed shouldBe false
            display.brightness shouldBe options.brightness
            display.isSeeThrough shouldBe false
            display.isDefaultBackground shouldBe false
            display.backgroundColor shouldBe options.backgroundColor
            display.isVisibleByDefault shouldBe false
            display.pauseWhenNotObserved shouldBe true

            val frame = requireNotNull(display.frame(nameplateViewer(target)))
            frame.metadata.brightness shouldBe 0xF000F0
            frame.attachmentVehicleId shouldBe target.entityId
            display.visibleTo(nameplateViewer(target), frame) shouldBe false
            display.visibleTo(nameplateViewer(viewer), frame) shouldBe false
            nameplate.show(viewer)
            display.visibleTo(nameplateViewer(viewer), frame) shouldBe true
            display.visibleTo(nameplateViewer(other), frame) shouldBe false
            nameplate.hide(viewer)
            display.visibleTo(nameplateViewer(viewer), frame) shouldBe false
            nameplate.show(viewer)
            display.visibleTo(nameplateViewer(viewer), frame) shouldBe true

            val changed = Component.text("Fighting")
            nameplate.update(changed)
            display.text() shouldBe changed
            nameplate.close()
            display.visibleTo(nameplateViewer(viewer), frame) shouldBe false
            fixture.owner.closed shouldBe false
            verify(exactly = 0) { fixture.owner.close(); fixture.owner.closeAsync() }
        }
    }

    "native name-tag factory anchors above the current head before local row placement" {
        MockBukkitTestRuntime.open().use { paper ->
            val target = paper.addPlayer("Target")
            val options = PaperNameplateOptions(verticalOffset = -0.25F)
            val fixture = PacketNameplateFixture(options)
            val factory = PacketPaperNameplateDisplayFactory.atNativeNameTag(fixture.owner, options)
            val nameplate = factory.create(target, Component.text("Status"))
            val display = fixture.display
            val targetSnapshot = nameplateViewer(target)
            val frame = requireNotNull(display.frame(targetSnapshot))

            nameplate.isAttachedTo(target) shouldBe true
            display.billboard shouldBe Display.Billboard.CENTER
            display.teleportDuration shouldBe 1
            display.pauseWhenNotObserved shouldBe false
            display.transformation.translation shouldBe Vector3f(0.0F, -0.25F, 0.0F)
            display.brightness shouldBe null
            frame.metadata.brightness shouldBe -1
            frame.x shouldBe targetSnapshot.x
            frame.y shouldBe targetSnapshot.y + targetSnapshot.height + 0.5
            frame.z shouldBe targetSnapshot.z
            frame.attachmentVehicleId shouldBe null
            frame.metadata.transform.translation.y shouldBe -0.25F
            frame.metadata.teleportDuration shouldBe 1
            frame.pauseWhenNotObserved shouldBe false

            val crouching = requireNotNull(display.frame(targetSnapshot.copy(height = 1.5)))
            crouching.y shouldBe targetSnapshot.y + 2.0
            crouching.entityId shouldBe frame.entityId
            crouching.uuid shouldBe frame.uuid
            nameplate.close()
            display.attachmentPlayer shouldBe null
            verify(exactly = 0) { fixture.owner.close(); fixture.owner.closeAsync() }
        }
    }

    "attached identity mounts once and follows running turning falling and stance without world teleports" {
        val previousApi = PacketEvents.getAPI()
        PacketEvents.setAPI(TestPacketEventsApi())
        try {
            MockBukkitTestRuntime.open().use { paper ->
                val target = paper.addPlayer("Target")
                val options = PaperNameplateOptions(verticalOffset = 0.3F)
                val fixture = PacketNameplateFixture(options)
                val nameplate = PacketPaperNameplateDisplayFactory.attachedIdentity(fixture.owner, options)
                    .create(target, Component.text("Rank\nClan\nTarget\n20 / 20"))
                val display = fixture.display
                val initial = nameplateViewer(target)
                val channel = Any()
                val backend = RecordingPacketBackend()
                val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
                val afkView = PacketDisplayView(0.0, 64.0, 0.0, 0.0, 0.0, -1.0, afk = true)
                fun send(snapshot: PacketDisplayViewer, reset: Boolean = false) {
                    val frame = requireNotNull(display.frame(snapshot))
                    val vehicle = requireNotNull(frame.attachmentVehicleId)
                    frame.entityId shouldBe display.entityId
                    frame.uuid shouldBe display.uniqueId
                    frame.y shouldBe snapshot.y + snapshot.height
                    frame.metadata.transform.translation.y shouldBe 0.3F
                    frame.metadata.teleportDuration shouldBe 0
                    frame.pauseWhenNotObserved shouldBe false
                    connection.submitViewAsync(listOf(frame), afkView, resetAll = reset,
                        desiredPassengers = mapOf(vehicle to listOf(frame.entityId)),
                        nativePassengerSnapshots = mapOf(vehicle to listOf(11)), liveVehicleIds = setOf(vehicle))
                    backend.runAll()
                }

                nameplate.isAttachedTo(target) shouldBe true
                display.billboard shouldBe Display.Billboard.CENTER
                send(initial)
                backend.writes.map { it::class.java.simpleName } shouldBe listOf(
                    "WrapperPlayServerSpawnEntity", "WrapperPlayServerEntityMetadata", "WrapperPlayServerSetPassengers",
                )
                backend.writes.filterIsInstance<WrapperPlayServerSetPassengers>().single().passengers.toList() shouldBe
                    listOf(11, display.entityId)
                backend.clear()

                val poses = listOf(
                    initial.copy(x = initial.x + 3.0),
                    initial.copy(x = initial.x + 3.0, z = initial.z + 2.0),
                    initial.copy(y = initial.y - 2.0),
                    initial.copy(height = 1.5),
                    initial.copy(height = 0.6),
                    initial.copy(x = initial.x + 100.0, y = initial.y + 10.0),
                )
                poses.forEach { send(it) }
                backend.writes shouldBe emptyList()
                nameplate.update(Component.text("Rank\nClan\nTarget\n17 / 20"))
                send(poses.last())
                backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().size shouldBe 1
                backend.writes.none { it is WrapperPlayServerEntityTeleport } shouldBe true
                backend.clear()

                // Client reset replay removes the old link before retiring/recreating its entity.
                send(initial, reset = true)
                backend.writes.map { it::class.java.simpleName } shouldBe listOf(
                    "WrapperPlayServerSetPassengers", "WrapperPlayServerDestroyEntities",
                    "WrapperPlayServerSpawnEntity", "WrapperPlayServerEntityMetadata", "WrapperPlayServerSetPassengers",
                )
                backend.writes.filterIsInstance<WrapperPlayServerSetPassengers>().map { it.passengers.toList() } shouldBe
                    listOf(listOf(11), listOf(11, display.entityId))
                backend.clear()

                target.teleport(Location(paper.server.addSimpleWorld("other"), 0.0, 64.0, 0.0))
                nameplate.isAttachedTo(target) shouldBe false
                nameplate.close()
                val closed = connection.closeAsync()
                backend.runAll()
                closed.join()
                backend.writes.filterIsInstance<WrapperPlayServerSetPassengers>().single().passengers.toList() shouldBe listOf(11)
                backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single().entityIds.toList() shouldBe listOf(display.entityId)
                verify(exactly = 0) { fixture.owner.close(); fixture.owner.closeAsync() }
                java.lang.ref.Reference.reachabilityFence(channel)
            }
        } finally {
            PacketEvents.setAPI(previousApi)
        }
    }

    "invalid carriers and world or entity replacement require recreation while close is handle-only" {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addPlayer("WorldOwner").world
            val id = UUID.randomUUID()
            val target = mockk<Player>()
            every { target.uniqueId } returns id
            every { target.world } returns world
            every { target.location } returns Location(world, 0.0, 64.0, 0.0)
            every { target.entityId } returns 10
            every { target.isValid } returns true
            every { target.isDead } returns false
            val fixture = PacketNameplateFixture(PaperNameplateOptions())
            val nameplate = fixture.factory.create(target, Component.empty())

            nameplate.isAttachedTo(target) shouldBe true
            every { target.isValid } returns false
            nameplate.isAttachedTo(target) shouldBe false
            every { target.isValid } returns true
            every { target.isDead } returns true
            nameplate.isAttachedTo(target) shouldBe false
            every { target.isDead } returns false
            every { target.entityId } returns 11
            nameplate.isAttachedTo(target) shouldBe false
            every { target.entityId } returns 10
            val otherWorld = paper.server.addSimpleWorld("other")
            every { target.world } returns otherWorld
            nameplate.isAttachedTo(target) shouldBe false
            every { target.world } returns world
            nameplate.isAttachedTo(target) shouldBe true

            val replacement = mockk<Player>()
            every { replacement.uniqueId } returns id
            every { replacement.world } returns world
            every { replacement.entityId } returns 10
            every { replacement.isValid } returns true
            every { replacement.isDead } returns false
            nameplate.isAttachedTo(replacement) shouldBe false

            fixture.display.detach()
            nameplate.isAttachedTo(target) shouldBe false
            fixture.display.attachTo(target)
            nameplate.isAttachedTo(target) shouldBe true
            every { fixture.owner.closed } returns true
            nameplate.isAttachedTo(target) shouldBe false
            every { fixture.owner.closed } returns false
            nameplate.close()
            nameplate.close()
            nameplate.isAttachedTo(target) shouldBe false
            fixture.display.isValid shouldBe false
            verify(exactly = 1) { fixture.owner.remove(fixture.display) }
            verify(exactly = 0) { fixture.owner.close(); fixture.owner.closeAsync() }
        }
    }

    "public constructor uses explicit host types without Kotlin default argument markers" {
        PacketPaperNameplateDisplayFactory::class.java.constructors.map { it.parameterTypes.toList() } shouldBe
            listOf(listOf(PaperPacketDisplays::class.java, PaperNameplateOptions::class.java))
        for (name in listOf("atNativeNameTag", "attachedIdentity")) {
            val factory = PacketPaperNameplateDisplayFactory::class.java.getDeclaredMethod(
                name, PaperPacketDisplays::class.java, PaperNameplateOptions::class.java,
            )
            java.lang.reflect.Modifier.isStatic(factory.modifiers) shouldBe true
            factory.returnType shouldBe PacketPaperNameplateDisplayFactory::class.java
        }
    }
})

private class PacketNameplateFixture(options: PaperNameplateOptions) {
    val owner = mockk<PaperPacketDisplays>(relaxed = true)
    lateinit var display: PacketTextDisplay
    val factory = PacketPaperNameplateDisplayFactory(owner, options)

    init {
        every { owner.closed } returns false
        every { owner.spawnText(any(), any()) } answers {
            PacketTextDisplay(owner, 100, firstArg(), secondArg()).also { display = it }
        }
    }
}

private fun nameplateViewer(player: Player): PacketDisplayViewer = player.location.let { location ->
    PacketDisplayViewer(
        player = player,
        id = player.uniqueId,
        worldId = player.world.uid,
        entityId = player.entityId,
        trackedBy = emptySet(),
        x = location.x,
        y = location.y,
        z = location.z,
        sentChunks = emptySet(),
        height = 1.8,
    )
}
