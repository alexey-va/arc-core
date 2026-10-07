package ru.arc.paper.display

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl
import net.kyori.adventure.text.Component
import java.util.UUID
import java.util.logging.Logger

class PacketDisplayTransportTest : FreeSpec({
    val previousApi = PacketEvents.getAPI()
    beforeSpec { PacketEvents.setAPI(TestPacketEventsApi()) }
    afterSpec { PacketEvents.setAPI(previousApi) }

    "rate limiting progressively spawns an atomic entity and keeps only the latest frame" {
        val backend = RecordingPacketBackend().apply { ordinaryTransactions = 1 }
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        connection.submit(listOf(frame(id = 1), frame(id = 2), frame(id = 3)))
        backend.tasks.removeFirst().invoke()
        backend.writes.map { it::class.java.simpleName } shouldBe listOf(
            "WrapperPlayServerSpawnEntity", "WrapperPlayServerEntityMetadata",
        )
        backend.tasks.size shouldBe 1

        // The unsent second entity disappears; no stale spawn is replayed.
        repeat(100) { connection.submit(listOf(frame(id = 1), frame(id = 3, x = it.toDouble()))) }
        backend.tasks.size shouldBe 1
        backend.ordinaryTransactions = 1
        backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().map { it.entityId } shouldBe listOf(1, 3)
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().last().position.x shouldBe 99.0
    }

    "rate exhaustion never prevents removal and a blocked channel retries cleanup after owner close" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        connection.submit(listOf(frame()))
        backend.runAll()
        backend.clear()
        backend.ordinaryTransactions = 0
        backend.writable = false
        connection.submit(emptyList())
        backend.tasks.removeFirst().invoke()
        backend.writes.size shouldBe 0
        backend.tasks.size shouldBe 1
        // No further Bukkit submit is needed to finish closing the owner.
        backend.writable = true
        backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single().entityIds.toList() shouldBe listOf(100)
        backend.tasks.size shouldBe 0
    }

    "a partial replay does not repeatedly destroy already respawned entities" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val frames = listOf(frame(id = 1), frame(id = 2))
        connection.submit(frames)
        backend.runAll()
        backend.clear()
        backend.ordinaryTransactions = 1
        connection.submit(frames, resetAll = true)
        backend.tasks.removeFirst().invoke()
        backend.ordinaryTransactions = 1
        backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().size shouldBe 1
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().map { it.entityId } shouldBe listOf(1, 2)
    }

    "deferred animation rotates admission so later entities can progress" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        connection.submit((1..3).map { frame(id = it) })
        backend.runAll()
        backend.clear()
        repeat(3) { tick ->
            backend.ordinaryTransactions = 1
            connection.submit((1..3).map { frame(id = it, x = tick + 1.0) })
            backend.tasks.removeFirst().invoke()
        }
        backend.writes.filterIsInstance<WrapperPlayServerEntityTeleport>().map { it.entityId } shouldBe listOf(1, 2, 3)
        backend.ordinaryTransactions = Int.MAX_VALUE
        backend.runAll()
    }

    "a pending attachment keeps one latest frame and one drain" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)

        connection.submit(listOf(frame(x = 1.0)))
        connection.submit(listOf(frame(x = 2.0)))
        backend.tasks.size shouldBe 1

        backend.runAll()
        val spawn = backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().single()
        spawn.position.x shouldBe 2.0
        backend.flushes shouldBe 1
    }

    "a reveal snap survives a coalesced latest frame and restores normal interpolation" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val hidden = animatedFrame(content = itemContent(TestItem.AIR), yTransform = -1f, duration = 2)

        connection.submit(listOf(hidden))
        backend.runAll()
        backend.clear()

        val reveal = animatedFrame(content = itemContent(TestItem.DIAMOND), yTransform = 0.5f, duration = 0)
        val latest = reveal.copy(
            metadata = reveal.metadata.copy(
                transform = reveal.metadata.transform.copy(translation = DisplayVector(0f, 0.75f, 0f)),
                interpolationDuration = 2,
            ),
        )
        connection.submit(listOf(reveal))
        connection.submit(listOf(latest))
        backend.runAll()

        val snapped = backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().single()
        snapped.metadataValue(9) shouldBe 0
        (snapped.metadataValue(11) as com.github.retrooper.packetevents.util.Vector3f).y shouldBe 0.75f
        snapped.metadataValue(23) shouldBe (latest.metadata.content as PacketDisplayContent.Item).item

        backend.clear()
        val next = latest.copy(
            metadata = latest.metadata.copy(
                transform = latest.metadata.transform.copy(translation = DisplayVector(0f, 1f, 0f)),
                interpolationDuration = 2,
            ),
        )
        connection.submit(listOf(next))
        backend.runAll()

        val restored = backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().single()
        restored.metadataValue(9) shouldBe 2
        (restored.metadataValue(11) as com.github.retrooper.packetevents.util.Vector3f).y shouldBe 1f
    }

    "a submit during snap delivery uses the effective in-flight snapshot" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val hidden = animatedFrame(content = itemContent(TestItem.AIR), yTransform = -1f, duration = 2)
        connection.submit(listOf(hidden))
        backend.runAll()
        backend.clear()

        val reveal = animatedFrame(content = itemContent(TestItem.DIAMOND), yTransform = 0.5f, duration = 0)
        val next = reveal.copy(
            metadata = reveal.metadata.copy(
                transform = reveal.metadata.transform.copy(translation = DisplayVector(0f, 0.75f, 0f)),
                interpolationDuration = 2,
            ),
        )
        backend.onWrite = { packet ->
            if (packet is WrapperPlayServerEntityMetadata && packet.metadataValue(9) == 0) {
                backend.onWrite = null
                connection.submit(listOf(next))
            }
        }
        connection.submit(listOf(reveal))
        backend.runAll()

        val updates = backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>()
        updates.map { it.metadataValue(9) } shouldBe listOf(0, 2)
        updates.map {
            (it.metadataValue(11) as com.github.retrooper.packetevents.util.Vector3f).y
        } shouldBe listOf(0.5f, 0.75f)
    }

    "a recycle snap survives skipped air frames but does not survive removal" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val visible = animatedFrame(content = itemContent(TestItem.DIAMOND), yTransform = 1f, duration = 2)
        connection.submit(listOf(visible))
        backend.runAll()
        backend.clear()

        val recycle = animatedFrame(content = itemContent(TestItem.AIR), yTransform = -1f, duration = 0)
        val shiftedAir = recycle.copy(
            metadata = recycle.metadata.copy(
                transform = recycle.metadata.transform.copy(translation = DisplayVector(0f, -1.5f, 0f)),
                interpolationDuration = 2,
            ),
        )
        val latest = animatedFrame(content = itemContent(TestItem.EMERALD), yTransform = 0.25f, duration = 2)
        connection.submit(listOf(recycle))
        connection.submit(listOf(shiftedAir))
        connection.submit(listOf(latest))
        backend.runAll()

        val snapped = backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().single()
        snapped.metadataValue(9) shouldBe 0
        (snapped.metadataValue(11) as com.github.retrooper.packetevents.util.Vector3f).y shouldBe 0.25f
        snapped.metadataValue(23) shouldBe (latest.metadata.content as PacketDisplayContent.Item).item

        backend.clear()
        connection.submit(listOf(recycle))
        connection.submit(emptyList())
        backend.runAll()

        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single()
            .entityIds.toList() shouldBe listOf(visible.entityId)
        backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().size shouldBe 0
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().size shouldBe 0
    }

    "a changed UUID reuses its numeric ID only after destroy and spawn" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val old = animatedFrame(content = PacketDisplayContent.Block(1), yTransform = 0f, duration = 2)
        connection.submit(listOf(old))
        backend.runAll()
        backend.clear()

        val snap = old.copy(
            metadata = old.metadata.copy(
                transform = old.metadata.transform.copy(translation = DisplayVector(0f, 1f, 0f)),
                interpolationDuration = 0,
            ),
        )
        val replacement = old.copy(
            uuid = UUID.randomUUID(),
            metadata = old.metadata.copy(
                transform = old.metadata.transform.copy(translation = DisplayVector(0f, 2f, 0f)),
                interpolationDuration = 2,
            ),
        )
        connection.submit(listOf(snap))
        connection.submit(listOf(replacement))
        backend.runAll()

        backend.writes.first() as WrapperPlayServerDestroyEntities
        val spawn = backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().single()
        spawn.entityId shouldBe old.entityId
        spawn.uuid.get() shouldBe replacement.uuid
        val spawnMetadata = backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().single()
        spawnMetadata.metadataValue(9) shouldBe 2
        (spawnMetadata.metadataValue(11) as com.github.retrooper.packetevents.util.Vector3f).y shouldBe 2f
    }

    "a changed display kind does not inherit a pending snap" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val old = animatedFrame(content = PacketDisplayContent.Block(1), yTransform = 0f, duration = 2)
        connection.submit(listOf(old))
        backend.runAll()
        backend.clear()

        val snap = old.copy(
            metadata = old.metadata.copy(
                transform = old.metadata.transform.copy(translation = DisplayVector(0f, 1f, 0f)),
                interpolationDuration = 0,
            ),
        )
        val replacement = animatedFrame(
            id = old.entityId,
            uuid = old.uuid,
            content = itemContent(TestItem.EMERALD),
            yTransform = 2f,
            duration = 2,
        )
        connection.submit(listOf(snap))
        connection.submit(listOf(replacement))
        backend.runAll()

        backend.writes.first() as WrapperPlayServerDestroyEntities
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().single().entityId shouldBe old.entityId
        backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().single().metadataValue(9) shouldBe 2
    }

    "unchanged zero-duration frames do not schedule packets" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val display = animatedFrame(content = itemContent(TestItem.DIAMOND), yTransform = 0f, duration = 0)
        connection.submit(listOf(display))
        backend.runAll()
        backend.clear()

        connection.submit(listOf(display))

        backend.tasks.size shouldBe 0
        backend.writes.size shouldBe 0
        backend.flushes shouldBe 0
    }

    "a submit racing an in-flight drain is not lost by the unchanged fast path" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val initial = frame(x = 0.0)

        connection.submit(listOf(initial))
        backend.runAll()
        backend.clear()

        backend.onWrite = { packet ->
            if (packet is WrapperPlayServerEntityTeleport) connection.submit(listOf(initial))
        }
        connection.submit(listOf(initial.copy(x = 1.0)))
        backend.runAll()

        backend.writes.filterIsInstance<WrapperPlayServerEntityTeleport>().map { it.position.x } shouldBe listOf(1.0, 0.0)
    }

    "a chunk reset destroys and replays retained IDs" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val display = frame(x = 4.0)

        connection.submit(listOf(display))
        backend.runAll()
        backend.clear()

        connection.submit(listOf(display), resetChunks = setOf(display.chunkKey))
        backend.runAll()

        (backend.writes.first() is WrapperPlayServerDestroyEntities) shouldBe true
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single().entityIds.toList() shouldBe listOf(display.entityId)
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().single().entityId shouldBe display.entityId
        backend.flushes shouldBe 1
    }

    "coordinate-only updates send a teleport and transformation updates restart delay" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val initial = frame(x = 1.0, content = PacketDisplayContent.Item(testItem(), 0))

        connection.submit(listOf(initial))
        backend.runAll()
        backend.clear()

        connection.submit(listOf(initial.copy(x = 2.0)))
        backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerEntityTeleport>().size shouldBe 1
        backend.writes.none { it is WrapperPlayServerEntityMetadata } shouldBe true

        backend.clear()
        val transformed = initial.copy(
            metadata = initial.metadata.copy(
                transform = initial.metadata.transform.copy(
                    translation = DisplayVector(0.5f, 0f, 0f),
                ),
                interpolationDelay = 0,
            ),
        )
        connection.submit(listOf(transformed))
        backend.runAll()
        val indexes = backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>()
            .single().entityMetadata.map { it.index }
        indexes shouldContain 8
        indexes shouldContain 11
    }

    "mount waits for atomic spawn metadata, follows with metadata only, and detaches before settled teleport" {
        val backend = RecordingPacketBackend()
        val state = PacketDisplayPassengerState()
        val connection = PacketDisplayAttachment(Any(), Logger.getAnonymousLogger(), backend, state)
        val mounted = frame(x = 1.0).copy(attachmentVehicleId = 77, worldId = UUID.randomUUID())
        connection.submit(
            listOf(mounted), desiredPassengers = mapOf(77 to listOf(mounted.entityId)),
            nativePassengerSnapshots = mapOf(77 to listOf(11)), liveVehicleIds = setOf(77),
        )
        backend.runAll()
        backend.writes.map { it::class.java.simpleName } shouldBe listOf(
            "WrapperPlayServerSpawnEntity", "WrapperPlayServerEntityMetadata", "WrapperPlayServerSetPassengers",
        )
        backend.writes.filterIsInstance<WrapperPlayServerSetPassengers>().single().passengers.toList() shouldBe listOf(11, mounted.entityId)

        backend.clear()
        connection.submit(
            listOf(mounted), resetAll = true, desiredPassengers = mapOf(77 to listOf(mounted.entityId)),
            nativePassengerSnapshots = mapOf(77 to listOf(11)), liveVehicleIds = setOf(77),
        )
        backend.runAll()
        val replayWrites = backend.writes
        (replayWrites.indexOfFirst { it is WrapperPlayServerSetPassengers } <
            replayWrites.indexOfFirst { it is WrapperPlayServerDestroyEntities }) shouldBe true
        (replayWrites.indexOfFirst { it is WrapperPlayServerEntityMetadata } <
            replayWrites.indexOfLast { it is WrapperPlayServerSetPassengers }) shouldBe true
        replayWrites.filterIsInstance<WrapperPlayServerSetPassengers>().map { it.passengers.toList() } shouldBe listOf(
            listOf(11), listOf(11, mounted.entityId),
        )

        backend.clear()
        connection.submit(
            listOf(mounted.copy(x = 2.0)), desiredPassengers = mapOf(77 to listOf(mounted.entityId)),
            nativePassengerSnapshots = mapOf(77 to listOf(11)), liveVehicleIds = setOf(77),
        )
        backend.runAll()
        backend.writes.none { it is WrapperPlayServerEntityTeleport } shouldBe true
        backend.writes.none { it is WrapperPlayServerSetPassengers } shouldBe true

        backend.clear()
        connection.submit(
            listOf(mounted.copy(x = 4.0, attachmentVehicleId = null)),
            nativePassengerSnapshots = mapOf(77 to listOf(11)), liveVehicleIds = setOf(77),
        )
        backend.runAll()
        val detach = backend.writes.indexOfFirst { it is WrapperPlayServerSetPassengers }
        val settle = backend.writes.indexOfFirst { it is WrapperPlayServerEntityTeleport }
        (detach >= 0 && detach < settle) shouldBe true
        backend.writes.filterIsInstance<WrapperPlayServerSetPassengers>().single().passengers.toList() shouldBe listOf(11)
    }

    "mount admission retries without a passenger link before spawn and metadata are admitted" {
        val backend = RecordingPacketBackend().apply { ordinaryTransactions = 1 }
        val connection = PacketDisplayAttachment(Any(), Logger.getAnonymousLogger(), backend)
        val mounted = frame().copy(attachmentVehicleId = 77)
        connection.submit(listOf(mounted), desiredPassengers = mapOf(77 to listOf(mounted.entityId)))
        backend.tasks.removeFirst().invoke()
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().size shouldBe 1
        backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().size shouldBe 1
        backend.writes.filterIsInstance<WrapperPlayServerSetPassengers>().size shouldBe 0

        backend.ordinaryTransactions = 1
        backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerSetPassengers>().single().passengers.toList() shouldBe listOf(mounted.entityId)
    }

    "native full passenger replacements retain current real IDs and only admitted fake IDs" {
        val state = PacketDisplayPassengerState()
        state.seedNative(77, listOf(9))
        state.setMounted(77, listOf(100, 101))
        state.observeNative(77, intArrayOf(12)).toList() shouldBe listOf(12, 100, 101)
        state.nativePassengers(77) shouldBe listOf(12)

        state.setMounted(77, listOf(101))
        state.observeNative(77, intArrayOf(13)).toList() shouldBe listOf(13, 101)
        state.nativePassengers(77) shouldBe listOf(13)
        state.setMounted(77, emptyList())
        state.observeNative(77, intArrayOf(14)).toList() shouldBe listOf(14)
    }

    "own passenger writes are not re-captured and same-channel failure cleanup preserves latest native IDs" {
        val channel = Any()
        val backend = RecordingPacketBackend().apply { failOnFlush = 2 }
        val state = PacketDisplayPassengerState().apply { seedNative(77, listOf(11)) }
        backend.onWrite = { packet ->
            if (packet is WrapperPlayServerSetPassengers) {
                state.observeNative(packet.entityId, packet.passengers)
            }
        }
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend, state)
        val mounted = frame().copy(attachmentVehicleId = 77)
        connection.submit(
            listOf(mounted), desiredPassengers = mapOf(77 to listOf(mounted.entityId)),
            nativePassengerSnapshots = mapOf(77 to listOf(11)), liveVehicleIds = setOf(77),
        )
        backend.runAll()

        // RecordingPacketBackend invokes the same observer synchronously from tryWrite,
        // which models PacketEvents observing the wrapper during its encode path.
        state.nativePassengers(77) shouldBe listOf(11)
        state.observeNative(77, intArrayOf(12)).toList() shouldBe listOf(12, mounted.entityId)
        state.nativePassengers(77) shouldBe listOf(12)

        backend.failOnFlush = null
        backend.clear()
        connection.deferReplacementUntilCleanup(channel) shouldBe true
        backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerSetPassengers>().map { it.passengers.toList() } shouldBe
            listOf(listOf(12))
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single().entityIds.toList() shouldBe
            listOf(mounted.entityId)
        connection.deferReplacementUntilCleanup(channel) shouldBe false
        state.isDisconnected() shouldBe false
    }

    "text metadata packs the native flags at index 27" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)

        connection.submit(listOf(frame(content = PacketDisplayContent.Text(Component.text("hello")))))
        backend.runAll()

        val indexes = backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>()
            .single().entityMetadata.map { it.index }
        indexes shouldContain 23
        indexes shouldContain 24
        indexes shouldContain 25
        indexes shouldContain 26
        indexes shouldContain 27
        (28 in indexes) shouldBe false
    }

    "a partial write invalidates the attachment and cleanup still destroys possible IDs" {
        val backend = RecordingPacketBackend(failOnWrite = 2)
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val display = frame()

        connection.submit(listOf(display))
        backend.runAll()
        backend.failOnWrite = null
        backend.clear()

        connection.submit(emptyList())
        backend.runAll()

        val destroy = backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single()
        destroy.entityIds.toList() shouldBe listOf(display.entityId)
        backend.flushes shouldBe 1
    }

    "an enqueue failure leaves cleanup retry work schedulable" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val display = frame()

        connection.submit(listOf(display))
        backend.runAll()
        backend.clear()
        backend.failEnqueue = true
        connection.submit(listOf(display.copy(x = 1.0)))
        backend.failEnqueue = false
        connection.submit(emptyList())
        backend.tasks.size shouldBe 1
        backend.runAll()

        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single()
            .entityIds.toList() shouldBe listOf(display.entityId)
    }

    "an oversized merged chunk reset collapses to a bounded full replay" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val display = frame()

        connection.submit(listOf(display))
        backend.runAll()
        backend.clear()
        connection.submit(listOf(display), resetChunks = (0L..256L).toSet())
        backend.runAll()

        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single()
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().single().entityId shouldBe display.entityId
    }
})

