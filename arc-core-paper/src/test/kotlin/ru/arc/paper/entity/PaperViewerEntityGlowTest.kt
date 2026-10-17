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
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import ru.arc.paper.player.TestPaperPlugin
import ru.arc.paper.display.TestPacketEventsApi
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.paper.testing.loadPlugin
import ru.arc.paper.api.VisualPacketAdmission
import ru.arc.paper.runtime.PaperNativeCleanupServiceProvider
import java.util.UUID
import java.util.logging.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    "viewer and entity removal preserve other viewer indexes and detach cannot clear a replacement" {
        val state = ViewerEntityGlowSelections()
        val entity = mockk<Entity>()
        val viewers = List(300) { UUID.randomUUID() }
        viewers.forEach { viewer -> repeat(3) { id -> state.select(ViewerEntityGlowKey(viewer, id), entity) } }
        state.forgetViewer(viewers[0])
        state.forgetEntities(viewers[1], intArrayOf(0, 2))
        state.isSelected(viewers[0], 0) shouldBe false
        state.isSelected(viewers[1], 0) shouldBe false
        state.isSelected(viewers[1], 1) shouldBe true
        state.isSelected(viewers[2], 0) shouldBe true
        state.size shouldBe 895
        val old = state.detach()
        state.size shouldBe 0
        state.select(ViewerEntityGlowKey(viewers[0], 7), entity)
        old.asSequence().count() shouldBe 895
        state.isSelected(viewers[0], 7) shouldBe true
        state.size shouldBe 1
    }

    "selection is viewer-specific, false restores native glow, and close restores remaining viewers" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val target = paper.addPlayer("Target")
            val firstViewer = paper.addPlayer("First")
            val secondViewer = paper.addPlayer("Second")
            val transport = RecordingTransport()
            val cleanup = paper.glowCleanup()
            val glow = PaperViewerEntityGlow(plugin, transport, cleanup)

            target.isGlowing = false
            val nativeUnlit = transport.nativeFlags(target)
            glow.set(firstViewer, target, glowing = true)
            transport.sent.last().viewerId shouldBe firstViewer.uniqueId
            transport.sent.last().flags shouldBe withGlowBit(nativeUnlit, glowing = true)
            transport.sent.last().cleanup shouldBe false
            target.isGlowing shouldBe false
            glow.isSelectedFor(firstViewer.uniqueId, target.entityId) shouldBe true
            glow.isSelectedFor(secondViewer.uniqueId, target.entityId) shouldBe false

            glow.set(secondViewer, target, glowing = true)
            glow.isSelectedFor(secondViewer.uniqueId, target.entityId) shouldBe true
            target.isGlowing = true
            val nativeLit = transport.nativeFlags(target)
            glow.set(firstViewer, target, glowing = false)
            transport.sent.last().flags shouldBe nativeLit
            transport.sent.last().cleanup shouldBe true
            glow.isSelectedFor(firstViewer.uniqueId, target.entityId) shouldBe false
            glow.isSelectedFor(secondViewer.uniqueId, target.entityId) shouldBe true

            glow.close()
            glow.close()
            paper.performTicks(1)
            transport.sent.last().viewerId shouldBe secondViewer.uniqueId
            transport.sent.last().flags shouldBe nativeLit
            transport.sent.last().cleanup shouldBe true
            glow.trackedSelectionCount() shouldBe 0
            transport.unregistered shouldBe true
            cleanup.close()
        }
    }

    "close unregisters the packet observer if native restoration capture throws" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val target = paper.addPlayer("Target")
            val viewer = paper.addPlayer("Viewer")
            val transport = RecordingTransport()
            val cleanup = paper.glowCleanup()
            val glow = PaperViewerEntityGlow(plugin, transport, cleanup)

            glow.set(viewer, target, glowing = true)
            transport.failNativeFlags = true

            val closing = glow.closeAsync()
            paper.performTicks(1)
            closing.isCompletedExceptionally shouldBe true
            transport.unregistered shouldBe true
            cleanup.close()
        }
    }

    "close keeps the packet observer until a deferred restoration drains or disconnects" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val target = paper.addPlayer("Target")
            val viewer = paper.addPlayer("Viewer")
            val transport = RecordingTransport().apply { deferCleanups = true }
            val cleanup = paper.glowCleanup()
            val glow = PaperViewerEntityGlow(plugin, transport, cleanup)

            glow.set(viewer, target, glowing = true)
            glow.close()
            paper.performTicks(1)
            transport.unregistered shouldBe false

            val user = mockk<User>()
            every { user.uuid } returns viewer.uniqueId
            every { user.channel } returns Any()
            transport.fireDisconnect(UserDisconnectEvent(user))
            paper.performTicks(1)
            transport.unregistered shouldBe true
            cleanup.close()
        }
    }

    "selective restoration observer preserves the live owner and cannot cancel accepted work" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("GlowConsumer")
            val viewer = paper.addPlayer("Viewer")
            val target = paper.addPlayer("Target")
            val transport = RecordingTransport().apply { deferCleanups = true }
            paper.glowCleanup().use { cleanup ->
                val glow = PaperViewerEntityGlow(plugin, transport, cleanup)
                glow.set(viewer, target, true)
                glow.set(viewer, target, false)
                val observer = glow.cleanupDrained()
                observer.cancel(false) shouldBe true
                val next = glow.cleanupDrained()
                next.isDone shouldBe false
                val user = mockk<User> {
                    every { uuid } returns viewer.uniqueId
                    every { channel } returns Any()
                }
                transport.fireDisconnect(UserDisconnectEvent(user))
                next.join() shouldBe null
                transport.unregistered shouldBe false
                glow.set(viewer, target, true)
                glow.isSelectedFor(viewer.uniqueId, target.entityId) shouldBe true
                transport.deferCleanups = false
                glow.close()
                paper.performTicks(1)
            }
        }
    }

    "close detaches immediately and restores large selections on the host budget after consumer disable" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("GlowConsumer")
            val viewer = paper.addPlayer("Viewer")
            val transport = RecordingTransport()
            paper.glowCleanup(7).use { cleanup ->
                val glow = PaperViewerEntityGlow(plugin, transport, cleanup)
                val entities = (1..300).map { id -> mockk<Entity> {
                    every { entityId } returns id
                    every { isValid } returns true
                    every { isGlowing } returns false
                } }
                entities.forEach { glow.set(viewer, it, true) }
                transport.sent.clear()
                val observer = glow.closeAsync()
                observer.cancel(false) shouldBe true
                val actualObserver = glow.closeAsync()
                glow.trackedSelectionCount() shouldBe 0
                transport.sent.size shouldBe 0
                actualObserver.isDone shouldBe false
                paper.server.pluginManager.disablePlugin(plugin)
                paper.performTicks(1)
                transport.sent.size shouldBe 7
                actualObserver.isDone shouldBe false
                repeat(43) {
                    val before = transport.sent.size
                    paper.performTicks(1)
                    (transport.sent.size - before <= 7) shouldBe true
                }
                actualObserver.join() shouldBe null
                transport.sent.size shouldBe 300
                transport.sent.map { it.entityId }.toSet().size shouldBe 300
                transport.sent.all { it.cleanup } shouldBe true
                transport.unregistered shouldBe true
            }
        }
    }

    "host stop fails a partial cleanup and never reports its remaining pairs as restored" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("GlowConsumer")
            val viewer = paper.addPlayer("Viewer")
            val transport = RecordingTransport().apply { deferCleanups = true }
            val cleanup = paper.glowCleanup(1)
            val glow = PaperViewerEntityGlow(plugin, transport, cleanup)
            val entities = (1..3).map { id -> mockk<Entity> {
                every { entityId } returns id
                every { isValid } returns true
                every { isGlowing } returns false
            } }
            entities.forEach { glow.set(viewer, it, true) }
            transport.sent.clear()
            val closing = glow.closeAsync()
            paper.performTicks(1)
            transport.sent.size shouldBe 1
            cleanup.close()
            closing.isCompletedExceptionally shouldBe true
            paper.performTicks(5)
            transport.sent.size shouldBe 1
            transport.unregistered shouldBe true
        }
    }

    "the packet queue coalesces latest flags, retries bounded pressure, and drops removed entities" {
        val backend = RecordingGlowQueueBackend()
        val queue = ViewerEntityGlowPacketQueue(backend, Logger.getAnonymousLogger())
        val channel = Any()

        queue.submit(channel, 101, 0x40.toByte(), cleanup = false)
        queue.submit(channel, 101, 0x41.toByte(), cleanup = false)
        queue.submit(channel, 202, 0x42.toByte(), cleanup = false)
        queue.hasPendingCleanup(channel, 101) shouldBe false
        backend.immediate.size shouldBe 1

        backend.runImmediate()
        backend.attempts.single().deliveries.map { it.entityId to it.flags } shouldBe listOf(
            101 to 0x41.toByte(),
            202 to 0x42.toByte(),
        )
        backend.attempts.single().admission shouldBe VisualPacketAdmission.VIEWER_RATE
        backend.retries.size shouldBe 1

        // Restoration replaces the pending glow-on update and remains deferred until writable.
        queue.submit(channel, 101, 0x21.toByte(), cleanup = true)
        queue.hasPendingCleanup(channel, 101) shouldBe true
        backend.writable = false
        backend.runRetry()
        backend.attempts[1].cleanup shouldBe true
        backend.attempts[1].admission shouldBe VisualPacketAdmission.CHANNEL_BACKPRESSURE
        backend.attempts.last().cleanup shouldBe false
        backend.attempts.last().admission shouldBe VisualPacketAdmission.CHANNEL_BACKPRESSURE
        backend.writes.size shouldBe 0
        backend.retries.size shouldBe 1

        backend.writable = true
        backend.runRetry()
        backend.writes.single().let { (cleanup, deliveries) ->
            cleanup shouldBe true
            deliveries.map { it.entityId to it.flags } shouldBe listOf(101 to 0x21.toByte())
        }
        backend.attempts.last().admission shouldBe VisualPacketAdmission.VIEWER_RATE
        backend.retries.size shouldBe 1

        // A server destroy supersedes the unsent ordinary update for entity 202.
        queue.forgetEntities(channel, intArrayOf(202))
        queue.submit(channel, 303, 0x33.toByte(), cleanup = false)
        backend.ordinaryAdmission = VisualPacketAdmission.ALLOWED
        backend.runRetry()
        backend.writes.last().let { (cleanup, deliveries) ->
            cleanup shouldBe false
            deliveries.map { it.entityId to it.flags } shouldBe listOf(303 to 0x33.toByte())
        }
        backend.flushes shouldBe 2
        backend.retries.size shouldBe 0
        backend.immediate.size shouldBe 0

        // Disconnect clears the weak-keyed connection state and any scheduled delivery.
        queue.submit(channel, 404, 0x44.toByte(), cleanup = false)
        backend.immediate.size shouldBe 1
        backend.open = false
        queue.forgetChannel(channel)
        queue.trackedConnectionCount() shouldBe 0
        backend.runImmediate()
        backend.writes.size shouldBe 2
    }

    "a closed shared budget drops ordinary glow updates but still admits restoration" {
        val backend = RecordingGlowQueueBackend().apply {
            ordinaryAdmission = VisualPacketAdmission.CLOSED
        }
        val queue = ViewerEntityGlowPacketQueue(backend, Logger.getAnonymousLogger())
        val channel = Any()

        queue.submit(channel, 505, 0x40.toByte(), cleanup = false)
        backend.runImmediate()
        backend.attempts.single().admission shouldBe VisualPacketAdmission.CLOSED
        backend.retries.size shouldBe 0

        queue.submit(channel, 505, 0x20.toByte(), cleanup = true)
        backend.runImmediate()
        backend.writes.single().let { (cleanup, deliveries) ->
            cleanup shouldBe true
            deliveries.map { it.entityId to it.flags } shouldBe listOf(505 to 0x20.toByte())
        }
        backend.retries.size shouldBe 0
    }

    "a native fire and invisibility update supersedes a pressured restore after owner close" {
        val backend = RecordingGlowQueueBackend().apply {
            cleanupAdmission = VisualPacketAdmission.CHANNEL_BACKPRESSURE
        }
        val queue = ViewerEntityGlowPacketQueue(backend, Logger.getAnonymousLogger())
        val channel = Any()
        var cleanupsDrained = false

        queue.submit(channel, 606, 0x00.toByte(), cleanup = true)
        queue.submit(channel, 607, 0x40.toByte(), cleanup = false)
        backend.runImmediate()
        backend.attempts[0].cleanup shouldBe true
        backend.attempts[0].admission shouldBe VisualPacketAdmission.CHANNEL_BACKPRESSURE
        backend.attempts[1].cleanup shouldBe false
        backend.retries.size shouldBe 1

        queue.retire()
        queue.whenCleanupsDrained { cleanupsDrained = true }
        cleanupsDrained shouldBe false

        // The native byte now has fire (0x01) and invisibility (0x20); it is already on the wire.
        queue.nativeFlagsSent(channel, 606, 0x21.toByte())
        cleanupsDrained shouldBe true
        backend.runRetry()
        backend.writes shouldBe emptyList()
        backend.attempts.size shouldBe 2
        backend.retries.size shouldBe 0
    }

    "an injected packet cannot supersede a newer restoration queued during its write" {
        val backend = RecordingGlowQueueBackend().apply {
            ordinaryAdmission = VisualPacketAdmission.ALLOWED
        }
        val queue = ViewerEntityGlowPacketQueue(backend, Logger.getAnonymousLogger())
        val channel = Any()
        backend.duringNextWrite = { deliveries ->
            queue.submit(channel, 707, 0x21.toByte(), cleanup = true)
            queue.nativeFlagsSent(channel, 707, deliveries.single().flags)
        }

        queue.submit(channel, 707, 0x40.toByte(), cleanup = false)
        backend.runImmediate()
        backend.writes.single().let { (cleanup, deliveries) ->
            cleanup shouldBe false
            deliveries.single().flags shouldBe 0x40.toByte()
        }
        backend.immediate.size shouldBe 1

        backend.runImmediate()
        backend.writes.last().let { (cleanup, deliveries) ->
            cleanup shouldBe true
            deliveries.single().flags shouldBe 0x21.toByte()
        }
        backend.immediate.size shouldBe 0
    }

    "disconnect releases a closed owner's observer while cleanup is blocked" {
        val backend = RecordingGlowQueueBackend().apply {
            cleanupAdmission = VisualPacketAdmission.CHANNEL_BACKPRESSURE
        }
        val queue = ViewerEntityGlowPacketQueue(backend, Logger.getAnonymousLogger())
        val channel = Any()
        var cleanupsDrained = false

        queue.submit(channel, 808, 0x20.toByte(), cleanup = true)
        backend.runImmediate()
        queue.retire()
        queue.whenCleanupsDrained { cleanupsDrained = true }
        cleanupsDrained shouldBe false

        backend.open = false
        backend.runRetry()
        cleanupsDrained shouldBe true
        queue.trackedConnectionCount() shouldBe 0
    }

    "PacketEvents status users with no UUID are ignored on send and disconnect" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadPlugin<TestPaperPlugin>()
                val viewer = paper.addPlayer("Viewer")
                val target = paper.addPlayer("Target")
                val transport = RecordingTransport()
                val cleanup = paper.glowCleanup()
                val glow = PaperViewerEntityGlow(plugin, transport, cleanup)
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
                paper.performTicks(1)
                cleanup.close()
            }
        }
    }

    "blocked packet write cannot hold native admission and cleanup completion waits for the accepted write" {
        val backend = RecordingGlowQueueBackend().apply { ordinaryAdmission = VisualPacketAdmission.ALLOWED }
        val queue = ViewerEntityGlowPacketQueue(backend, Logger.getAnonymousLogger())
        val channel = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.duringNextWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        queue.submit(channel, 1, 0x20.toByte(), true)
        val writing = CompletableFuture.runAsync { backend.runImmediate() }
        try {
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            queue.submit(channel, 2, 0x20.toByte(), true)
            queue.retire()
            var drained = false
            queue.whenCleanupsDrained { drained = true }
            drained shouldBe false
            release.countDown()
            writing.get(5, TimeUnit.SECONDS)
            drained shouldBe false
            backend.runImmediate()
            drained shouldBe true
            backend.flushes shouldBe 2
        } finally { release.countDown(); writing.get(5, TimeUnit.SECONDS) }
    }
})

