package ru.arc.paper.actionpanel

import ru.arc.paper.display.testPacketDisplays
import ru.arc.paper.display.refreshFully
import ru.arc.paper.display.TestPacketDisplayAudience

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.text.Component
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
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
    "text rotation keeps the rectangle center and input target while old constructors stay unrotated" {
        ActionPanelTextStyle(0.9f, 40, 0.42f, 0.32f, 0.025).rotationDegrees shouldBe 0f
        ActionPanelTextStyle(0.9f, 40, 0.42f, 0.32f, 0.025, 0, 0).rotationDegrees shouldBe 0f
        ActionPanelTextStyle::class.java.getConstructor(Float::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Double::class.javaPrimitiveType)
        ActionPanelTextStyle::class.java.getConstructor(Float::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Double::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper, ActionPanelInputProfile.ENTITY_BOTH).use { f ->
            val original = checkNotNull(f.current)
            val rect = ActionPanelRect(ActionPanelPoint(0.0, 0.0, 0.0), 0.42, 0.32)
            var entityId: Int? = null
            var nativeId: UUID? = null
            for ((angle, direction) in listOf(0f to (1.0 to 0.0), 90f to (0.0 to 1.0),
                180f to (-1.0 to 0.0), -90f to (0.0 to -1.0))) {
                val button = ActionPanelButton("confirm", rect, Component.text(">"),
                    ActionPanelTextStyle(0.9f, 40, 0.42f, 0.32f, 0.025, 0, 0, angle))
                f.current = ActionPanelFrame("rotating", original.placement, ActionPanelLayout(listOf(button), emptyList()),
                    true, original.options, null)
                f.refresh()
                val frame = f.frames().single()
                if (entityId == null) entityId = frame.entityId else frame.entityId shouldBe entityId
                val target = f.interactions.single { it.isValid }
                if (nativeId == null) nativeId = target.uniqueId else target.uniqueId shouldBe nativeId
                val transform = frame.metadata.transform
                val q = transform.leftRotation.let { Quaternionf(it.x, it.y, it.z, it.w) }
                val vector = q.transform(Vector3f(1f, 0f, 0f))
                vector.x.toDouble() shouldBe (direction.first plusOrMinus 1e-6)
                vector.y.toDouble() shouldBe (direction.second plusOrMinus 1e-6)
                val pivot = (rect.height / 2 - button.style.baselineInset).toFloat()
                val center = q.transform(Vector3f(0f, pivot, 0f)).add(transform.translation.let { Vector3f(it.x, it.y, it.z) })
                center.x.toDouble() shouldBe (0.0 plusOrMinus 1e-6)
                center.y.toDouble() shouldBe (pivot.toDouble() plusOrMinus 1e-6)
                f.aim("confirm")
                f.panels.target(f.player)?.action shouldBe "confirm"
            }
        } }
    }

    "old public constructors preserve physical depth and body-movement motion" {
        val world = UUID.randomUUID()
        ActionPanelPlacement.FollowView(world).motion shouldBe ActionPanelViewMotion.BODY_MOVEMENT
        ActionPanelPlacement.FollowView(world, ActionPanelFollowSettings()).motion shouldBe ActionPanelViewMotion.BODY_MOVEMENT
        ActionPanelPlacement.FollowView::class.java.getConstructor(UUID::class.java, ActionPanelFollowSettings::class.java)
        ActionPanelPlacement.FollowView::class.java.getConstructor(UUID::class.java)
        ActionPanelOptions().depth shouldBe ActionPanelDepth.WORLD
        ActionPanelOptions(ActionPanelInputProfile.ENTITY_BOTH, 4.0, false).depth shouldBe ActionPanelDepth.WORLD
        ActionPanelOptions(ActionPanelInputProfile.ENTITY_BOTH, 4.0, false, true).depth shouldBe ActionPanelDepth.WORLD
        ActionPanelOptions::class.java.getConstructor(ActionPanelInputProfile::class.java, Double::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    }

    "view overlay ignores a real wall before its plane but preserves actual hit bounds and native deduplication" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper, ActionPanelInputProfile.ENTITY_BOTH).use { f ->
            every { f.worldSpace.safe(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
            every { f.world.rayTraceBlocks(any(), any(), any(), any(), any(), any()) } answers {
                org.bukkit.util.RayTraceResult(f.eye.toVector().add(f.eye.direction.multiply(0.45)))
            }
            f.current = f.overlay("overlay", listOf("confirm", "cancel"))
            f.refresh()
            val before = f.poses()
            val ids = f.interactions.filter { it.isValid }.map { it.uniqueId }
            f.aim("confirm")
            f.panels.intercept(f.player) shouldBe true
            f.panels.onClick(PlayerInteractEntityEvent(f.player, f.interactions.first(), EquipmentSlot.HAND))
            f.activated shouldBe listOf("overlay" to "confirm")
            f.refresh()
            f.poses() shouldBe before
            f.interactions.filter { it.isValid }.map { it.uniqueId } shouldBe ids
            f.frames().all { ((it.metadata.content as PacketDisplayContent.Text).flags.toInt() and 2) != 0 } shouldBe true
            f.eye.pitch = -80f
            f.panels.intercept(f.player) shouldBe false
            verify(exactly = 0) { f.worldSpace.safe(any(), any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { f.worldSpace.clearSweep(any(), any()) }
            verify(exactly = 0) { f.world.rayTraceBlocks(any(), any(), any(), any(), any(), any()) }
            // WORLD retains both its native depth flag and its real-block line-of-sight gate.
            every { f.worldSpace.safe(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            f.eye.pitch = 0f
            f.current = f.frame("world")
            f.refresh()
            f.frames().all { ((it.metadata.content as PacketDisplayContent.Text).flags.toInt() and 2) == 0 } shouldBe true
            f.panels.intercept(f.player) shouldBe false
        } }
    }

    "aim-stable pages and confirmation retain their plane and moving aim without rotating native targets" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper, ActionPanelInputProfile.ENTITY_BOTH).use { f ->
            val trace = mutableListOf<String>()
            f.current = f.overlay("page-1", (1..6).map { "action-$it" }, navigation = true)
            f.refresh()
            trace += f.snapshot("page-1")
            f.aim("next")
            val anchor = f.anchor()
            val retained = f.frames().filter { (it.metadata.content as PacketDisplayContent.Text).text == Component.text("Title") }.single().entityId
            f.current = f.overlay("page-2", (7..10).map { "action-$it" }, navigation = true)
            f.refresh()
            f.anchor() shouldBe anchor
            trace += f.snapshot("page-2")
            f.frames().first { (it.metadata.content as PacketDisplayContent.Text).text == Component.text("Title") }.entityId shouldBe retained
            f.current = f.overlay("paste-plan", listOf("confirm", "cancel", "rotate-left", "rotate-right"))
            f.refresh()
            f.anchor() shouldBe anchor
            f.aim("confirm")
            val ids = f.frames().map { it.entityId }
            val nativeIds = f.interactions.filter { it.isValid }.map { it.uniqueId }
            val yaw = f.eye.yaw
            val pitch = f.eye.pitch
            trace += f.snapshot("paste-confirm-start")
            repeat(8) { step ->
                f.body.x += 0.04; f.eye.x += 0.04
                f.now += 300_000_000L
                f.refresh()
                f.eye.yaw shouldBe yaw
                f.eye.pitch shouldBe pitch
                f.anchor().yaw shouldBe anchor.yaw
                f.anchor().point.x shouldBe (anchor.point.x + (step + 1) * 0.04 plusOrMinus 1e-6)
                f.panels.target(f.player)?.action shouldBe "confirm"
                f.panels.intercept(f.player) shouldBe true
                f.frames().map { it.entityId } shouldBe ids
                f.interactions.filter { it.isValid }.map { it.uniqueId } shouldBe nativeIds
                trace += f.snapshot("paste-confirm-microstep-$step")
            }
            f.activated.size shouldBe 8
            f.eye.yaw += 180f; f.eye.pitch = 0f
            var previous = f.anchor()
            var retainedAim: String? = null
            repeat(16) { step ->
                f.refresh()
                val current = f.anchor()
                if (retainedAim != null) {
                    current shouldBe previous
                    f.panels.target(f.player)?.action shouldBe retainedAim
                }
                retainedAim = f.panels.target(f.player)?.action
                (ActionPanelGeometry.yawDelta(previous.yaw, current.yaw) <= 12.0001) shouldBe true
                (ActionPanelGeometry.distance(previous.point, current.point) <= 0.503) shouldBe true
                f.frames().map { it.entityId } shouldBe ids
                f.interactions.filter { it.isValid }.map { it.uniqueId } shouldBe nativeIds
                trace += f.snapshot("head-recenter-$step")
                previous = current
            }
            val toPanel = org.bukkit.util.Vector(previous.point.x - f.eye.x, 0.0, previous.point.z - f.eye.z)
            (f.eye.direction.normalize().dot(toPanel.normalize()) > 0.8) shouldBe true
            retainedAim shouldBe "cancel"
            val out = java.nio.file.Path.of("build/reports/action-panel-motion/after.json")
            java.nio.file.Files.createDirectories(out.parent)
            java.nio.file.Files.writeString(out, trace.joinToString(prefix = "[", postfix = "]", separator = ",\n"))
        } }
    }

    "aim-stable recenter reaches the captured direction when the ray misses all controls" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper).use { f ->
            // Status-only progress has no button that could capture the gaze during the turn.
            f.current = f.overlay("progress", emptyList())
            f.refresh()
            f.eye.yaw = 180f
            repeat(16) { f.refresh() }
            ActionPanelGeometry.yawDelta(f.anchor().yaw, 0f) shouldBe (0.0 plusOrMinus 1e-6)
            val settled = f.anchor()
            f.refresh()
            f.anchor() shouldBe settled
        } }
    }

    "overlay full bounds and actual crouching eyes remain reach limited" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper).use { f ->
            f.current = f.overlay("reach", listOf("confirm", "cancel"))
            f.refresh()
            val initial = f.anchor()
            f.eye.y -= 0.3
            f.refresh()
            f.anchor() shouldBe initial
            f.aim("confirm")
            f.panels.target(f.player)?.action shouldBe "confirm"
            f.eye.x = 10.0
            f.panels.target(f.player) shouldBe null
        } }
    }

    "reach-limited open-space placement preserves zero-tolerance following during repeated strafing" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper).use { f ->
            f.useCollisionWorld(emptyList())
            val rect = ActionPanelRect(ActionPanelPoint(0.0, 0.0, 0.0), 2.0, 1.0)
            val button = ActionPanelButton("confirm", rect, Component.text("Confirm"), ActionPanelTextStyle.button(0.8f, 2f, 1f))
            f.current = ActionPanelFrame("reach-limited", ActionPanelPlacement.FollowView(f.world.uid,
                ActionPanelFollowSettings(2.4, 0.0, 0.0, 1.25, 0.0)), ActionPanelLayout(listOf(button), emptyList()), true,
                ActionPanelOptions(ActionPanelInputProfile.EXTERNAL_RAY, 2.5, false), null)
            f.refresh()
            val initial = f.frames().single()
            initial.z shouldBe (2.2 plusOrMinus 1e-6)
            repeat(3) {
                listOf(0.05, 0.10, 0.05, 0.0, -0.05, -0.10, -0.05, 0.0).forEach { x ->
                    f.body.x = x; f.eye.x = x
                    f.refresh()
                    val current = f.frames().single()
                    current.entityId shouldBe initial.entityId
                    current.x shouldBe (initial.x + x plusOrMinus 1e-6)
                    current.z shouldBe (initial.z plusOrMinus 1e-6)
                    current.metadata.teleportDuration shouldBe 2
                }
            }
        } }
    }

    "doorway strafing holds a safe world anchor and releases obstruction without a recovery jump" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper).use { f ->
            val walls = mutableListOf(
                BoundingBox(-5.0, 64.0, 1.6, -1.65, 69.0, 3.0),
                BoundingBox(1.65, 64.0, 1.6, 5.0, 69.0, 3.0),
            )
            f.useCollisionWorld(walls)
            val layout = ActionPanelLayouts.twoColumn((1..6).map { ActionPanelAction("action-$it", Component.text("Action $it")) },
                Component.text("Title"))
            f.current = ActionPanelFrame("doorway", ActionPanelPlacement.FollowView(f.world.uid), layout, true,
                ActionPanelOptions(ActionPanelInputProfile.EXTERNAL_RAY, 4.0, false), null)
            fun pose() = f.frames().associate { it.entityId to Triple(it.x, it.y, it.z) }
            fun move(x: Double) { f.body.x = x; f.eye.x = x; f.refresh() }
            f.refresh()
            val initial = pose()
            repeat(4) {
                listOf(0.12, -0.12, 0.06, -0.06, 0.0).forEach { x ->
                    move(x)
                    pose() shouldBe initial
                }
            }

            // A new solid obstruction invalidates the retained footprint immediately.
            walls += BoundingBox(-5.0, 64.0, 1.6, 5.0, 69.0, 2.6)
            f.refresh()
            val escaped = pose()
            escaped.keys shouldBe initial.keys
            escaped shouldNotBe initial
            f.frames().all { it.z < 1.6 && it.metadata.teleportDuration == 0 } shouldBe true

            // The usual follow destination needs extra clearance before the panel can return.
            // Stay still after the forced retreat: recovery must not depend on another strafe.
            walls.clear()
            var previous = pose()
            repeat(20) {
                f.refresh()
                val current = pose()
                current.keys shouldBe initial.keys
                current.forEach { (id, at) ->
                    val before = previous.getValue(id)
                    ActionPanelGeometry.distance(ActionPanelPoint(before.first, before.second, before.third),
                        ActionPanelPoint(at.first, at.second, at.third)).let { it <= 0.09 } shouldBe true
                }
                previous = current
            }
            f.frames().all { kotlin.math.abs(it.z - 2.4) < 1e-6 } shouldBe true
            move(0.2)
            f.frames().all { it.metadata.teleportDuration == 2 } shouldBe true
            pose() shouldNotBe previous
        } }
    }

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

    listOf("current", "ray", "audit", "activate").forEach { stage ->
        "native right input contains $stage failure and preserves scene cleanup and five-second backoff" {
            MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper, ActionPanelInputProfile.ENTITY_BOTH).use { f ->
                f.refresh()
                val old = f.interactions.single()
                val failure = IllegalStateException("Native $stage failure")
                when (stage) {
                    "current" -> f.frameFailure = failure
                    "ray" -> every { f.worldSpace.rayClear(any(), any(), any(), any()) } throws failure
                    "audit" -> f.auditFailure = failure
                    "activate" -> f.activationFailure = failure
                }
                val event = PlayerInteractEntityEvent(f.player, old, EquipmentSlot.HAND)
                f.panels.onClick(event)
                event.isCancelled shouldBe true
                old.isValid shouldBe false
                f.frames() shouldBe emptyList()
                f.panels.contains(f.player.uniqueId) shouldBe false
                f.suppression.last() shouldBe ("first" to false)
                f.activated shouldBe emptyList()
                f.activationCalls shouldBe if (stage == "activate") 1 else 0

                val calls = Triple(f.frameCalls, f.auditCalls, f.activationCalls)
                f.panels.onClick(PlayerInteractEntityEvent(f.player, old, EquipmentSlot.HAND))
                f.panels.onAttack(PrePlayerAttackEntityEvent(f.player, old, false))
                Triple(f.frameCalls, f.auditCalls, f.activationCalls) shouldBe calls
                f.frameFailure = null; f.auditFailure = null; f.activationFailure = null
                every { f.worldSpace.rayClear(any(), any(), any(), any()) } returns true
                f.refresh()
                f.panels.contains(f.player.uniqueId) shouldBe false
                f.now += 4_999_999_999L
                f.refresh()
                f.panels.contains(f.player.uniqueId) shouldBe false
                f.now++
                f.refresh()
                f.panels.contains(f.player.uniqueId) shouldBe true
                f.interactions.last().uniqueId shouldNotBe old.uniqueId
            } }
        }
    }

    "native left activation failure is contained and cannot repeat through the removed target" {
        MockBukkitTestRuntime.open().use { paper -> PanelFixture(paper, ActionPanelInputProfile.ENTITY_BOTH).use { f ->
            f.refresh()
            val old = f.interactions.single()
            f.activationFailure = IllegalStateException("Native left activation failure")
            val event = PrePlayerAttackEntityEvent(f.player, old, false)
            f.panels.onAttack(event)
            event.isCancelled shouldBe true
            f.activationCalls shouldBe 1
            f.activated shouldBe emptyList()
            f.frames() shouldBe emptyList()
            old.isValid shouldBe false
            f.panels.onAttack(PrePlayerAttackEntityEvent(f.player, old, false))
            f.activationCalls shouldBe 1
            f.refresh()
            f.panels.contains(f.player.uniqueId) shouldBe false
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
    var frameFailure: Exception? = null
    var auditFailure: Exception? = null
    var activationFailure: Exception? = null
    var frameCalls = 0
    var auditCalls = 0
    var activationCalls = 0

    init {
        val worldId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        every { world.uid } returns worldId
        every { world.name } returns "action-panel-world"
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { player.isChunkSent(any<Long>()) } returns true
        every { world.rayTraceBlocks(any(), any(), any(), any(), any(), any()) } answers {
            val origin = firstArg<Location>()
            val direction = secondArg<org.bukkit.util.Vector>()
            val end = origin.toVector().add(direction.clone().multiply(thirdArg<Double>()))
            if (worldSpace.rayClear(player, ActionPanelPoint(origin.x, origin.y, origin.z), ActionPanelPoint(end.x, end.y, end.z)) { true }) null
            else org.bukkit.util.RayTraceResult(origin.toVector())
        }
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
        displays = testPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), object : TestPacketDisplayAudience() {
            override fun checkThread() = Unit
            override fun capture() = listOf(PacketDisplayViewer(player, playerId, worldId, 1, emptySet(),
                body.x, body.y, body.z, (-1..1).flatMap { x -> (-1..1).map { z -> Chunk.getChunkKey(x, z) } }.toSet(), height = 1.8))
        })
        current = frame("first")
        panels = PaperActionPanels(plugin, displays, object : ActionPanelController<String, String>() {
            override fun frame(player: Player): ActionPanelFrame<String, String>? {
                frameCalls++; frameFailure?.let { throw it }; return current
            }
            override fun activate(player: Player, context: String, action: String) {
                activationCalls++; activationFailure?.let { throw it }; activated += context to action
            }
            override fun inputObserved(player: Player, context: String, action: String?, accepted: Boolean, reason: ActionPanelInputReason) {
                auditCalls++; auditFailure?.let { throw it }
            }
            override fun guideSuppressionChanged(playerId: UUID, context: String, suppressed: Boolean) { suppression += context to suppressed }
        }, false, worldSpace) { now }
    }

    fun frame(context: String, input: ActionPanelInputProfile = profile): ActionPanelFrame<String, String> {
        val rect = ActionPanelRect(ActionPanelPoint(0.0, 0.0, 0.0), 1.5, 0.3)
        val button = ActionPanelButton("confirm", rect, Component.text("Confirm"), ActionPanelTextStyle.button(0.8f, 1.5f, 0.3f))
        return ActionPanelFrame(context, ActionPanelPlacement.FollowTarget(world.uid, ActionPanelPoint(0.0, 64.0, 10.0)),
            ActionPanelLayout(listOf(button), emptyList()), true, ActionPanelOptions(input, 4.0, true), null)
    }
    fun overlay(context: String, actions: List<String>, navigation: Boolean = false): ActionPanelFrame<String, String> {
        val all = actions + if (navigation) listOf("previous", "next") else emptyList()
        val layout = ActionPanelLayouts.twoColumn(all.map { ActionPanelAction(it, Component.text(it)) }, Component.text("Title"),
            if (navigation) Component.text(context) else Component.empty(), if (navigation) 2 else 0, ActionPanelGridSettings())
        return ActionPanelFrame(context, ActionPanelPlacement.FollowView(world.uid, ActionPanelFollowSettings(), ActionPanelViewMotion.AIM_STABLE),
            layout, true, ActionPanelOptions(profile, 4.0, false, false, ActionPanelDepth.VIEW_OVERLAY), null)
    }
    fun anchor(): ActionPanelAnchor {
        val field = panels.javaClass.getDeclaredField("panels").apply { isAccessible = true }
        val panel = (field.get(panels) as Map<*, *>)[player.uniqueId]!!
        return panel.javaClass.getDeclaredField("anchor").apply { isAccessible = true }.get(panel) as ActionPanelAnchor
    }
    fun aim(action: String) {
        val button = checkNotNull(current).layout.buttons.first { it.action == action }
        val at = ActionPanelGeometry.localToWorld(anchor(), button.rect.center)
        eye.direction = Vector(at.x - eye.x, at.y - eye.y, at.z - eye.z)
    }
    fun poses() = frames().associate { it.entityId to Triple(it.x, it.y, it.z) }
    fun snapshot(state: String): String {
        val at = anchor()
        val frames = frames().joinToString { "{\"id\":${it.entityId},\"x\":${it.x},\"y\":${it.y},\"z\":${it.z},\"yaw\":${it.yaw},\"duration\":${it.metadata.teleportDuration}}" }
        val native = interactions.filter { it.isValid }.joinToString { "\"${it.uniqueId}\"" }
        return "{\"state\":\"$state\",\"eyeX\":${eye.x},\"eyeZ\":${eye.z},\"headYaw\":${eye.yaw},\"anchor\":{\"x\":${at.point.x},\"y\":${at.point.y},\"z\":${at.point.z},\"yaw\":${at.yaw}},\"target\":\"${panels.target(player)?.action}\",\"frames\":[$frames],\"nativeIds\":[$native]}"
    }
    /** Real placement/reach/sent-chunk checks; only native block shapes and block ray tracing are recorded. */
    fun useCollisionWorld(obstacles: List<BoundingBox>) {
        val native = ActionPanelWorldSpace()
        val blocks = mutableMapOf<Triple<Int, Int, Int>, Block>()
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            val x = firstArg<Int>(); val y = secondArg<Int>(); val z = thirdArg<Int>()
            blocks.getOrPut(Triple(x, y, z)) {
                mockk<Block>().also { block ->
                    every { block.collisionShape.boundingBoxes } answers {
                        obstacles.mapNotNull { box ->
                            val minX = maxOf(box.minX, x.toDouble()); val maxX = minOf(box.maxX, x + 1.0)
                            val minY = maxOf(box.minY, y.toDouble()); val maxY = minOf(box.maxY, y + 1.0)
                            val minZ = maxOf(box.minZ, z.toDouble()); val maxZ = minOf(box.maxZ, z + 1.0)
                            if (minX >= maxX || minY >= maxY || minZ >= maxZ) null
                            else BoundingBox(minX - x, minY - y, minZ - z, maxX - x, maxY - y, maxZ - z)
                        }
                    }
                }
            }
        }
        every { world.rayTraceBlocks(any(), any(), any(), any(), any(), any()) } answers {
            val origin = firstArg<Location>().toVector()
            val direction = secondArg<Vector>()
            val distance = thirdArg<Double>()
            obstacles.mapNotNull { it.rayTrace(origin, direction, distance) }.minByOrNull { it.hitPosition.distanceSquared(origin) }
        }
        every { worldSpace.safe(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            native.safe(firstArg(), secondArg(), thirdArg(), arg(3), arg(4), arg(5), arg(6), arg(7))
        }
        every { worldSpace.rayClear(any(), any(), any(), any()) } answers { native.rayClear(firstArg(), secondArg(), thirdArg(), arg(3)) }
        every { worldSpace.clearSweep(any(), any()) } answers { native.clearSweep(firstArg(), secondArg()) }
    }
    fun refresh() { panels.refresh(player); displays.refreshFully() }
    fun frames(): List<PacketDisplayFrame> { displays.refreshFully(); return transport.connection.frames }
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
