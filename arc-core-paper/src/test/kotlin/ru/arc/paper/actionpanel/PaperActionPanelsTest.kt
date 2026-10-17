package ru.arc.paper.actionpanel

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.text.Component
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PacketDisplayAudienceSource
import ru.arc.paper.display.PacketDisplayConnection
import ru.arc.paper.display.PacketDisplayContent
import ru.arc.paper.display.PacketDisplayFrame
import ru.arc.paper.display.PacketDisplayTransport
import ru.arc.paper.display.PacketDisplayViewer
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.function.Consumer

class PaperActionPanelsTest : FreeSpec({
    "head turns and crouching preserve placement and IDs while body movement interpolates" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper).use { f ->
            f.refresh()
            val first = f.frames().associateBy { it.entityId }
            f.eye.yaw = 50f
            f.eye.y -= 0.3
            f.refresh()
            f.frames().associate { it.entityId to Triple(it.x, it.y, it.z) } shouldBe
                first.mapValues { Triple(it.value.x, it.value.y, it.value.z) }
            f.body.x += 0.2
            f.eye.x += 0.2
            f.refresh()
            f.frames().map { it.entityId }.toSet() shouldBe first.keys
            f.frames().all { it.metadata.teleportDuration == 2 } shouldBe true
        } }
    }

    "fresh context and native entity IDs fence old clicks while text handles remain continuous" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper, ActionPanelInputProfile.ENTITY_BOTH).use { f ->
            f.refresh()
            val old = f.interactions.single()
            val textIds = f.frames().map { it.entityId }
            f.current = f.frame("replacement")
            f.refresh()
            val fresh = f.interactions.last()
            fresh.uniqueId shouldNotBe old.uniqueId
            old.isValid shouldBe false
            f.frames().map { it.entityId } shouldBe textIds
            f.panels.onClick(PlayerInteractEntityEvent(f.player, old, EquipmentSlot.HAND))
            f.activated shouldBe emptyList()
            f.panels.onClick(PlayerInteractEntityEvent(f.player, fresh, EquipmentSlot.HAND))
            f.activated shouldBe listOf("replacement" to "confirm")
        } }
    }

    "fixed authored controls retain their identities through a fully hidden scene" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper, ActionPanelInputProfile.ENTITY_RIGHT).use { f ->
            val original = checkNotNull(f.current)
            val button = original.layout.buttons.single()
            val fixed = ActionPanelPlacement.Fixed(Location(f.world, 0.0, 65.62, 2.4, 180f, 0f))
            fun frame(visible: Boolean) = ActionPanelFrame(original.context, fixed,
                ActionPanelLayout(listOf(ActionPanelButton(button.action, button.rect, button.text, button.style, visible, true)),
                    original.layout.labels), true, original.options, null)
            f.current = frame(true)
            f.refresh()
            val textId = f.frames().single().entityId
            val control = f.interactions.single()

            f.current = frame(false)
            f.refresh()
            f.panels.contains(f.player.uniqueId) shouldBe true
            f.frames() shouldBe emptyList()
            f.panels.target(f.player) shouldBe null
            f.panels.intercept(f.player) shouldBe false
            f.interactions.single().uniqueId shouldBe control.uniqueId
            control.isValid shouldBe true

            f.current = frame(true)
            f.refresh()
            f.frames().single().entityId shouldBe textId
            f.interactions.single().uniqueId shouldBe control.uniqueId

            val hidden = frame(false)
            f.current = ActionPanelFrame(original.context, original.placement, hidden.layout, true, original.options, null)
            f.refresh()
            f.panels.contains(f.player.uniqueId) shouldBe false
            control.isValid shouldBe false
        } }
    }

    "unrefreshed context is consumed without dispatch and duplicate gestures survive context replacement" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper).use { f ->
            f.refresh()
            f.panels.intercept(f.player) shouldBe true
            f.activated shouldBe listOf("first" to "confirm")
            f.current = f.frame("second")
            f.panels.intercept(f.player) shouldBe true
            f.panels.intercept(f.player) shouldBe true
            f.activated shouldBe listOf("first" to "confirm")
            f.now += 250_000_000L
            f.panels.intercept(f.player) shouldBe true
            f.activated.last() shouldBe ("second" to "confirm")
            f.eye.yaw = 90f
            f.panels.intercept(f.player) shouldBe false
        } }
    }

    "cancelled right-click profile and offhand events preserve domain dispatch policy" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper, ActionPanelInputProfile.ENTITY_RIGHT).use { f ->
            f.refresh()
            val hitbox = f.interactions.single()
            val cancelled = PlayerInteractEntityEvent(f.player, hitbox, EquipmentSlot.HAND).apply { isCancelled = true }
            f.panels.onClick(cancelled)
            f.panels.onClick(PlayerInteractEntityEvent(f.player, hitbox, EquipmentSlot.OFF_HAND))
            f.activated shouldBe emptyList()
            f.current = f.frame("first", ActionPanelInputProfile.ENTITY_BOTH)
            f.refresh()
            f.panels.onClick(cancelled)
            f.activated shouldBe listOf("first" to "confirm")
        } }
    }

    "native left attack accepts no-attack form while external cancellation and foreign owner stay denied" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper, ActionPanelInputProfile.ENTITY_BOTH).use { f ->
            f.refresh()
            val control = f.interactions.single()
            val foreign = mockk<Player>(relaxed = true) { every { uniqueId } returns UUID.randomUUID() }
            f.panels.onAttack(PrePlayerAttackEntityEvent(foreign, control, false))
            val noAttack = PrePlayerAttackEntityEvent(f.player, control, false)
            f.panels.onAttack(noAttack)
            noAttack.isCancelled shouldBe true
            f.activated shouldBe listOf("first" to "confirm")
            f.now += 300_000_000L
            f.panels.onAttack(PrePlayerAttackEntityEvent(f.player, control, true).apply { isCancelled = true })
            f.activated.size shouldBe 1
        } }
    }

    "no-fit retry is bounded and body or context changes retry immediately before obstacle recovery" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper).use { f ->
            var checks = 0
            var available = false
            every { f.worldSpace.safe(any(), any(), any(), any(), any(), any(), any(), any()) } answers { checks++; available }
            f.refresh()
            f.panels.contains(f.player.uniqueId) shouldBe false
            val firstChecks = checks
            repeat(3) { f.refresh() }
            checks shouldBe firstChecks
            f.body.x += 0.01
            f.eye.x += 0.01
            f.refresh()
            (checks > firstChecks) shouldBe true
            val movedChecks = checks
            f.current = f.frame("next")
            f.refresh()
            (checks > movedChecks) shouldBe true
            available = true
            f.now += 250_000_000L
            f.refresh()
            f.panels.contains(f.player.uniqueId) shouldBe true
            f.frames().size shouldBe 1
        } }
    }

    "renderer failures have a five-second retry and explicit lifecycle clear resets it" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper).use { f ->
            var checks = 0
            every { f.worldSpace.safe(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                checks++
                throw IllegalStateException("Test world query failure")
            }
            f.refresh()
            f.refresh()
            checks shouldBe 1
            f.now += 5_000_000_000L
            f.refresh()
            checks shouldBe 2
            every { f.worldSpace.safe(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            f.panels.clear(f.player.uniqueId)
            f.refresh()
            f.panels.contains(f.player.uniqueId) shouldBe true
        } }
    }

    "blocked swept motion snaps but keeps the same packet label" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper).use { f ->
            f.refresh()
            val first = f.frames().single()
            every { f.worldSpace.clearSweep(any(), any()) } returns false
            f.body.y += 0.2
            f.eye.y += 0.2
            f.refresh()
            val moved = f.frames().single()
            moved.entityId shouldBe first.entityId
            moved.metadata.teleportDuration shouldBe 0
            moved.y shouldNotBe first.y
        } }
    }

    "input validates the full footprint from actual eyes and fails closed on occlusion" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper).use { f ->
            f.refresh()
            val capturedEyes = mutableListOf<ActionPanelPoint>()
            every { f.worldSpace.safe(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                capturedEyes += secondArg<ActionPanelPoint>()
                false
            }
            f.eye.y -= 0.3
            f.panels.target(f.player) shouldBe null
            capturedEyes.last().y shouldBe f.eye.y
            f.activated shouldBe emptyList()
            every { f.worldSpace.safe(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            every { f.worldSpace.rayClear(any(), any(), any(), any()) } returns false
            f.panels.intercept(f.player) shouldBe false
        } }
    }

    "guide suppression restores the old context before activating the replacement and clears once" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper).use { f ->
            f.refresh()
            f.suppression shouldBe listOf("first" to true)
            f.current = f.frame("second")
            f.refresh()
            f.suppression shouldBe listOf("first" to true, "first" to false, "second" to true)
            f.panels.clear(f.player.uniqueId)
            f.panels.clear(f.player.uniqueId)
            f.suppression.last() shouldBe ("second" to false)
            f.suppression.size shouldBe 4
        } }
    }

    "closing a borrowed panel releases its own scene and leaves the display owner usable" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper, ActionPanelInputProfile.ENTITY_BOTH).use { f ->
            f.refresh()
            f.panels.close()
            f.panels.close()
            f.frames() shouldBe emptyList()
            f.interactions.all { !it.isValid } shouldBe true
            f.transport.closed shouldBe false
            f.displays.spawnText(Location(f.world, 0.0, 65.0, 1.0), Component.text("other scene"))
            f.frames().single().metadata.content shouldBe PacketDisplayContent.Text(Component.text("other scene"))
        } }
    }

    "audience revocation and disabled nearest controls cannot be activated or hovered" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper).use { f ->
            var visible = true
            val group = f.displays.createAudienceGroup { visible }
            val original = checkNotNull(f.current)
            f.current = ActionPanelFrame(original.context, original.placement, original.layout, true, original.options, group)
            f.refresh()
            visible = false
            f.refresh()
            f.panels.isLookingAt(f.player.uniqueId) shouldBe false
            f.suppression.last() shouldBe ("first" to false)
            f.panels.target(f.player) shouldBe null
            f.panels.intercept(f.player) shouldBe false
            visible = true
            val button = original.layout.buttons.single()
            val disabled = ActionPanelButton(button.action, button.rect, button.text, button.style, true, false)
            f.current = ActionPanelFrame(original.context, original.placement,
                ActionPanelLayout(listOf(disabled), original.layout.labels), true, original.options, group)
            f.refresh()
            f.panels.target(f.player) shouldBe null
            f.panels.intercept(f.player) shouldBe true
            f.activated shouldBe emptyList()
            f.frames().none { it.metadata.glowing } shouldBe true
        } }
    }
})