private class RecordingPacketBackend(
    var failOnWrite: Int? = null,
) : PacketDisplayChannelBackend {
    val tasks = ArrayDeque<() -> Unit>()
    val writes = mutableListOf<PacketWrapper<*>>()
    var flushes = 0
        private set
    var failEnqueue = false
    var writable = true
    var ordinaryTransactions = Int.MAX_VALUE
    var onWrite: ((PacketWrapper<*>) -> Unit)? = null
    var failOnFlush: Int? = null
    private var writeCount = 0

    override fun isOpen(channel: Any): Boolean = true

    override fun enqueue(channel: Any, task: () -> Unit) {
        if (failEnqueue) throw IllegalStateException("injected enqueue failure")
        tasks += task
    }

    override fun retry(channel: Any, task: () -> Unit) { tasks += task }

    override fun tryWrite(channel: Any, packets: List<PacketWrapper<*>>, cleanup: Boolean): Boolean {
        if (!writable || (!cleanup && ordinaryTransactions <= 0)) return false
        if (!cleanup) ordinaryTransactions--
        packets.forEach { packet ->
            writeCount++
            if (writeCount == failOnWrite) throw IllegalStateException("injected packet write failure")
            writes += packet
            onWrite?.invoke(packet)
        }
        return true
    }

    override fun flush(channel: Any) {
        flushes++
        if (flushes == failOnFlush) throw IllegalStateException("injected flush failure")
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
    }

    fun clear() {
        writes.clear()
        flushes = 0
        writeCount = 0
    }
}

