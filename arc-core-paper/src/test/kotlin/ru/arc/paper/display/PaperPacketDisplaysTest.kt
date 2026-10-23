package ru.arc.paper.display

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.entity.Horse
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.ServicePriority
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcAfkProvider
import ru.arc.paper.player.TestPaperPlugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.paper.testing.loadPlugin
import java.util.UUID

class PaperPacketDisplaysTest : FreeSpec({
    "Bukkit captures optional canonical AFK status once per viewer into detached snapshots" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadPlugin<TestPaperPlugin>()
                val world = paper.addSimpleWorld("afk-audience")
                val viewers = List(2) { index ->
                    RespawnAudiencePlayer(paper.server, "AFK-$index").also {
                        paper.server.addPlayer(it)
                        it.setLocation(Location(world, 1.0, 64.0, 1.0))
                    }
                }
                fun capture(): List<PacketDisplayViewer> = buildList {
                    BukkitPacketDisplayAudience.captureSteps { add(it) }.forEach { }
                }
                capture().map { it.view?.afk } shouldBe listOf(false, false)
                var calls = 0
                val provider = object : ArcAfkProvider {
                    override fun isAfk(playerId: UUID): Boolean {
                        org.bukkit.Bukkit.isPrimaryThread() shouldBe true
                        calls++
                        paper.server.servicesManager.unregister(ArcAfkProvider::class.java, this)
                        return playerId in viewers.map { it.uniqueId }
                    }
                }
                paper.server.servicesManager.register(ArcAfkProvider::class.java, provider, plugin, ServicePriority.Normal)
                val paused = capture()
                calls shouldBe 2 // Resolve the provider once for the whole capture, not for every viewer.
                paused.map { it.view?.afk } shouldBe listOf(true, true)
                capture().map { it.view?.afk } shouldBe listOf(false, false)
                paused.map { it.view?.afk } shouldBe listOf(true, true)
            }
        }
    }

    "Bukkit audience skips an online player through death and respawn without interrupting another viewer" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadPlugin<TestPaperPlugin>()
                val world = paper.addSimpleWorld("respawn-audience")
                val player = RespawnAudiencePlayer(paper.server, "Respawning").also(paper.server::addPlayer)
                val observer = RespawnAudiencePlayer(paper.server, "Observer").also(paper.server::addPlayer)
                val location = Location(world, 1.0, 64.0, 1.0)
                player.setLocation(location)
                observer.setLocation(location)
                val transport = RecordingTransport()
                testPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), BukkitPacketDisplayAudience).use { displays ->
                    val display = displays.spawnText(location, Component.text("Before death"))
                    displays.refreshFully()
                    transport.forPlayer(player).last().frames.single().entityId shouldBe display.entityId
                    transport.forPlayer(observer).last().frames.single().entityId shouldBe display.entityId
                    player.sentChunkReads shouldBe 1

                    player.health = 0.0
                    player.inWorld = false
                    player.isOnline shouldBe true
                    player.isDead shouldBe true
                    display.text(Component.text("While dead"))
                    displays.refreshFully()
                    player.sentChunkReads shouldBe 1
                    transport.forPlayer(player).last().frames shouldBe emptyList()
                    transport.forPlayer(observer).last().frames.single().metadata.content shouldBe
                        PacketDisplayContent.Text(Component.text("While dead"))

                    // Native respawn restores life before reattaching the entity/chunk loader.
                    player.respawn()
                    player.setLocation(location)
                    player.isDead shouldBe false
                    player.isValid shouldBe false
                    display.text(Component.text("While respawning"))
                    displays.refreshFully()
                    player.sentChunkReads shouldBe 1
                    transport.forPlayer(player).last().frames shouldBe emptyList()
                    transport.forPlayer(observer).last().frames.single().metadata.content shouldBe
                        PacketDisplayContent.Text(Component.text("While respawning"))

                    player.inWorld = true
                    paper.callEvent(PlayerPostRespawnEvent(player, location, false, false, false, PlayerRespawnEvent.RespawnReason.DEATH))
                    display.text(Component.text("After respawn"))
                    displays.refreshFully()
                    player.sentChunkReads shouldBe 2
                    transport.forPlayer(player).last().resetAll shouldBe true
                    transport.forPlayer(player).last().frames.single().entityId shouldBe display.entityId
                    transport.forPlayer(player).last().frames.single().metadata.content shouldBe
                        PacketDisplayContent.Text(Component.text("After respawn"))
                    transport.forPlayer(observer).last().frames.single().metadata.content shouldBe
                        PacketDisplayContent.Text(Component.text("After respawn"))
                }
            }
        }
    }

    "scene groups hide static and animated handles without publishing private cards" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val world = paper.addSimpleWorld("scene-gates")
            val player = paper.addPlayer("Seated")
            val observer = paper.addPlayer("Observer")
            val audience = MutableAudience().apply {
                values = listOf(viewer(player, world.uid), viewer(observer, world.uid))
            }
            val transport = RecordingTransport()
            testPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), audience).use { displays ->
                var open = false
                val calls = mutableMapOf<UUID, Int>()
                val group = displays.createAudienceGroup {
                    calls[it.uniqueId] = (calls[it.uniqueId] ?: 0) + 1
                    open
                }
                val public = List(20) {
                    displays.spawnText(Location(world, 1.0, 64.0, 1.0), Component.text("part-$it"))
                        .apply { audienceGroup = group }
                }
                val card = displays.spawnText(Location(world, 1.0, 64.0, 1.0), Component.text("private"))
                    .apply { audienceGroup = group; isVisibleByDefault = false; showTo(player) }
                displays.refreshFully()
                calls shouldBe mapOf(player.uniqueId to 1, observer.uniqueId to 1)
                transport.connections shouldBe emptyMap()

                open = true
                displays.refreshFully()
                calls.values.toSet() shouldBe setOf(2)
                transport.forPlayer(player).last().frames.size shouldBe 21
                transport.forPlayer(observer).last().frames.map { it.entityId } shouldBe public.map { it.entityId }
                card.hideFrom(player)
                public.first().teleport(Location(world, 2.0, 64.0, 1.0))
                displays.refreshFully()
                transport.forPlayer(player).last().frames.size shouldBe 20
                card.showTo(player)
                open = false
                displays.refreshFully()
                transport.forPlayer(player).last().frames shouldBe emptyList()
                transport.forPlayer(observer).last().frames shouldBe emptyList()
                open = true
                displays.refreshFully()
                transport.forPlayer(player).last().frames.size shouldBe 21
                transport.forPlayer(observer).last().frames.size shouldBe 20

                // World/chunk/range guards avoid even invoking a scene's expensive policy.
                val before = calls.toMap()
                audience.values = listOf(viewer(player, world.uid, chunks = emptySet()),
                    viewer(observer, UUID.randomUUID()))
                displays.refreshFully()
                calls shouldBe before
                audience.values = listOf(viewer(player, world.uid, x = 1000.0))
                displays.refreshFully()
                calls shouldBe before

                group.close()
                group.close()
                audience.values = listOf(viewer(player, world.uid))
                displays.refreshFully()
                calls shouldBe before
                transport.forPlayer(player).last().frames shouldBe emptyList()
                shouldThrow<IllegalArgumentException> { public.first().audienceGroup = group }
                public.first().audienceGroup = null
                displays.refreshFully()
                transport.forPlayer(player).last().frames.single().entityId shouldBe public.first().entityId
            }
        }
    }

    "groups are owner-bound and close with the display owner" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val world = paper.addSimpleWorld("group-ownership")
            val owner = testPacketDisplays(plugin, RecordingTransport(), LifecycleTaskScope(TestTaskScheduler()), MutableAudience())
            val other = testPacketDisplays(plugin, RecordingTransport(), LifecycleTaskScope(TestTaskScheduler()), MutableAudience())
            val group = owner.createAudienceGroup { true }
            val handle = owner.spawnText(Location(world, 1.0, 64.0, 1.0), Component.empty())
                .apply { audienceGroup = group }
            handle.detach()
            handle.audienceGroup shouldBe group
            val foreign = other.spawnText(Location(world, 1.0, 64.0, 1.0), Component.empty())
            shouldThrow<IllegalArgumentException> { foreign.audienceGroup = group }
            owner.close()
            group.isClosed shouldBe true
            handle.audienceGroup shouldBe null
            shouldThrow<IllegalStateException> { owner.createAudienceGroup { true } }
            other.close()
        }
    }

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
            testPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), audience).use { displays ->
                val display = displays.spawnText(Location(world, 1.0, 64.0, 1.0), Component.text("Marker"))
                display.isVisibleByDefault = false
                display.showTo(player)
                displays.refreshFully()
                transport.forPlayer(player).last().frames.map { it.entityId } shouldBe listOf(display.entityId)
                transport.connections.containsKey(other.uniqueId) shouldBe false
                world.entities.size shouldBe before

                audience.values = listOf(viewer(player, world.uid, chunks = emptySet()))
                displays.refreshFully()
                transport.forPlayer(player).last().frames shouldBe emptyList()
                audience.values = listOf(viewer(player, world.uid))
                displays.refreshFully()
                transport.forPlayer(player).last().frames.single().entityId shouldBe display.entityId
                display.hideFrom(player)
                displays.refreshFully()
                transport.forPlayer(player).last().frames shouldBe emptyList()
                display.showTo(player)
                audience.values = listOf(viewer(player, UUID.randomUUID()))
                displays.refreshFully()
                transport.forPlayer(player).last().frames shouldBe emptyList()
                audience.values = listOf(viewer(player, world.uid, x = 65.01))
                displays.refreshFully()
                transport.forPlayer(player).last().frames shouldBe emptyList()
                audience.values = listOf(viewer(player, world.uid, x = 65.0))
                displays.refreshFully()
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
            testPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), audience).use { displays ->
                val location = Location(world, 1.0, 64.0, 1.0)
                val display = displays.spawnText(location, Component.text("A"))
                val transform = Transformation(Vector3f(0f), Quaternionf(), Vector3f(2f), Quaternionf())
                display.transformation = transform
                displays.refreshFully()
                val first = transport.forPlayer(player).last().frames.single()
                location.x = 1000.0
                transform.scale.x = 1000f
                display.location.x = 1000.0
                display.transformation.scale.x = 1000f
                displays.refreshFully()
                transport.forPlayer(player).last().frames.single() shouldBe first
                display.teleport(Location(world, 2.0, 64.0, 1.0))
                display.text(Component.text("B"))
                displays.refreshFully()
                val changed = transport.forPlayer(player).last().frames.single()
                changed.entityId shouldBe first.entityId
                changed.uuid shouldBe first.uuid
                changed.x shouldBe 2.0
                first.x shouldBe 1.0
                first.metadata.transform.scale.x shouldBe 2f
                first.metadata.content shouldBe PacketDisplayContent.Text(Component.text("A"))

                val destination = paper.addSimpleWorld("copies-destination")
                display.teleport(Location(destination, 3.0, 64.0, 1.0))
                audience.values = listOf(viewer(player, destination.uid))
                displays.refreshFully()
                val movedWorld = transport.forPlayer(player).last().frames.single()
                movedWorld.worldId shouldBe destination.uid
                movedWorld.entityId shouldBe first.entityId
                first.worldId shouldBe world.uid
            }
        }
    }

    "mounted frames use the carrier snapshot and only reach viewers tracking a visible carrier" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val world = paper.addSimpleWorld("mounted-audience")
            val carrier = paper.addPlayer("Carrier")
            val bystander = paper.addPlayer("Bystander")
            val audience = MutableAudience().apply {
                values = listOf(viewer(carrier, world.uid), viewer(bystander, world.uid))
            }
            val transport = RecordingTransport()
            testPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), audience).use { displays ->
                val display = displays.spawnText(Location(world, 1.0, 64.0, 1.0), Component.text("Held"))
                display.attachTo(carrier)
                display.transformation = Transformation(Vector3f(0f, .35f, 0f), Quaternionf(), Vector3f(1f), Quaternionf())
                displays.refreshFully()

                val initial = transport.forPlayer(carrier).last().frames.single()
                initial.attachmentVehicleId shouldBe carrier.entityId
                initial.y shouldBe 65.8
                initial.metadata.transform.translation.y shouldBe .35f
                transport.connections.containsKey(bystander.uniqueId) shouldBe false

                audience.values = listOf(
                    viewer(carrier, world.uid).copy(trackedBy = setOf(bystander.uniqueId)),
                    viewer(bystander, world.uid),
                )
                displays.refreshFully()
                transport.forPlayer(bystander).last().passengers shouldBe mapOf(carrier.entityId to listOf(display.entityId))
                transport.forPlayer(bystander).last().frames.single().x shouldBe audience.values.first { it.id == carrier.uniqueId }.x

                val replacement = RecordingConnection()
                transport.connections[bystander.uniqueId] = replacement
                displays.refreshFully()
                replacement.last().frames.single().entityId shouldBe display.entityId
                replacement.last().passengers shouldBe mapOf(carrier.entityId to listOf(display.entityId))

                display.teleport(Location(world, 3.0, 66.0, 3.0))
                display.detach()
                displays.refreshFully()
                replacement.last().passengers shouldBe emptyMap()
                replacement.last().frames.single().x shouldBe 3.0
                replacement.last().frames.single().y shouldBe 66.0
            }
        }
    }

    "mounted Citizens player carriers are tracked without becoming online viewers or reading chunk loaders" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadPlugin<TestPaperPlugin>()
                val world = paper.addSimpleWorld("citizen-carrier")
                val observer = paper.addPlayer("Observer")
                val carrier = RespawnAudiencePlayer(paper.server, "Resident").apply {
                    setLocation(Location(world, 1.0, 64.0, 1.0))
                    observers = setOf(observer)
                }
                val audience = MutableAudience().apply { values = listOf(viewer(observer, world.uid)) }
                val transport = RecordingTransport()
                testPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), audience).use { displays ->
                    val label = displays.spawnText(carrier.location, Component.text("Resident"))
                    label.attachTo(carrier)
                    displays.refreshFully()
                    val first = transport.forPlayer(observer).last()
                    first.passengers shouldBe mapOf(carrier.entityId to listOf(label.entityId))
                    first.frames.single().attachmentVehicleId shouldBe carrier.entityId
                    first.frames.single().y shouldBe 65.8
                    carrier.sentChunkReads shouldBe 0
                    transport.connections.containsKey(carrier.uniqueId) shouldBe false

                    carrier.setLocation(Location(world, 4.0, 65.0, 1.0))
                    carrier.nativeHeight = 1.5
                    displays.refreshFully()
                    val moving = transport.forPlayer(observer).last().frames.single()
                    moving.entityId shouldBe first.frames.single().entityId
                    moving.x shouldBe 4.0
                    moving.y shouldBe 66.5
                    carrier.sentChunkReads shouldBe 0

                    carrier.observers = emptySet()
                    displays.refreshFully()
                    transport.forPlayer(observer).last().frames shouldBe emptyList()
                    carrier.observers = setOf(observer)
                    displays.refreshFully()
                    transport.forPlayer(observer).last().frames.single().entityId shouldBe label.entityId

                    carrier.inWorld = false
                    displays.refreshFully()
                    transport.forPlayer(observer).last().frames shouldBe emptyList()
                    transport.forPlayer(observer).last().passengers shouldBe emptyMap()
                }
            }
        }
    }

    "native mob attachments retain real riders and follow tracking movement and invalidation" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val world = paper.addSimpleWorld("horse-carrier")
            val observer = paper.addPlayer("Observer")
            val rider = paper.addPlayer("Rider")
            var horseLocation = Location(world, 1.0, 64.0, 1.0)
            var observers = setOf<Player>(observer)
            var valid = true
            val horseId = UUID.randomUUID()
            val horse = mockk<Horse> {
                every { uniqueId } returns horseId
                every { entityId } returns 900
                every { isValid } answers { valid }
                every { location } answers { horseLocation.clone() }
                every { getWorld() } answers { requireNotNull(horseLocation.world) }
                every { getTrackedBy() } answers { observers }
                every { passengers } returns listOf(rider)
            }
            val audience = MutableAudience().apply { values = listOf(viewer(observer, world.uid)) }
            val transport = RecordingTransport()
            testPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), audience).use { displays ->
                val parts = List(3) { displays.spawnText(horseLocation, Component.text("Tack-$it")) }
                val offset = Vector(0.0, 1.44375, 0.0)
                parts.forEach { it.attachTo(horse, offset) }
                offset.y = 100.0 // Attachment owns a copy, not a caller's mutable input.
                displays.refreshFully()
                val first = transport.forPlayer(observer).last()
                first.passengers shouldBe mapOf(900 to parts.map { it.entityId })
                first.nativePassengers shouldBe mapOf(900 to listOf(rider.entityId))
                first.frames.map { it.y } shouldBe List(3) { 65.44375 }
                verify(exactly = 1) { horse.getTrackedBy() }

                horseLocation = Location(world, 4.0, 65.0, 1.0, 90f, 0f)
                parts.forEach { it.teleport(Location(world, -20.0, 20.0, -20.0)) }
                displays.refreshFully()
                val moving = transport.forPlayer(observer).last()
                moving.frames.map { it.entityId } shouldBe first.frames.map { it.entityId }
                moving.frames.map { it.x } shouldBe List(3) { 4.0 }
                moving.frames.map { it.y } shouldBe List(3) { 66.44375 }
                moving.frames.map { it.attachmentVehicleId } shouldBe List(3) { 900 }

                observers = emptySet()
                displays.refreshFully()
                transport.forPlayer(observer).last().frames shouldBe emptyList()
                observers = setOf(observer)
                displays.refreshFully()
                transport.forPlayer(observer).last().frames.map { it.entityId } shouldBe parts.map { it.entityId }

                valid = false
                displays.refreshFully()
                transport.forPlayer(observer).last().frames shouldBe emptyList()
                transport.forPlayer(observer).last().passengers shouldBe emptyMap()
                valid = true
                parts.forEach { it.detach() }
                audience.values = listOf(viewer(observer, world.uid, x = -20.0, chunks = setOf((-2L shl 32) or 0xfffffffeL)))
                displays.refreshFully()
                transport.forPlayer(observer).last().passengers shouldBe emptyMap()
                transport.forPlayer(observer).last().frames.map { it.x } shouldBe List(3) { -20.0 }
                shouldThrow<IllegalArgumentException> { parts.first().attachTo(horse, Vector(Double.NaN, 0.0, 0.0)) }
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
            testPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), audience).use { displays ->
                val display = displays.spawnText(Location(world, 1.0, 64.0, 1.0), Component.text("Marker"))
                displays.refreshFully()
                val old = transport.forPlayer(player)
                displays.onChunkUnload(PlayerChunkUnloadEvent(world.getChunkAt(0, 0), player))
                displays.refreshFully()
                old.last().resetChunks shouldBe setOf(0L)
                old.last().frames.single().entityId shouldBe display.entityId
                val replacement = RecordingConnection()
                transport.connections[player.uniqueId] = replacement
                displays.refreshFully()
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
            val displays = testPacketDisplays(plugin, transport, scope, audience)
            val display = displays.spawnText(Location(world, 1.0, 64.0, 1.0), Component.text("Marker"))
            displays.refreshFully()
            display.remove()
            display.remove()
            displays.refreshFully()
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

private fun viewer(
    player: Player,
    world: UUID,
    x: Double = 1.0,
    chunks: Set<Long> = setOf(0L),
    trackedBy: Set<UUID> = emptySet(),
) = PacketDisplayViewer(
    player, player.uniqueId, world, player.entityId, trackedBy, x, 64.0, 1.0, chunks,
    player.passengers.map { it.entityId }, height = 1.8,
)

private class MutableAudience : TestPacketDisplayAudience() {
    var values = emptyList<PacketDisplayViewer>()
    override fun checkThread() = Unit
    override fun capture() = values
}

private class RespawnAudiencePlayer(server: ServerMock, name: String) : PlayerMock(server, name) {
    var inWorld = true
    var sentChunkReads = 0
    var observers = emptySet<Player>()
    var nativeHeight = 1.8

    // CraftEntity on Purpur 1.21.11-2568 requires both isAlive() and Entity.valid;
    // MockBukkit's default isValid() checks removal only.
    override fun isValid(): Boolean = !isDead && inWorld
    override fun getTrackedBy(): Set<Player> = observers
    override fun getHeight(): Double = nativeHeight
    override fun getSentChunkKeys(): Set<Long> {
        sentChunkReads++
        if (!inWorld) throw NullPointerException("Native player chunk loader is detached")
        return setOf(0L)
    }
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
    data class Frame(
        val frames: List<PacketDisplayFrame>,
        val resetChunks: Set<Long>,
        val resetAll: Boolean,
        val passengers: Map<Int, List<Int>>,
        val nativePassengers: Map<Int, List<Int>>,
    )
    override val identity: Any = Any()
    private val frames = mutableListOf<Frame>()
    fun last() = frames.last()
    override fun submit(
        desired: List<PacketDisplayFrame>,
        resetChunks: Set<Long>,
        resetAll: Boolean,
        desiredPassengers: Map<Int, List<Int>>,
        nativePassengerSnapshots: Map<Int, List<Int>>,
        liveVehicleIds: Set<Int>?,
    ) {
        frames += Frame(desired, resetChunks, resetAll, desiredPassengers, nativePassengerSnapshots)
    }
}