private class PanelFixture(paper: MockBukkitTestRuntime, val profile: ActionPanelInputProfile = ActionPanelInputProfile.EXTERNAL_RAY) : AutoCloseable {
    val plugin = paper.createSimplePlugin("ActionPanelTest")
    val world = mockk<World>()
    val player = mockk<Player>(relaxed = true)
    val body = Location(world, 0.0, 64.0, 0.0)
    val eye = Location(world, 0.0, 65.62, 0.0)
    val worldSpace = mockk<ActionPanelWorldSpace>()
    val interactions = mutableListOf<Interaction>()
    val activated = mutableListOf<Pair<String, String>>()
    val suppression = mutableListOf<Pair<String, Boolean>>()
    val transport = PanelTransport()
    val displays: PaperPacketDisplays
    val panels: PaperActionPanels<String, String>
    var now = 1_000_000_000L
    var current: ActionPanelFrame<String, String>?

    init {
        val worldId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        every { world.uid } returns worldId
        every { world.name } returns "action-panel-world"
        every { player.uniqueId } returns playerId
        every { player.world } returns world
        every { player.isOnline } returns true
        every { player.isDead } returns false
        every { player.location } answers { body.clone() }
        every { player.eyeLocation } answers { eye.clone() }
        every { worldSpace.safe(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
        every { worldSpace.rayClear(any(), any(), any(), any()) } returns true
        every { worldSpace.clearSweep(any(), any()) } returns true
        every { world.spawn(any<Location>(), eq(Interaction::class.java), any<Consumer<Interaction>>()) } answers {
            var location = firstArg<Location>().clone()
            var valid = true
            val interaction = mockk<Interaction>(relaxed = true)
            val id = UUID.randomUUID()
            every { interaction.uniqueId } returns id
            every { interaction.world } returns world
            every { interaction.location } answers { location.clone() }
            every { interaction.isValid } answers { valid }
            every { interaction.teleport(any<Location>()) } answers { location = firstArg<Location>().clone(); true }
            every { interaction.remove() } answers { valid = false }
            thirdArg<Consumer<Interaction>>().accept(interaction)
            interactions += interaction
            interaction
        }
        displays = PaperPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), object : PacketDisplayAudienceSource {
            override fun checkThread() = Unit
            override fun capture() = listOf(PacketDisplayViewer(player, playerId, worldId, 1, emptySet(),
                body.x, body.y, body.z, (-1..1).flatMap { x -> (-1..1).map { z -> Chunk.getChunkKey(x, z) } }.toSet()))
        })
        current = frame("first")
        panels = PaperActionPanels(plugin, displays, object : ActionPanelController<String, String>() {
            override fun frame(player: Player) = current
            override fun activate(player: Player, context: String, action: String) { activated += context to action }
            override fun guideSuppressionChanged(playerId: UUID, context: String, suppressed: Boolean) { suppression += context to suppressed }
        }, false, worldSpace) { now }
    }

