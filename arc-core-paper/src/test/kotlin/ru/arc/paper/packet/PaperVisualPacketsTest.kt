package ru.arc.paper.packet

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.injector.ChannelInjector
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import ru.arc.paper.api.ArcVisualPacketBudget
import ru.arc.paper.api.VisualPacketAdmission

class PaperVisualPacketsTest : FreeSpec({
    val priorApi = PacketEvents.getAPI()
    val protocol = mockk<ProtocolManager>()
    val sizes = mutableListOf<Int>()
    beforeSpec {
        every { protocol.writePacket(any<Any>(), any<Any>()) } answers {
            val buffer = secondArg<ByteBuf>()
            sizes += buffer.readableBytes()
            buffer.release()
            Unit
        }
        PacketEvents.setAPI(CodecApi(protocol))
    }
    afterSpec { PacketEvents.setAPI(priorApi) }

    "admission uses encoded bytes and releases rejected buffers without recording them as sent" {
        val channel = EmbeddedChannel()
        try {
            val budget = RecordingBudget()
            val sender = PaperVisualPackets("test:displays", budget)
            val denied = listOf(CodecPacket(), CodecPacket())
            sender.write(channel, denied) shouldBe VisualPacketAdmission.VIEWER_RATE
            denied.forEach { it.encoded!!.refCnt() shouldBe 0 }
            denied.forEach { it.buffer shouldBe null }
            budget.sentBytes shouldBe 0
            budget.reservedBytes shouldBe denied.sumOf { it.size }

            budget.admission = VisualPacketAdmission.ALLOWED
            val sent = listOf(CodecPacket(), CodecPacket())
            sender.write(channel, sent, cleanup = true) shouldBe VisualPacketAdmission.ALLOWED
            budget.sentBytes shouldBe sent.sumOf { it.size }
            budget.sentPackets shouldBe 2
            budget.cleanup shouldBe true
            sizes.takeLast(2).sum() shouldBe budget.sentBytes
            sent.forEach { it.encoded!!.refCnt() shouldBe 0 }
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    "a failed encode releases the completed prefix and partially encoded packet" {
        val channel = EmbeddedChannel()
        try {
            val budget = RecordingBudget()
            val first = CodecPacket()
            val broken = CodecPacket(fail = true)
            shouldThrow<IllegalStateException> {
                PaperVisualPackets("test:displays", budget).write(channel, listOf(first, broken))
            }
            first.encoded!!.refCnt() shouldBe 0
            broken.encoded!!.refCnt() shouldBe 0
            broken.buffer shouldBe null
            budget.reservedBytes shouldBe 0
            budget.sentPackets shouldBe 0
        } finally {
            channel.finishAndReleaseAll()
        }
    }
})

private class CodecPacket(private val fail: Boolean = false) : PacketWrapper<CodecPacket>(PacketType.Play.Server.ENTITY_METADATA) {
    var encoded: ByteBuf? = null
    var size = 0
    override fun write() {
        encoded = buffer as ByteBuf
        writeInt(1234)
        size = encoded!!.readableBytes()
        check(!fail) { "injected encoding failure" }
    }
}

private class RecordingBudget : ArcVisualPacketBudget {
    var admission = VisualPacketAdmission.VIEWER_RATE
    var reservedBytes = 0
    var sentBytes = 0
    var sentPackets = 0
    var cleanup = false
    override fun acquire(source: String, connection: Any, bytes: Int, packets: Int, cleanup: Boolean, writable: Boolean): VisualPacketAdmission {
        reservedBytes += bytes
        this.cleanup = cleanup
        return admission
    }
    override fun recordSent(source: String, bytes: Int, packets: Int, cleanup: Boolean) {
        sentBytes += bytes
        sentPackets += packets
    }
}

private class CodecApi(private val protocol: ProtocolManager) : PacketEventsAPI<Any>() {
    private val server = ServerManager { ServerVersion.V_1_21_11 }
    private val netty = NettyManagerImpl()
    private val injector = mockk<ChannelInjector> { every { isProxy } returns false }
    override fun getServerManager() = server
    override fun getNettyManager() = netty
    override fun getProtocolManager() = protocol
    override fun getInjector() = injector
    override fun getPlugin(): Any = this
    override fun getPlayerManager(): com.github.retrooper.packetevents.manager.player.PlayerManager = error("No player reads")
    override fun load() = Unit
    override fun init() = Unit
    override fun terminate() = Unit
    override fun isLoaded() = true
    override fun isInitialized() = true
    override fun isTerminated() = false
}
