package ru.arc.paper.nameplate

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
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

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
            display.isSeeThrough shouldBe false
            display.isDefaultBackground shouldBe false
            display.backgroundColor shouldBe options.backgroundColor
            display.isVisibleByDefault shouldBe false

            val frame = requireNotNull(display.frame(nameplateViewer(target)))
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
