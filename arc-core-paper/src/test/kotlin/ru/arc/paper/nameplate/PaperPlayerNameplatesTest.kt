package ru.arc.paper.nameplate

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.core.BukkitTaskScheduler
import ru.arc.nameplate.NameplateLayer
import ru.arc.nameplate.NameplateLayerKey
import ru.arc.nameplate.PlayerNameplateRegistry
import ru.arc.paper.player.TestPaperPlugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.RecordingPaperNameplateDisplayFactory
import ru.arc.paper.testing.loadPlugin
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord

class PaperPlayerNameplatesTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var plugin: TestPaperPlugin

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        plugin = paper.loadPlugin<TestPaperPlugin>()
    }

    afterEach { paper.close() }

    "one display composes updates and applies per-viewer visibility" {
        val target = paper.addPlayer("Target")
        val viewer = paper.addPlayer("Viewer")
        val registry = PlayerNameplateRegistry()
        val displays = RecordingPaperNameplateDisplayFactory()
        val visibility = ControlledVisibilityPolicy().apply { allow(viewer.uniqueId, target.uniqueId) }
        val nameplates = PaperPlayerNameplates.open(
            plugin = plugin,
            registry = registry,
            scheduler = BukkitTaskScheduler(plugin),
            displays = displays,
            visibility = visibility,
        )
        val health = NameplateLayerKey("arcduels", "health")

        registry.upsert(target.uniqueId, NameplateLayer(health, 100, Component.text("20 ❤")))
        nameplates.refreshNow()

        displays.records.size shouldBe 1
        val record = displays.latest(target.uniqueId)!!
        plain(record.contents.single()) shouldBe "20 ❤"
        record.showCalls shouldBe listOf(viewer.uniqueId)
        record.shownTo shouldBe setOf(viewer.uniqueId)
        nameplates.activeDisplayCount shouldBe 1
        nameplates.visibleViewerCount shouldBe 1

        registry.upsert(target.uniqueId, NameplateLayer(health, 100, Component.text("17 ❤")))
        nameplates.refreshNow()
        plain(record.contents.last()) shouldBe "17 ❤"
        record.showCalls shouldBe listOf(viewer.uniqueId)

        visibility.deny(viewer.uniqueId, target.uniqueId)
        nameplates.refreshNow()
        record.hideCalls shouldBe listOf(viewer.uniqueId)
        record.shownTo shouldBe emptySet()

        nameplates.close()
        record.closed shouldBe true
    }

    "removed registry targets and lost passenger attachments are reconciled" {
        val target = paper.addPlayer("Target")
        val registry = PlayerNameplateRegistry()
        val displays = RecordingPaperNameplateDisplayFactory()
        val nameplates = PaperPlayerNameplates.open(
            plugin = plugin,
            registry = registry,
            scheduler = BukkitTaskScheduler(plugin),
            displays = displays,
            visibility = PaperNameplateVisibilityPolicy { _, _ -> false },
        )
        val key = NameplateLayerKey("arcevents", "state")
        registry.upsert(target.uniqueId, NameplateLayer(key, 0, Component.text("В бою")))
        nameplates.refreshNow()
        val first = displays.latest(target.uniqueId)!!

        first.attached = false
        nameplates.refreshNow()
        displays.records.size shouldBe 2
        first.closed shouldBe true
        displays.latest(target.uniqueId)!!.closed shouldBe false

        registry.clearTarget(target.uniqueId) shouldBe 1
        nameplates.refreshNow()
        displays.latest(target.uniqueId)!!.closed shouldBe true
        nameplates.activeDisplayCount shouldBe 0
        nameplates.close()
    }

    "offline target data remains registered without leaking a display" {
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000099")
        val registry = PlayerNameplateRegistry()
        val displays = RecordingPaperNameplateDisplayFactory()
        registry.upsert(
            playerId,
            NameplateLayer(NameplateLayerKey("arcranks", "role"), 0, Component.text("Игрок")),
        )
        val nameplates = PaperPlayerNameplates.open(
            plugin = plugin,
            registry = registry,
            scheduler = BukkitTaskScheduler(plugin),
            displays = displays,
            visibility = PaperNameplateVisibilityPolicy { _, _ -> true },
        )

        nameplates.refreshNow()

        registry.targetCount() shouldBe 1
        displays.records shouldBe emptyList()
        nameplates.activeDisplayCount shouldBe 0
        nameplates.close()
    }

    "options reject unbounded polling and rendering values" {
        runCatching { PaperNameplateOptions(reconcilePeriodTicks = 0) }.isFailure shouldBe true
        runCatching { PaperNameplateOptions(maxDistance = Double.NaN) }.isFailure shouldBe true
        runCatching { PaperNameplateOptions(lineWidth = 0) }.isFailure shouldBe true
        runCatching { PaperNameplateOptions(viewRange = 5F) }.isFailure shouldBe true
        runCatching { PaperNameplateOptions(scale = 0F) }.isFailure shouldBe true
        runCatching { PaperNameplateOptions(verticalOffset = Float.NaN) }.isFailure shouldBe true
    }

    "native visibility policy enforces self distance world invisibility and spectator privacy" {
        val viewer = paper.addPlayer("Viewer")
        val target = paper.addPlayer("Target")
        val policy = NativePaperNameplateVisibilityPolicy(
            PaperNameplateOptions(requireLineOfSight = false),
        )

        policy.canView(viewer, target) shouldBe true
        policy.canView(target, target) shouldBe false

        viewer.teleport(Location(viewer.world, 64.0, viewer.location.y, 0.0))
        policy.canView(viewer, target) shouldBe false
        viewer.teleport(target.location)

        target.isInvisible = true
        policy.canView(viewer, target) shouldBe false
        target.isInvisible = false
        target.gameMode = GameMode.SPECTATOR
        policy.canView(viewer, target) shouldBe false
        target.gameMode = GameMode.SURVIVAL

        val otherWorld = paper.addSimpleWorld("other")
        target.teleport(Location(otherWorld, 0.0, 64.0, 0.0))
        policy.canView(viewer, target) shouldBe false
    }

    "a repeated platform failure is isolated and logged once until recovery" {
        val target = paper.addPlayer("Target")
        val registry = PlayerNameplateRegistry()
        registry.upsert(
            target.uniqueId,
            NameplateLayer(NameplateLayerKey("arcevents", "state"), 0, Component.text("В бою")),
        )
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.message.startsWith("ARC_NAMEPLATE")) records += record
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        plugin.logger.addHandler(handler)
        try {
            val nameplates = PaperPlayerNameplates.open(
                plugin = plugin,
                registry = registry,
                scheduler = BukkitTaskScheduler(plugin),
                displays = PaperNameplateDisplayFactory { _, _ -> error("display unavailable") },
                visibility = PaperNameplateVisibilityPolicy { _, _ -> false },
            )

            nameplates.refreshNow()
            nameplates.refreshNow()

            records.size shouldBe 1
            records.single().message shouldBe "ARC_NAMEPLATE outcome=reconcile_failed reason=IllegalStateException"
            nameplates.activeDisplayCount shouldBe 0
            nameplates.close()
        } finally {
            plugin.logger.removeHandler(handler)
        }
    }
})

private class ControlledVisibilityPolicy : PaperNameplateVisibilityPolicy {
    private val allowed = hashSetOf<Pair<UUID, UUID>>()

    fun allow(
        viewerId: UUID,
        targetId: UUID,
    ) {
        allowed += viewerId to targetId
    }

    fun deny(
        viewerId: UUID,
        targetId: UUID,
    ) {
        allowed -= viewerId to targetId
    }

    override fun canView(
        viewer: Player,
        target: Player,
    ): Boolean = viewer.uniqueId to target.uniqueId in allowed
}

private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
