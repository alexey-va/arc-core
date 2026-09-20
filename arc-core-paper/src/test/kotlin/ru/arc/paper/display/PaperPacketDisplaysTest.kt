package ru.arc.paper.display

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.player.TestPaperPlugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import java.util.UUID

class PaperPacketDisplaysTest : FreeSpec({
    "visibility follows explicit viewers, worlds, range and received chunks without native spawns" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val world = paper.addSimpleWorld("displays")
            val player = paper.addPlayer("Viewer")
            val other = paper.addPlayer("Other")
            val audience = MutableAudience().apply {
                values = listOf(viewer(player, world.uid), viewer(other, world.uid))
            }
            val transport = RecordingTransport()
            val before = world.entities.size
            PaperPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), audience).use { displays ->
                val display = displays.spawnText(Location(world, 1.0, 64.0, 1.0), Component.text("Marker"))
                display.isVisibleByDefault = false
                display.showTo(player)
                displays.refresh()
                transport.forPlayer(player).last().frames.map { it.entityId } shouldBe listOf(display.entityId)
                transport.connections.containsKey(other.uniqueId) shouldBe false
                world.entities.size shouldBe before

                audience.values = listOf(viewer(player, world.uid, chunks = emptySet()))
                displays.refresh()
                transport.forPlayer(player).last().frames shouldBe emptyList()
                audience.values = listOf(viewer(player, world.uid))
                displays.refresh()
                transport.forPlayer(player).last().frames.single().entityId shouldBe display.entityId
                display.hideFrom(player)
                displays.refresh()
                transport.forPlayer(player).last().frames shouldBe emptyList()
                display.showTo(player)
                audience.values = listOf(viewer(player, UUID.randomUUID()))
                displays.refresh()
                transport.forPlayer(player).last().frames shouldBe emptyList()
                audience.values = listOf(viewer(player, world.uid, x = 65.01))
                displays.refresh()
                transport.forPlayer(player).last().frames shouldBe emptyList()
                audience.values = listOf(viewer(player, world.uid, x = 65.0))
                displays.refresh()
                transport.forPlayer(player).last().frames.size shouldBe 1
            }
        }
    }

    "snapshots own location and transformation and animation preserves identity" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val world = paper.addSimpleWorld("copies")
            val player = paper.addPlayer("Viewer")
            val audience = MutableAudience().apply { values = listOf(viewer(player, world.uid)) }
            val transport = RecordingTransport()
            PaperPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), audience).use { displays ->
                val location = Location(world, 1.0, 64.0, 1.0)
                val display = displays.spawnText(location, Component.text("A"))
                val transform = Transformation(Vector3f(0f), Quaternionf(), Vector3f(2f), Quaternionf())
                display.transformation = transform
                displays.refresh()
                val first = transport.forPlayer(player).last().frames.single()
                location.x = 1000.0
                transform.scale.x = 1000f
                display.location.x = 1000.0
                display.transformation.scale.x = 1000f
                displays.refresh()
                transport.forPlayer(player).last().frames.single() shouldBe first
                display.teleport(Location(world, 2.0, 64.0, 1.0))
                display.text(Component.text("B"))
                displays.refresh()
                val changed = transport.forPlayer(player).last().frames.single()
                changed.entityId shouldBe first.entityId
                changed.uuid shouldBe first.uuid
                changed.x shouldBe 2.0
                first.x shouldBe 1.0
                first.metadata.transform.scale.x shouldBe 2f
                first.metadata.content shouldBe PacketDisplayContent.Text(Component.text("A"))
            }
        }
    }

    "chunk resets survive a quick unload reload between snapshots and replacement connections clean up" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val world = paper.addSimpleWorld("replay")
            val player = paper.addPlayer("Viewer")
            val audience = MutableAudience().apply { values = listOf(viewer(player, world.uid)) }
            val transport = RecordingTransport()
            PaperPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), audience).use { displays ->
                val display = displays.spawnText(Location(world, 1.0, 64.0, 1.0), Component.text("Marker"))
                displays.refresh()
                val old = transport.forPlayer(player)
                displays.onChunkUnload(PlayerChunkUnloadEvent(world.getChunkAt(0, 0), player))
                displays.refresh()
                old.last().resetChunks shouldBe setOf(0L)
                old.last().frames.single().entityId shouldBe display.entityId
                val replacement = RecordingConnection()
                transport.connections[player.uniqueId] = replacement
                displays.refresh()
                old.last().frames shouldBe emptyList()
                replacement.last().frames.single().entityId shouldBe display.entityId
            }
        }
    }

    "remove and close are idempotent, cancel tasks and reject new handles" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val world = paper.addSimpleWorld("close")
            val player = paper.addPlayer("Viewer")
            val audience = MutableAudience().apply { values = listOf(viewer(player, world.uid)) }
            val transport = RecordingTransport()
            val scope = LifecycleTaskScope(TestTaskScheduler())
            val displays = PaperPacketDisplays(plugin, transport, scope, audience)
            val display = displays.spawnText(Location(world, 1.0, 64.0, 1.0), Component.text("Marker"))
            displays.refresh()
            display.remove()
            display.remove()
            displays.refresh()
            transport.forPlayer(player).last().frames shouldBe emptyList()
            display.isValid shouldBe false
            displays.close()
            displays.close()
            transport.closed shouldBe true
            scope.trackedTaskCount() shouldBe 0
            shouldThrow<IllegalStateException> { displays.spawnText(Location(world, 0.0, 64.0, 0.0), Component.empty()) }
        }
    }
})

private fun viewer(player: Player, world: UUID, x: Double = 1.0, chunks: Set<Long> = setOf(0L)) =
    PacketDisplayViewer(player, player.uniqueId, world, x, 64.0, 1.0, chunks)

private class MutableAudience : PacketDisplayAudienceSource {
    var values = emptyList<PacketDisplayViewer>()
    override fun checkThread() = Unit
    override fun capture() = values
}

private class RecordingTransport : PacketDisplayTransport {
    val connections = mutableMapOf<UUID, RecordingConnection>()
    var next = 100
    var closed = false
    fun forPlayer(player: Player) = connections.getValue(player.uniqueId)
    override fun blockStateId(blockData: BlockData) = error("Not used in text-only platform tests")
    override fun itemSnapshot(item: ItemStack): com.github.retrooper.packetevents.protocol.item.ItemStack = error("Not used")
    override fun nextEntityId() = next++
    override fun connection(player: Player) = connections.getOrPut(player.uniqueId, ::RecordingConnection)
    override fun forget(player: Player) { connections.remove(player.uniqueId) }
    override fun close() { closed = true }
}

private class RecordingConnection : PacketDisplayConnection {
    data class Frame(val frames: List<PacketDisplayFrame>, val resetChunks: Set<Long>, val resetAll: Boolean)
    override val identity: Any = Any()
    private val frames = mutableListOf<Frame>()
    fun last() = frames.last()
    override fun submit(desired: List<PacketDisplayFrame>, resetChunks: Set<Long>, resetAll: Boolean) {
        frames += Frame(desired, resetChunks, resetAll)
    }
}
