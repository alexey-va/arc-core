package ru.arc.paper.npc

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.event.EventManager
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.manager.player.PlayerManager
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.attribute.Attributes
import com.github.retrooper.packetevents.protocol.ConnectionState
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.settings.PacketEventsSettings
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.*
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl
import io.mockk.every
import io.mockk.mockk
import io.netty.channel.embedded.EmbeddedChannel
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcVisualPacketBudget
import ru.arc.paper.display.PacketDisplayAttachment
import ru.arc.paper.display.PacketDisplayChannelBackend
import ru.arc.paper.display.PacketDisplayContent
import ru.arc.paper.display.PacketDisplayFrame
import ru.arc.paper.display.PacketDisplayMetadata
import ru.arc.paper.display.TestPacketEventsApi
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

class PacketNpcTransportTest : FreeSpec({
    val previousApi = PacketEvents.getAPI()
    beforeSpec { PacketEvents.setAPI(TestPacketEventsApi()) }
    afterSpec { PacketEvents.setAPI(previousApi) }

    "48 player actors add unlisted profiles before spawn, idle sends zero, walking sends only relative move and head look" {
        val backend = NpcBackend()
        val channel = Any()
        val connection = PacketNpcConnection(channel, Logger.getAnonymousLogger(), backend)
        val actors = List(48) { npcFrame(it + 1) }
        connection.submit(actors); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().size shouldBe 48
        backend.writes.filterIsInstance<WrapperPlayServerPlayerInfoUpdate>().forEach { packet ->
            packet.entries.single().isListed shouldBe false
            packet.actions.contains(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED) shouldBe true
            val spawn = backend.writes.indexOfFirst { it is WrapperPlayServerSpawnEntity && it.uuid.orElse(null) == packet.entries.single().profileId }
            (backend.writes.indexOf(packet) < spawn) shouldBe true
        }
        backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().forEach {
            it.entityMetadata.single { value -> value.index == 16 }.value shouldBe 0x7f.toByte()
        }
        backend.writes.filterIsInstance<WrapperPlayServerUpdateAttributes>().size shouldBe 48
        backend.writes.filterIsInstance<WrapperPlayServerUpdateAttributes>().forEach {
            it.properties.single().attribute shouldBe Attributes.SCALE
            it.properties.single().value shouldBe 1.0
        }
        backend.clear()
        repeat(10) { connection.submit(actors); backend.runAll() }
        backend.writes.size shouldBe 0
        val walking = actors.map { it.copy(x = it.x + 0.0625, bodyYaw = 45f, headYaw = 30f, pitch = 5f) }
        connection.submit(walking); backend.runAll()
        backend.writes.size shouldBe 96
        backend.writes.filterIsInstance<WrapperPlayServerEntityRelativeMoveAndRotation>().forEach {
            it.deltaX shouldBe 0.0625
            it.yaw shouldBe 45f
            it.pitch shouldBe 5f
        }
        backend.writes.filterIsInstance<WrapperPlayServerEntityHeadLook>().size shouldBe 48
        backend.clear()
        connection.submit(walking); backend.runAll()
        backend.writes.size shouldBe 0
        java.lang.ref.Reference.reachabilityFence(channel)
    }

    "summon then supplies and resumed walking use the new client delta base without respawning" {
        val backend = NpcBackend()
        val channel = Any()
        val connection = PacketNpcConnection(channel, Logger.getAnonymousLogger(), backend)
        val client = NpcClientPosition12111()
        fun display(frame: PacketNpcFrame) {
            backend.clear()
            connection.submit(listOf(frame)); backend.runAll()
            backend.writes.forEach(client::accept)
        }
        val previous = npcFrame().copy(x = -70.5, y = 71.0, z = -50.5)
        display(previous)
        val lease = connection.lease(previous.entityId)
        val summoned = previous.copy(x = -49.5, y = 71.0, z = -36.5)
        display(summoned)
        client.position shouldBe Vector3d(summoned.x, summoned.y, summoned.z)
        val seeds = ItemStack.builder().type(ItemTypes.WHEAT_SEEDS).amount(1).version(ClientVersion.V_1_21_11).build()
        val supplied = summoned.copy(equipment = mapOf(EquipmentSlot.MAIN_HAND to seeds))
        display(supplied)
        backend.writes.filterIsInstance<WrapperPlayServerEntityEquipment>().size shouldBe 1
        client.position shouldBe Vector3d(summoned.x, summoned.y, summoned.z)
        val walking = supplied.copy(x = supplied.x + .125, z = supplied.z + .0625, bodyYaw = 45f)
        display(walking)
        backend.writes.filterIsInstance<WrapperPlayServerEntityRelativeMoveAndRotation>().size shouldBe 1
        // With ENTITY_TELEPORT this decodes against previous, visibly rewinding 25 blocks
        // despite the authoritative actor continuing normally from the summoned location.
        client.position shouldBe Vector3d(walking.x, walking.y, walking.z)
        client.deltaBase shouldBe client.position
        (connection.lease(walking.entityId) === lease) shouldBe true
        java.lang.ref.Reference.reachabilityFence(channel)
    }

    "pressure retains one latest frame and one retry without historical movement or gesture replay" {
        val backend = NpcBackend()
        val channel = Any()
        val connection = PacketNpcConnection(channel, Logger.getAnonymousLogger(), backend)
        val first = npcFrame()
        connection.submit(listOf(first)); backend.runAll(); backend.clear()
        backend.ordinary = false
        connection.submit(listOf(first.copy(x = 1.0, swing = 1)))
        backend.runOne()
        repeat(100) { index -> connection.submit(listOf(first.copy(x = 2.0 + index / 4096.0, swing = 100L + index))) }
        backend.tasks.size shouldBe 1
        backend.ordinary = true
        backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerEntityRelativeMoveAndRotation>().single().deltaX shouldBe 2.0 + 99 / 4096.0
        backend.writes.filterIsInstance<WrapperPlayServerEntityAnimation>().size shouldBe 1
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().size shouldBe 0
        java.lang.ref.Reference.reachabilityFence(channel)
    }

    "native scale is sent after spawn and only the latest changed value updates the same actor" {
        val backend = NpcBackend()
        val channel = Any()
        val connection = PacketNpcConnection(channel, Logger.getAnonymousLogger(), backend)
        val child = npcFrame().copy(scale = .5)
        connection.submit(listOf(child)); backend.runAll()
        val scale = backend.writes.filterIsInstance<WrapperPlayServerUpdateAttributes>().single()
        scale.entityId shouldBe child.entityId
        scale.properties.single().attribute shouldBe Attributes.SCALE
        scale.properties.single().value shouldBe .5
        scale.properties.single().modifiers shouldBe emptyList()
        (backend.writes.indexOfFirst { it is WrapperPlayServerSpawnEntity } < backend.writes.indexOf(scale)) shouldBe true
        val admitted = connection.lease(child.entityId)
        backend.clear()
        connection.submit(listOf(child)); backend.runAll()
        backend.writes shouldBe emptyList()
        backend.ordinary = false
        connection.submit(listOf(child.copy(scale = .75))); backend.runOne()
        connection.submit(listOf(child.copy(scale = 1.0)))
        connection.submit(listOf(child.copy(scale = 1.25)))
        backend.ordinary = true
        backend.runAll()
        backend.writes.size shouldBe 1
        backend.writes.filterIsInstance<WrapperPlayServerUpdateAttributes>().single().properties.single().value shouldBe 1.25
        (connection.lease(child.entityId) === admitted) shouldBe true
        backend.clear()
        connection.submit(listOf(child.copy(scale = 1.25)), resetAll = true); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerUpdateAttributes>().single().properties.single().value shouldBe 1.25
        java.lang.ref.Reference.reachabilityFence(channel)
    }

    "moving actors share limited admission fairly while every pending capture advances" {
        val backend = NpcBackend()
        val channel = Any()
        val connection = PacketNpcConnection(channel, Logger.getAnonymousLogger(), backend)
        val actors = List(48) { npcFrame(it + 1) }
        connection.submit(actors); backend.runAll(); backend.clear()
        repeat(6) { tick ->
            backend.remaining = 8
            connection.submit(actors.map { it.copy(x = (tick + 1) / 16.0) })
            backend.runOne()
            backend.tasks.size shouldBe 1
        }
        backend.writes.filterIsInstance<WrapperPlayServerEntityRelativeMoveAndRotation>()
            .map { it.entityId }.toSet().size shouldBe 48
        backend.remaining = Int.MAX_VALUE
        backend.runAll()
        java.lang.ref.Reference.reachabilityFence(channel)
    }

    "sleep bed, waking, equipment, swings and hurt are independent state deltas" {
        val backend = NpcBackend()
        val channel = Any()
        val connection = PacketNpcConnection(channel, Logger.getAnonymousLogger(), backend)
        val first = npcFrame()
        connection.submit(listOf(first)); backend.runAll(); backend.clear()
        val sword = ItemStack.builder().type(ItemTypes.IRON_SWORD).amount(1).version(ClientVersion.V_1_21_11).build()
        val sleeping = first.copy(pose = PacketNpcPose.SLEEPING, bed = PacketNpcBed(1, 64, 2),
            equipment = mapOf(EquipmentSlot.MAIN_HAND to sword), swing = 1, hurt = 1)
        connection.submit(listOf(sleeping)); backend.runAll()
        val metadata = backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().single()
        metadata.entityMetadata.single { it.index == 6 }.value shouldBe EntityPose.SLEEPING
        (metadata.entityMetadata.single { it.index == 14 }.value as Optional<*>).isPresent shouldBe true
        backend.writes.filterIsInstance<WrapperPlayServerEntityEquipment>().single().equipment.single().slot shouldBe EquipmentSlot.MAIN_HAND
        backend.writes.filterIsInstance<WrapperPlayServerEntityAnimation>().size shouldBe 1
        backend.writes.filterIsInstance<WrapperPlayServerHurtAnimation>().size shouldBe 1
        backend.clear()
        connection.submit(listOf(sleeping.copy(pose = PacketNpcPose.STANDING, bed = null))); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().single().entityMetadata.single { it.index == 14 }.value shouldBe Optional.empty<Any>()
        java.lang.ref.Reference.reachabilityFence(channel)
    }

    "visibility revocation cancels an unspawned stale frame and chunk reset replays an identical actor" {
        val backend = NpcBackend()
        val channel = Any()
        val connection = PacketNpcConnection(channel, Logger.getAnonymousLogger(), backend)
        val frame = npcFrame()
        connection.submit(listOf(frame)); connection.revoke(frame.entityId); backend.runAll()
        backend.writes.size shouldBe 0
        connection.submit(listOf(frame)); backend.runAll()
        val oldLease = connection.lease(frame.entityId)!!
        backend.clear()
        backend.writable = false
        connection.submit(listOf(frame), setOf(frame.chunkKey))
        backend.runOne()
        oldLease.isActive() shouldBe false
        connection.submit(listOf(frame))
        backend.writable = true
        backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().size shouldBe 1
        backend.writes.filterIsInstance<WrapperPlayServerPlayerInfoRemove>().size shouldBe 1
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().size shouldBe 1
        (connection.lease(frame.entityId) !== oldLease) shouldBe true
        java.lang.ref.Reference.reachabilityFence(channel)
    }

    "new connection replays current state and unseen stale retired IDs cannot admit input or flood queued callbacks" {
        val backend = NpcBackend()
        val channel = Any()
        var now = 0L
        val connection = PacketNpcConnection(channel, Logger.getAnonymousLogger(), backend) { now }
        val frame = npcFrame()
        connection.admitInput(frame.entityId) shouldBe null
        connection.submit(listOf(frame)); backend.runAll()
        connection.admitInput(frame.entityId) shouldBe connection.lease(frame.entityId)
        repeat(100) { connection.admitInput(frame.entityId) shouldBe null }
        connection.finishInput()
        connection.admitInput(frame.entityId) shouldBe null
        now += 100_000_000
        connection.admitInput(frame.entityId) shouldBe connection.lease(frame.entityId)
        connection.finishInput()
        connection.revokeAll()
        now += 100_000_000
        connection.admitInput(frame.entityId) shouldBe null
        connection.disconnect()
        backend.clear()
        val replacementChannel = Any()
        val replacement = PacketNpcConnection(replacementChannel, Logger.getAnonymousLogger(), backend)
        replacement.submit(listOf(frame.copy(x = 3.0))); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().single().position.x shouldBe 3.0
        connection.matches(replacementChannel) shouldBe false
        java.lang.ref.Reference.reachabilityFence(channel)
        java.lang.ref.Reference.reachabilityFence(replacementChannel)
    }

    "admitted display children retire before NPC and a delayed display spawn cannot outlive its carrier" {
        val backend = NpcBackend()
        val channel = Any()
        val connection = PacketNpcConnection(channel, Logger.getAnonymousLogger(), backend)
        val actor = npcFrame()
        connection.submit(listOf(actor)); backend.runAll()
        val lease = connection.lease(actor.entityId)!!
        val displays = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val child = PacketDisplayFrame(200, UUID(0, 200), 0.0, 65.8, 0.0, 0f, 0f,
            PacketDisplayMetadata(PacketDisplayContent.Text(Component.text("Resident"))), actor.worldId, actor.entityId, lease)
        displays.submitAsync(listOf(child), desiredPassengers = mapOf(actor.entityId to listOf(child.entityId)),
            nativePassengerSnapshots = mapOf(actor.entityId to emptyList()), liveVehicleIds = setOf(actor.entityId))
        backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerSetPassengers>().last().passengers.toList() shouldBe listOf(child.entityId)
        backend.clear()
        val closed = connection.closeAsync()
        backend.runAll()
        closed.join()
        val destroyed = backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().flatMap { it.entityIds.toList() }
        (destroyed.indexOf(child.entityId) < destroyed.indexOf(actor.entityId)) shouldBe true
        backend.clear()
        displays.submitAsync(listOf(child)); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().size shouldBe 0
        java.lang.ref.Reference.reachabilityFence(channel)
    }

    "a partial profile write retires possible actor and profile before making the connection replaceable" {
        val backend = NpcBackend().apply { failAfter = 1 }
        val channel = Any()
        val connection = PacketNpcConnection(channel, Logger.getAnonymousLogger(), backend)
        connection.submit(listOf(npcFrame()))
        backend.runAll()
        connection.isUsable shouldBe false
        connection.isDrained shouldBe true
        connection.closeAsync().isCompletedExceptionally shouldBe true
        backend.writes.filterIsInstance<WrapperPlayServerPlayerInfoUpdate>().size shouldBe 1
        backend.writes.filterIsInstance<WrapperPlayServerPlayerInfoRemove>().size shouldBe 1
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().size shouldBe 1
        java.lang.ref.Reference.reachabilityFence(channel)
    }

    "disconnect from an old channel does not evict a replacement connection for the same player" {
        MockBukkitTestRuntime.open().use {
            val prior = PacketEvents.getAPI()
            val oldChannel = EmbeddedChannel()
            val newChannel = EmbeddedChannel()
            var activeChannel = oldChannel
            val id = UUID.randomUUID()
            val player = mockk<Player> { every { uniqueId } returns id }
            val manager = mockk<PlayerManager> { every { getChannel(player) } answers { activeChannel } }
            val events = EventManager()
            val api = mockk<PacketEventsAPI<Any>> {
                every { playerManager } returns manager
                every { eventManager } returns events
                every { nettyManager } returns NettyManagerImpl()
                every { serverManager } returns ServerManager { ServerVersion.V_1_21_11 }
                every { settings } returns PacketEventsSettings()
            }
            val plugin = mockk<Plugin>(relaxed = true) {
                every { name } returns "TestNpcTransport"
                every { logger } returns Logger.getAnonymousLogger()
                every { server.servicesManager.load(ArcVisualPacketBudget::class.java) } returns mockk(relaxed = true)
            }
            PacketEvents.setAPI(api)
            try {
                PacketNpcTransport(plugin, "test-npcs") { _, _, _, _, _ -> }.use { transport ->
                    val old = requireNotNull(transport.connection(player))
                    activeChannel = newChannel
                    val current = requireNotNull(transport.connection(player))
                    (current === old) shouldBe false
                    events.callEvent(UserDisconnectEvent(User(oldChannel, ConnectionState.PLAY,
                        ClientVersion.V_1_21_11, UserProfile(id, "viewer"))))
                    current.isUsable shouldBe true
                    (transport.connection(player) === current) shouldBe true
                    events.callEvent(UserDisconnectEvent(User(newChannel, ConnectionState.PLAY,
                        ClientVersion.V_1_21_11, UserProfile(id, "viewer"))))
                    current.isUsable shouldBe false
                }
            } finally {
                PacketEvents.setAPI(prior)
                oldChannel.finishAndReleaseAll()
                newChannel.finishAndReleaseAll()
            }
        }
    }

    "denied child churn stays bounded while admitted children and partial writes remain eligible for cleanup" {
        val backend = NpcBackend()
        val channel = Any()
        val connection = PacketNpcConnection(channel, Logger.getAnonymousLogger(), backend)
        val actor = npcFrame()
        connection.submit(listOf(actor)); backend.runAll()
        val lease = requireNotNull(connection.lease(actor.entityId))
        val displays = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        fun child(id: Int) = PacketDisplayFrame(id, UUID(0, id.toLong()), 0.0, 65.8, 0.0, 0f, 0f,
            PacketDisplayMetadata(PacketDisplayContent.Text(Component.text("Resident"))), actor.worldId, actor.entityId, lease)
        backend.ordinary = false
        repeat(100) { index ->
            displays.submit(listOf(child(200 + index))); backend.runOne()
            lease.childIds().size shouldBe 0
            displays.submit(emptyList()); backend.runAll()
            lease.childIds().size shouldBe 0
        }
        backend.ordinary = true
        val admitted = child(400)
        displays.submit(listOf(admitted)); backend.runAll()
        lease.childIds().toSet() shouldBe setOf(admitted.entityId)
        backend.ordinary = false
        val updated = admitted.copy(metadata = PacketDisplayMetadata(PacketDisplayContent.Text(Component.text("Changed"))))
        displays.submit(listOf(updated)); backend.runOne()
        lease.childIds().toSet() shouldBe setOf(admitted.entityId)
        displays.submit(listOf(admitted)); backend.runAll()
        backend.ordinary = true
        backend.clear()
        backend.failAfter = 1
        val partial = child(401)
        displays.submit(listOf(admitted, partial)); backend.runOne()
        lease.childIds().toSet() shouldBe setOf(admitted.entityId, partial.entityId)
        backend.clear()
        connection.closeAsync(); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().flatMap { it.entityIds.toList() }.toSet() shouldBe
            setOf(actor.entityId, admitted.entityId, partial.entityId)
        java.lang.ref.Reference.reachabilityFence(channel)
    }
})