private fun MockBukkitTestRuntime.glowCleanup(steps: Int = 128) =
    PaperNativeCleanupServiceProvider(createSimplePlugin("GlowCleanupHost"), steps, 2_000_000L) { 0L }

private class RecordingTransport : ViewerEntityGlowPacketTransport {
    override fun nativeFlags(entity: Entity): Byte {
        check(!failNativeFlags) { "native flags unavailable" }
        return withGlowBit(0xa9.toByte(), entity.isGlowing)
    }

    data class Sent(val viewerId: UUID, val entityId: Int, val flags: Byte, val cleanup: Boolean)

    val sent = mutableListOf<Sent>()
    private var registered: PacketListenerCommon? = null
    private var cleanupsDrained: (() -> Unit)? = null
    var deferCleanups = false
    var failNativeFlags = false
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

    override fun send(viewer: Player, entityId: Int, flags: Byte, cleanup: Boolean) {
        sent += Sent(viewer.uniqueId, entityId, flags, cleanup)
    }

    override fun whenCleanupsDrained(callback: () -> Unit) {
        if (deferCleanups) cleanupsDrained = callback else callback()
    }

    override fun forgetChannel(channel: Any) {
        cleanupsDrained?.also { cleanupsDrained = null }?.invoke()
    }

    fun fireDisconnect(event: UserDisconnectEvent) {
        registered?.onUserDisconnect(event)
    }

