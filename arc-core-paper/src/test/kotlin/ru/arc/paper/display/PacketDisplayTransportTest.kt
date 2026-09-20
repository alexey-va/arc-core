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
    var onWrite: ((PacketWrapper<*>) -> Unit)? = null
    private var writeCount = 0

    override fun isOpen(channel: Any): Boolean = true

    override fun enqueue(channel: Any, task: () -> Unit) {
        if (failEnqueue) throw IllegalStateException("injected enqueue failure")
        tasks += task
    }

    override fun write(channel: Any, packet: PacketWrapper<*>) {
        writeCount++
        if (writeCount == failOnWrite) throw IllegalStateException("injected packet write failure")
        writes += packet
        onWrite?.invoke(packet)
    }

    override fun flush(channel: Any) {
        flushes++
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
): PacketDisplayFrame = PacketDisplayFrame(
    entityId = id,
    uuid = UUID(0, id.toLong()),
    x = x,
    y = 64.0,
    z = 0.0,
    yaw = 15f,
    pitch = 2f,
    metadata = PacketDisplayMetadata(content),
)

private fun testItem(): com.github.retrooper.packetevents.protocol.item.ItemStack =
    com.github.retrooper.packetevents.protocol.item.ItemStack.builder()
        .type(com.github.retrooper.packetevents.protocol.item.type.ItemTypes.DIAMOND)
        .amount(1)
        .version(com.github.retrooper.packetevents.protocol.player.ClientVersion.V_1_21_11)
        .build()

/** Real registry and buffer plumbing, with no live player/channel operations. */
private class TestPacketEventsApi : PacketEventsAPI<Any>() {
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