    fun frame(context: String, input: ActionPanelInputProfile = profile): ActionPanelFrame<String, String> {
        val rect = ActionPanelRect(ActionPanelPoint(0.0, 0.0, 0.0), 1.5, 0.3)
        val button = ActionPanelButton("confirm", rect, Component.text("Confirm"), ActionPanelTextStyle.button(0.8f, 1.5f, 0.3f))
        return ActionPanelFrame(context, ActionPanelPlacement.FollowTarget(world.uid, ActionPanelPoint(0.0, 64.0, 10.0)),
            ActionPanelLayout(listOf(button), emptyList()), true, ActionPanelOptions(input, 4.0, true), null)
    }
    fun refresh() { panels.refresh(player) }
    fun frames(): List<PacketDisplayFrame> { displays.refresh(); return transport.connection.frames }
    override fun close() { panels.close(); displays.close() }
}

private class PanelTransport : PacketDisplayTransport {
    var closed = false
    var next = 100
    val connection = PanelConnection()
    override fun nextEntityId() = next++
    override fun connection(player: Player) = connection
    override fun forget(player: Player) = Unit
    override fun blockStateId(block: BlockData): Int = error("Text-only test")
    override fun itemSnapshot(item: ItemStack): com.github.retrooper.packetevents.protocol.item.ItemStack = error("Text-only test")
    override fun close() { closed = true }
}

private class PanelConnection : PacketDisplayConnection {
    override val identity = Any()
    var frames = emptyList<PacketDisplayFrame>()
    override fun submit(desired: List<PacketDisplayFrame>, resetChunks: Set<Long>, resetAll: Boolean,
        desiredPassengers: Map<Int, List<Int>>, nativePassengerSnapshots: Map<Int, List<Int>>, liveVehicleIds: Set<Int>?) {
        frames = desired
    }
}
