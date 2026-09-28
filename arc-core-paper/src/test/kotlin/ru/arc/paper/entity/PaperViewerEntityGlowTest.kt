package ru.arc.paper.entity

import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.User
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import org.bukkit.entity.LivingEntity
import ru.arc.paper.player.TestPaperPlugin
import ru.arc.paper.display.TestPacketEventsApi
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.paper.testing.loadPlugin
import java.util.UUID

class PaperViewerEntityGlowTest : FreeSpec({
    val previousPacketEventsApi = PacketEvents.getAPI()
    beforeSpec { PacketEvents.setAPI(TestPacketEventsApi()) }
    afterSpec { PacketEvents.setAPI(previousPacketEventsApi) }

    "glow overrides preserve every other common flag and update only metadata index zero" {
        for (raw in 0..0xff) {
            val flags = raw.toByte()
            val enabled = withGlowBit(flags, glowing = true).toInt() and 0xff
            val disabled = withGlowBit(flags, glowing = false).toInt() and 0xff
            enabled shouldBe (raw or 0x40)
            disabled shouldBe (raw and 0xbf)
        }

        val flags = 0x29.toByte()
        val other = EntityData(1, EntityDataTypes.INT, 12)
        val metadata = listOf(other, EntityData(0, EntityDataTypes.BYTE, flags))
        val adjusted = withViewerGlow(metadata)!!
        (adjusted[1].value as Byte) shouldBe withGlowBit(flags, glowing = true)
        adjusted[0] shouldBe other
        (metadata[1].value as Byte) shouldBe flags
        withViewerGlow(listOf(other)) shouldBe null
        withViewerGlow(listOf(EntityData(0, EntityDataTypes.BYTE, withGlowBit(flags, true)))) shouldBe null
    }

    "selection is viewer-specific, false restores native glow, and close restores remaining viewers" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val target = paper.addPlayer("Target")
            val firstViewer = paper.addPlayer("First")
            val secondViewer = paper.addPlayer("Second")
            val transport = RecordingTransport()
            val glow = PaperViewerEntityGlow(plugin, transport)

            target.isGlowing = false
            val nativeUnlit = transport.nativeFlags(target)
            glow.set(firstViewer, target, glowing = true)
            transport.sent.last().viewerId shouldBe firstViewer.uniqueId
            transport.sent.last().flags shouldBe withGlowBit(nativeUnlit, glowing = true)
            target.isGlowing shouldBe false
            glow.isSelectedFor(firstViewer.uniqueId, target.entityId) shouldBe true
            glow.isSelectedFor(secondViewer.uniqueId, target.entityId) shouldBe false

            glow.set(secondViewer, target, glowing = true)
            glow.isSelectedFor(secondViewer.uniqueId, target.entityId) shouldBe true
            target.isGlowing = true
            val nativeLit = transport.nativeFlags(target)
            glow.set(firstViewer, target, glowing = false)
            transport.sent.last().flags shouldBe nativeLit
            glow.isSelectedFor(firstViewer.uniqueId, target.entityId) shouldBe false
            glow.isSelectedFor(secondViewer.uniqueId, target.entityId) shouldBe true

            glow.close()
            glow.close()
            transport.sent.last().viewerId shouldBe secondViewer.uniqueId
            transport.sent.last().flags shouldBe nativeLit
            glow.trackedSelectionCount() shouldBe 0
            transport.unregistered shouldBe true
        }
    }

    "PacketEvents status users with no UUID are ignored on send and disconnect" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadPlugin<TestPaperPlugin>()
                val viewer = paper.addPlayer("Viewer")
                val target = paper.addPlayer("Target")
                val transport = RecordingTransport()
                val glow = PaperViewerEntityGlow(plugin, transport)
                glow.set(viewer, target, glowing = true)

                val statusUser = mockk<User>()
                every { statusUser.uuid } returns null
                transport.fireDisconnect(UserDisconnectEvent(statusUser))
                glow.isSelectedFor(viewer.uniqueId, target.entityId) shouldBe true

                val statusPacket = mockk<PacketSendEvent>(relaxed = true)
                every { statusPacket.isCancelled } returns false
                every { statusPacket.user } returns statusUser
                every { statusPacket.packetType } returns PacketType.Play.Server.ENTITY_METADATA
                transport.firePacketSend(statusPacket)
                glow.isSelectedFor(viewer.uniqueId, target.entityId) shouldBe true
                glow.close()
            }
        }
    }
})

private class RecordingTransport : ViewerEntityGlowPacketTransport {
    override fun nativeFlags(entity: LivingEntity): Byte = withGlowBit(0xa9.toByte(), entity.isGlowing)

    data class Sent(val viewerId: UUID, val entityId: Int, val flags: Byte)

    val sent = mutableListOf<Sent>()
    private var registered: PacketListenerCommon? = null
    var unregistered = false
        private set

    override fun register(listener: PacketListenerCommon) {
        registered = listener
    }

    override fun unregister(listener: PacketListenerCommon) {
        if (registered === listener) {
            registered = null
            unregistered = true
        }
    }

    override fun send(viewer: Player, entityId: Int, flags: Byte) {
        sent += Sent(viewer.uniqueId, entityId, flags)
    }

    fun fireDisconnect(event: UserDisconnectEvent) {
        registered?.onUserDisconnect(event)
    }

    fun firePacketSend(event: PacketSendEvent) {
        (registered as PacketListenerAbstract).onPacketSend(event)
    }
}