private fun frame(
    id: Int = 100,
    x: Double = 0.0,
    content: PacketDisplayContent = PacketDisplayContent.Block(1),
    uuid: UUID = UUID(0, id.toLong()),
): PacketDisplayFrame = PacketDisplayFrame(
    entityId = id,
    uuid = uuid,
    x = x,
    y = 64.0,
    z = 0.0,
    yaw = 15f,
    pitch = 2f,
    metadata = PacketDisplayMetadata(content),
)

private fun animatedFrame(
    id: Int = 100,
    uuid: UUID = UUID(0, id.toLong()),
    content: PacketDisplayContent,
    yTransform: Float,
    duration: Int,
): PacketDisplayFrame = frame(id = id, content = content, uuid = uuid).copy(
    metadata = PacketDisplayMetadata(
        content = content,
        transform = DisplayTransform(translation = DisplayVector(0f, yTransform, 0f)),
        interpolationDuration = duration,
    ),
)

private enum class TestItem { AIR, DIAMOND, EMERALD }

private fun itemContent(material: TestItem): PacketDisplayContent.Item = PacketDisplayContent.Item(testItem(material), 0)

private fun testItem(material: TestItem = TestItem.DIAMOND): com.github.retrooper.packetevents.protocol.item.ItemStack {
    val type = when (material) {
        TestItem.AIR -> com.github.retrooper.packetevents.protocol.item.type.ItemTypes.AIR
        TestItem.DIAMOND -> com.github.retrooper.packetevents.protocol.item.type.ItemTypes.DIAMOND
        TestItem.EMERALD -> com.github.retrooper.packetevents.protocol.item.type.ItemTypes.EMERALD
    }
    return com.github.retrooper.packetevents.protocol.item.ItemStack.builder()
        .type(type)
        .amount(1)
        .version(com.github.retrooper.packetevents.protocol.player.ClientVersion.V_1_21_11)
        .build()
}

private fun WrapperPlayServerEntityMetadata.metadataValue(index: Int): Any? =
    entityMetadata.single { it.index == index }.value

/** Real registry and buffer plumbing, with no live player/channel operations. */
internal class TestPacketEventsApi : PacketEventsAPI<Any>() {
    private val server = ServerManager { ServerVersion.V_1_21_11 }
    private val netty = NettyManagerImpl()
    override fun getServerManager() = server
    override fun getNettyManager() = netty
    override fun getPlugin(): Any = this
    override fun getProtocolManager(): com.github.retrooper.packetevents.manager.protocol.ProtocolManager =
        error("No live protocol manager in codec tests")
    override fun getPlayerManager(): com.github.retrooper.packetevents.manager.player.PlayerManager =
        error("No Bukkit players in codec tests")
    override fun getInjector(): com.github.retrooper.packetevents.injector.ChannelInjector =
        error("No channel injection in codec tests")
    override fun load() = Unit
    override fun init() = Unit
    override fun terminate() = Unit
    override fun isLoaded() = true
    override fun isInitialized() = true
    override fun isTerminated() = false
}