internal fun npcFrame(id: Int = 1) = PacketNpcFrame(
    id, PacketNpcProfile(UUID(0, id.toLong()), "npc$id", null, null), UUID(0, 99),
    0.0, 64.0, 0.0, 0f, 0f, 0f, PacketNpcPose.STANDING, null, emptyMap(), 0, 0, AtomicBoolean(true),
)

/** The position branches of the pinned 1.21.11 ClientPacketListener and VecDeltaCodec. */
private class NpcClientPosition12111 {
    var position: Vector3d = Vector3d.zero()
        private set
    var deltaBase: Vector3d = Vector3d.zero()
        private set

    fun accept(packet: PacketWrapper<*>) {
        when (packet) {
            is WrapperPlayServerSpawnEntity -> { position = packet.position; deltaBase = position }
            is WrapperPlayServerEntityPositionSync -> { position = packet.values.position; deltaBase = position }
            is WrapperPlayServerEntityTeleport -> position = packet.position // Deliberately does not update VecDeltaCodec.
            is WrapperPlayServerEntityRelativeMoveAndRotation -> {
                fun decode(base: Double, delta: Double): Double = if (delta == 0.0) base
                    else (Math.round(base * 4096.0) + Math.round(delta * 4096.0)) / 4096.0
                position = Vector3d(decode(deltaBase.x, packet.deltaX), decode(deltaBase.y, packet.deltaY),
                    decode(deltaBase.z, packet.deltaZ))
                deltaBase = position
            }
        }
    }
}

internal class NpcBackend : PacketDisplayChannelBackend {
    val tasks = ArrayDeque<() -> Unit>()
    val writes = mutableListOf<PacketWrapper<*>>()
    var ordinary = true
    var remaining = Int.MAX_VALUE
    var writable = true
    var failAfter: Int? = null
    override fun isOpen(channel: Any) = true
    override fun enqueue(channel: Any, task: () -> Unit) { tasks.add(task) }
    override fun retry(channel: Any, task: () -> Unit) { tasks.add(task) }
    override fun flush(channel: Any) = Unit
    override fun tryWrite(channel: Any, packets: List<PacketWrapper<*>>, cleanup: Boolean): Boolean {
        if (!writable || (!cleanup && (!ordinary || remaining == 0))) return false
        if (!cleanup) remaining--
        for (packet in packets) {
            if (failAfter == writes.size) { failAfter = null; error("Injected partial write") }
            writes.add(packet)
        }
        return true
    }
    fun runOne() { tasks.removeFirst().invoke() }
    fun runAll() { var count = 0; while (tasks.isNotEmpty()) { check(++count <= 1_000); runOne() } }
    fun clear() { writes.clear() }
}
