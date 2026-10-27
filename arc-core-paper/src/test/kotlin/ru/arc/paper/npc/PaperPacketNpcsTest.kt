package ru.arc.paper.npc

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.api.ArcSceneBounds
import ru.arc.paper.api.ArcSceneObservationService
import ru.arc.paper.scene.PaperSceneObservationServiceProvider
import ru.arc.paper.display.PacketDisplayViewer
import ru.arc.paper.display.TestPacketDisplayAudience
import ru.arc.paper.display.TestPacketEventsApi
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.BooleanSupplier
import java.util.logging.Logger

class PaperPacketNpcsTest : FreeSpec({
    val previousApi = PacketEvents.getAPI()
    beforeSpec { PacketEvents.setAPI(TestPacketEventsApi()) }
    afterSpec { PacketEvents.setAPI(previousApi) }

    "viewer entrance departure range chunks worlds and connection replacement reconcile current actor" {
        MockBukkitTestRuntime.open().use {
            NpcFixture().use { f ->
                val actor = f.spawn()
                f.refresh()
                f.backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().single().entityId shouldBe actor.entityId
                f.backend.clear()
                f.audience.values = listOf(f.viewer.copy(x = 100.0))
                f.refresh()
                f.backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single()
                f.backend.clear()
                f.audience.values = listOf(f.viewer.copy(sentChunks = emptySet()))
                f.refresh()
                f.backend.writes.size shouldBe 0
                f.audience.values = listOf(f.viewer.copy(worldId = UUID.randomUUID()))
                f.refresh()
                f.backend.writes.size shouldBe 0
                actor.teleport(Location(f.world, 1.0, 64.0, 1.0))
                f.audience.values = listOf(f.viewer)
                f.refresh()
                f.backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().single().position.x shouldBe 1.0
                f.backend.clear()
                val old = f.connection
                f.replaceConnection()
                f.refresh()
                f.backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().size shouldBe 1
                old.lease(actor.entityId) shouldBe null
                actor.remove()
                f.refresh()
                f.connection.lease(actor.entityId) shouldBe null
                f.transport.ownedIds shouldBe emptySet()
            }
        }
    }

    "main-thread interaction accepts visible reach and rejects forged unseen world wall range aim and retired generation" {
        MockBukkitTestRuntime.open().use {
            NpcFixture().use { f ->
                val actor = f.spawn()
                var calls = 0
                f.owner.onInteract = { player, npc, action ->
                    org.bukkit.Bukkit.isPrimaryThread() shouldBe true
                    player shouldBe f.player
                    npc shouldBe actor
                    action shouldBe PacketNpcInteraction.INTERACT
                    calls++
                }
                fun click(id: Int = actor.entityId, connection: PacketNpcConnection = f.connection) {
                    val admitted = connection.lease(id) ?: f.connection.lease(actor.entityId) ?: return
                    f.owner.interact(f.player.uniqueId, connection, id, admitted, PacketNpcInteraction.INTERACT)
                }
                click() // never shown
                calls shouldBe 0
                f.refresh()
                click(); calls shouldBe 1
                actor.viewRange = 0.5
                click(); calls shouldBe 1
                actor.viewRange = 48.0
                f.refresh()
                click(999); calls shouldBe 1
                f.wall = true
                click(); calls shouldBe 1
                f.wall = false
                f.eye = Location(f.world, 0.0, 65.62, -10.0)
                click(); calls shouldBe 1
                f.eye = Location(f.world, 0.0, 65.62, -2.0, 180f, 0f)
                click(); calls shouldBe 1
                f.eye = Location(f.world, 0.0, 65.62, -2.0)
                f.online = false
                click(); calls shouldBe 1
                f.online = true
                f.playerWorld = mockk { every { uid } returns UUID.randomUUID() }
                click(); calls shouldBe 1
                f.playerWorld = f.world
                f.chunks = emptySet()
                click(); calls shouldBe 1
                f.chunks = setOf(0L)
                actor.hideFrom(f.player)
                click(); calls shouldBe 1
                actor.showTo(f.player)
                f.refresh()
                val old = f.connection
                f.replaceConnection(); f.refresh()
                click(connection = old); calls shouldBe 1
                click(); calls shouldBe 2
                actor.remove()
                click(); calls shouldBe 2
            }
        }
    }

    "host cleanup remains pending after consumer tasks stop and completes only after connection cleanup drains" {
        MockBukkitTestRuntime.open().use {
            val f = NpcFixture()
            val actor = f.spawn()
            f.refresh()
            f.backend.writable = false
            val closed = f.owner.closeAsync()
            f.cleanup.step()
            closed.isDone shouldBe false
            actor.isValid shouldBe false
            f.scope.trackedTaskCount() shouldBe 0
            f.cleanup.step() // The next host quantum retires the viewer and queues its Netty cleanup.
            f.backend.runOne()
            f.cleanup.step()
            closed.isDone shouldBe false
            f.backend.writable = true
            f.backend.runAll()
            f.cleanup.finish()
            closed.join()
            verify(exactly = 1) { f.transport.close() }
            f.owner.closeAsync().join()
        }
    }

    "queued intake cannot cross a hide-show or chunk replay generation on the same connection" {
        MockBukkitTestRuntime.open().use {
            for (chunkReplay in listOf(false, true)) {
                NpcFixture().use { f ->
                    val actor = f.spawn()
                    f.refresh()
                    var calls = 0
                    f.owner.onInteract = { _, _, _ -> calls++ }
                    val admitted = requireNotNull(f.connection.admitInput(actor.entityId))
                    f.owner.enqueueInput(f.player.uniqueId, f.connection, actor.entityId, admitted, PacketNpcInteraction.INTERACT)
                    if (chunkReplay) {
                        f.connection.submit(listOf(actor.frame()), setOf(actor.frame().chunkKey))
                    } else {
                        actor.hideFrom(f.player)
                        actor.showTo(f.player)
                        f.connection.submit(listOf(actor.frame()))
                    }
                    // Deliver the replacement before Paper runs the pending input callback.
                    f.backend.runAll()
                    (f.connection.lease(actor.entityId) === admitted) shouldBe false
                    f.scheduler.tick()
                    calls shouldBe 0
                    // The rejected callback still releases its pending gate; a fresh click works.
                    f.inputTime += 100_000_000
                    val current = requireNotNull(f.connection.admitInput(actor.entityId))
                    f.owner.enqueueInput(f.player.uniqueId, f.connection, actor.entityId, current, PacketNpcInteraction.INTERACT)
                    f.scheduler.tick()
                    calls shouldBe 1
                }
            }
        }
    }

    "scale bounds native pose dimensions and interaction hitbox agree" {
        MockBukkitTestRuntime.open().use {
            NpcFixture().use { f ->
                val actor = f.spawn()
                actor.scale shouldBe 1.0
                for (invalid in listOf(.24, 2.01, Double.NaN, Double.POSITIVE_INFINITY)) {
                    runCatching { actor.scale = invalid }.isFailure shouldBe true
                }
                actor.scale shouldBe 1.0
                actor.scale = .5
                actor.height shouldBe .9
                actor.eyeHeight shouldBe .81
                actor.pose = PacketNpcPose.CROUCHING
                actor.height shouldBe .75
                actor.eyeHeight shouldBe .635
                actor.pose = PacketNpcPose.SLEEPING
                actor.height shouldBe .2
                actor.eyeHeight shouldBe .2
                actor.pose = PacketNpcPose.STANDING
                f.refresh()
                var calls = 0
                f.owner.onInteract = { _, _, _ -> calls++ }
                fun click() = f.owner.interact(f.player.uniqueId, f.connection, actor.entityId,
                    requireNotNull(f.connection.lease(actor.entityId)), PacketNpcInteraction.INTERACT)
                click() // The normal adult eye ray passes over the child's head.
                calls shouldBe 0
                f.eye = Location(f.world, .2, 64.6, -2.0)
                click() // Inside the adult width, outside the child's width.
                calls shouldBe 0
                f.eye = Location(f.world, .1, 64.6, -2.0)
                click()
                calls shouldBe 1
                actor.scale = 1.0
                f.refresh()
                f.eye = Location(f.world, .2, 65.62, -2.0)
                click()
                calls shouldBe 2
            }
        }
    }

    "a seated handle preserves feet input and ends its mount on movement pose world change and removal" {
        MockBukkitTestRuntime.open().use {
            NpcFixture().use { f ->
                val actor = f.spawn()
                val feet = actor.location
                actor.sit(feet)
                actor.isSeated shouldBe true
                actor.location shouldBe feet
                actor.pose shouldBe PacketNpcPose.STANDING
                f.refresh()
                val seat = requireNotNull(actor.frame().seat)
                f.transport.ownedIds shouldBe setOf(actor.entityId)
                var calls = 0
                f.owner.onInteract = { _, _, _ -> calls++ }
                f.owner.interact(f.player.uniqueId, f.connection, actor.entityId,
                    requireNotNull(f.connection.lease(actor.entityId)), PacketNpcInteraction.INTERACT)
                calls shouldBe 1
                actor.teleport(feet.clone().add(.1, 0.0, 0.0))
                actor.isSeated shouldBe false
                actor.frame().seat shouldBe seat
                actor.sit(feet)
                actor.pose = PacketNpcPose.SLEEPING
                actor.isSeated shouldBe false
                actor.sit(feet)
                actor.teleport(Location(mockk { every { uid } returns UUID.randomUUID() }, 0.0, 64.0, 0.0))
                actor.isSeated shouldBe false
                actor.remove()
                actor.isSeated shouldBe false
                runCatching { actor.sit(feet) }.isFailure shouldBe true
            }
        }
    }

    "scene observations require explicit opt-in and detach only live default-visible actor appearance" {
        MockBukkitTestRuntime.open().use { paper ->
            PaperSceneObservationServiceProvider(paper.createSimplePlugin("ArcCore")).use { observations ->
                NpcFixture(observations).use { f ->
                    val actor = f.owner.spawnPlayer(Location(f.world, 0.0, 64.0, 0.0),
                        PacketNpcProfile(UUID.randomUUID(), "public", "dGV4dHVyZQ==", null))
                    actor.sceneLabel = "Анна"
                    actor.scale = .75
                    actor.sit(actor.location)
                    actor.bodyYaw = 45f; actor.headYaw = 90f; actor.pitch = 15f
                    val helmet = ItemStack(Material.LEATHER_HELMET).apply {
                        itemMeta = (itemMeta as LeatherArmorMeta).apply {
                            @Suppress("DEPRECATION")
                            setCustomModelData(25)
                            setColor(org.bukkit.Color.fromRGB(0x123456))
                        }
                    }
                    actor.setEquipment(EquipmentSlot.HEAD, helmet)
                    val bounds = ArcSceneBounds(f.world.uid, -1.0, 63.0, -1.0, 1.0, 66.0, 1.0)
                    observations.entities(bounds, 512) shouldBe emptyList()
                    f.owner.enableSceneObservation(); f.owner.enableSceneObservation()
                    val snapshot = observations.entities(bounds, 1).single()
                    snapshot.name shouldBe "Анна"
                    snapshot.skinTexture shouldBe "dGV4dHVyZQ=="
                    snapshot.bodyYaw shouldBe 45f
                    snapshot.headYaw shouldBe 90f
                    snapshot.scale shouldBe .75
                    snapshot.seated shouldBe true
                    snapshot.equipment.single().material shouldBe "minecraft:leather_helmet"
                    snapshot.equipment.single().customModelData shouldBe 25
                    snapshot.equipment.single().tint shouldBe 0x123456
                    actor.setEquipment(EquipmentSlot.HEAD, ItemStack(Material.AIR))
                    snapshot.equipment.size shouldBe 1
                    observations.entities(bounds, 1).single().equipment shouldBe emptyList()
                    actor.isVisibleByDefault = false
                    actor.showTo(f.player)
                    observations.entities(bounds, 512) shouldBe emptyList()
                    actor.isVisibleByDefault = true
                    actor.teleport(Location(f.world, 1.0, 64.0, 0.0))
                    observations.entities(bounds, 512) shouldBe emptyList()
                    actor.teleport(Location(f.world, 0.0, 64.0, 0.0))
                    actor.remove()
                    observations.entities(bounds, 512) shouldBe emptyList()
                    f.close()
                    observations.registerEntitySource(f.plugin, "test-npcs") { _, _ -> emptyList() }.close()
                }
                observations.entities(ArcSceneBounds(UUID.randomUUID(), 0.0, 0.0, 0.0, 1.0, 1.0, 1.0), 512) shouldBe emptyList()
            }
        }
    }
})