    fun firePacketSend(event: PacketSendEvent) {
        (registered as PacketListenerAbstract).onPacketSend(event)
    }
}

private class RecordingGlowQueueBackend : ViewerEntityGlowQueueBackend {
    data class Attempt(
        val cleanup: Boolean,
        val deliveries: List<ViewerEntityGlowDelivery>,
        val admission: VisualPacketAdmission,
    )

    val immediate = java.util.ArrayDeque<() -> Unit>()
    val retries = java.util.ArrayDeque<() -> Unit>()
    val attempts = mutableListOf<Attempt>()
    val writes = mutableListOf<Pair<Boolean, List<ViewerEntityGlowDelivery>>>()
    var open = true
    var writable = true
    var ordinaryAdmission = VisualPacketAdmission.VIEWER_RATE
    var cleanupAdmission = VisualPacketAdmission.ALLOWED
    var flushes = 0
    var duringNextWrite: ((List<ViewerEntityGlowDelivery>) -> Unit)? = null

    override fun isOpen(channel: Any): Boolean = open
    override fun enqueue(channel: Any, task: () -> Unit) {
        immediate.addLast(task)
    }
    override fun retry(channel: Any, task: () -> Unit) {
        retries.addLast(task)
    }
    override fun write(
        channel: Any,
        deliveries: List<ViewerEntityGlowDelivery>,
        cleanup: Boolean,
    ): VisualPacketAdmission {
        duringNextWrite?.also {
            duringNextWrite = null
            it(deliveries)
        }
        val admission = when {
            !open -> VisualPacketAdmission.CLOSED
            !writable -> VisualPacketAdmission.CHANNEL_BACKPRESSURE
            cleanup -> cleanupAdmission
            else -> ordinaryAdmission
        }
        attempts += Attempt(cleanup, deliveries.toList(), admission)
        if (admission == VisualPacketAdmission.ALLOWED) writes += cleanup to deliveries.toList()
        return admission
    }
    override fun flush(channel: Any) {
        flushes++
    }
    fun runImmediate() = immediate.removeFirst().invoke()
    fun runRetry() = retries.removeFirst().invoke()
}