private class NpcFixture(observations: ArcSceneObservationService? = null) : AutoCloseable {
    val plugin = mockk<Plugin>(relaxed = true)
    val backend = NpcBackend()
    private var channel = Any()
    var inputTime = 0L
    var connection = PacketNpcConnection(channel, Logger.getAnonymousLogger(), backend) { inputTime }
    val world = mockk<World>()
    val player = mockk<Player>()
    var playerWorld: World = world
    var online = true
    var chunks = setOf(0L)
    var eye = Location(world, 0.0, 65.62, -2.0)
    var wall = false
    val transport = mockk<PacketNpcTransport>(relaxed = true)
    val scheduler = TestTaskScheduler()
    val scope = LifecycleTaskScope(scheduler)
    val cleanup = NpcCleanup()
    val audience = NpcAudience()
    private val playerId = UUID.randomUUID()
    private val worldId = UUID.randomUUID()
    val viewer: PacketDisplayViewer
    val owner: PaperPacketNpcs

    init {
        every { world.uid } returns worldId
        every { world.rayTraceBlocks(any(), any(), any(), FluidCollisionMode.NEVER, true) } answers {
            // Honour the requested range; this is a wall before the actor, not a permissive fixture.
            val start = firstArg<Location>()
            val direction = secondArg<Vector>()
            val maximum = thirdArg<Double>()
            if (wall && maximum >= 0.5) RayTraceResult(start.toVector().add(direction.clone().multiply(0.5))) else null
        }
        every { player.uniqueId } returns playerId
        every { player.entityId } returns 900
        every { player.isOnline } answers { online }
        every { player.isValid } returns true
        every { player.isDead } returns false
        every { player.world } answers { playerWorld }
        every { player.eyeLocation } answers { eye.clone() }
        every { player.location } answers { eye.clone().subtract(0.0, 1.62, 0.0) }
        every { player.sentChunkKeys } answers { chunks }
        every { player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE) } returns null
        every { plugin.isEnabled } returns true
        every { plugin.logger } returns Logger.getAnonymousLogger()
        every { plugin.server.servicesManager.load(ArcSceneObservationService::class.java) } returns observations
        every { plugin.server.getPlayer(playerId) } returns player
        val ownedIds = mutableSetOf<Int>()
        every { transport.ownedIds } returns ownedIds
        var nextId = 1
        every { transport.nextEntityId() } answers { nextId++ }
        every { transport.connection(player) } answers { connection }
        viewer = PacketDisplayViewer(player, playerId, worldId, 900, emptySet(), 0.0, 64.0, -2.0, setOf(0L), height = 1.8)
        audience.values = listOf(viewer)
        owner = PaperPacketNpcs(plugin, "test-npcs", scope, audience,
            BudgetedTaskQueue(scope, maximumNanos = Long.MAX_VALUE), true, cleanup, null, transport)
    }

    fun spawn() = owner.spawnPlayer(Location(world, 0.0, 64.0, 0.0), PacketNpcProfile(UUID.randomUUID(), "resident", null, null))
    fun replaceConnection() { channel = Any(); connection = PacketNpcConnection(channel, Logger.getAnonymousLogger(), backend) { inputTime } }
    fun refresh() {
        val result = owner.refresh()
        var ticks = 0
        while (!result.isDone) { check(++ticks < 100); scheduler.tick() }
        result.join()
        backend.runAll()
    }
    override fun close() {
        val done = owner.closeAsync()
        var count = 0
        while (!done.isDone) { check(++count < 100); cleanup.step(); backend.runAll() }
        done.join()
        java.lang.ref.Reference.reachabilityFence(channel)
    }
}

private class NpcAudience : TestPacketDisplayAudience() {
    var values = emptyList<PacketDisplayViewer>()
    override fun checkThread() { org.bukkit.Bukkit.isPrimaryThread() shouldBe true }
    override fun capture() = values
}

private class NpcCleanup : ArcNativeCleanupService {
    private var step: BooleanSupplier? = null
    private var done = CompletableFuture<Void>()
    override fun submit(owner: String, step: BooleanSupplier): CompletionStage<Void> {
        this.step = step
        return done
    }
    fun step() {
        if (step?.asBoolean == true) { step = null; done.complete(null) }
    }
    fun finish() { var count = 0; while (!done.isDone) { check(++count < 100); step() } }
}
