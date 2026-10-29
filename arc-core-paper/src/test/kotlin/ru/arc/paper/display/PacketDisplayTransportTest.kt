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
import java.util.logging.Filter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class PacketDisplayTransportTest : FreeSpec({
    val previousApi = PacketEvents.getAPI()
    beforeSpec { PacketEvents.setAPI(TestPacketEventsApi()) }
    afterSpec { PacketEvents.setAPI(previousApi) }

    "an empty scene is drained only after removal flush and remains retryable after a write failure" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val display = frame()
        connection.submitAsync(emptyList()); backend.runAll()
        connection.isDrained shouldBe true
        connection.submitAsync(listOf(display)); backend.runAll(); backend.clear()
        connection.isDrained shouldBe false
        backend.writable = false
        val removal = connection.submitAsync(emptyList())
        backend.tasks.removeFirst().invoke() // Admission does not acknowledge deferred removal.
        removal.join()
        backend.tasks.removeFirst().invoke()
        connection.isDrained shouldBe false
        backend.writable = true
        backend.failOnWrite = 1
        backend.runAll()
        connection.isDrained shouldBe false
        backend.failOnWrite = null
        connection.submitAsync(emptyList()); backend.runAll()
        connection.isDrained shouldBe true
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single().entityIds.toList() shouldBe listOf(display.entityId)
        backend.flushes shouldBe 1
        java.lang.ref.Reference.reachabilityFence(channel)
    }

    "AFK pauses front-facing motion content and new spawns but preserves privacy removal and latest resume" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val active = PacketDisplayView(0.0, 64.0, 0.0, 0.0, 0.0, 1.0)
        val afk = active.copy(afk = true)
        val first = frame().copy(z = 8.0, metadata = frame().metadata.copy(interpolationDuration = 1, teleportDuration = 1))
        val removed = first.copy(entityId = 2, uuid = UUID(0, 2))
        connection.submitViewAsync(listOf(first, removed), active); backend.runAll(); backend.clear()
        val latest = first.copy(x = 2.0, metadata = first.metadata.copy(content = PacketDisplayContent.Block(2)))
        val added = latest.copy(entityId = 3, uuid = UUID(0, 3))
        connection.submitViewAsync(listOf(latest, added), afk); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single().entityIds.toList() shouldBe listOf(2)
        backend.writes.size shouldBe 1
        backend.clear()
        connection.submitViewAsync(listOf(latest, added), active); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().map { it.entityId } shouldBe listOf(3)
        backend.writes.filterIsInstance<WrapperPlayServerEntityTeleport>().single().position.x shouldBe 2.0
        backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().single { it.entityId == first.entityId }.let {
            it.metadataValue(9) shouldBe 0
            it.metadataValue(10) shouldBe 0
            it.metadataValue(23) shouldBe 2
        }
        backend.clear()
        connection.submitViewAsync(listOf(latest, added), active); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().single().metadataValue(9) shouldBe 1
        java.lang.ref.Reference.reachabilityFence(channel)
    }

    "AFK initial scenes and chunk replays defer spawns while reset and terminal cleanup remain active" {
        val backend = RecordingPacketBackend()
        val connection = PacketDisplayAttachment(Any(), Logger.getAnonymousLogger(), backend)
        val active = PacketDisplayView(0.0, 64.0, 0.0, 0.0, 0.0, 1.0)
        val afk = active.copy(afk = true)
        val initial = frame().copy(z = 8.0)
        connection.submitViewAsync(listOf(initial), afk); backend.runAll()
        backend.writes shouldBe emptyList()
        val latest = initial.copy(x = 2.0)
        connection.submitViewAsync(listOf(latest), active); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().single().entityId shouldBe initial.entityId
        backend.clear()
        connection.submitViewAsync(listOf(latest), afk, resetAll = true); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single()
        backend.writes.size shouldBe 1
        backend.clear()
        connection.submitViewAsync(listOf(latest), active); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().single()
        backend.clear()
        connection.submitViewAsync(listOf(latest.copy(x = 3.0)), afk); backend.runAll()
        connection.closeAsync(); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single().entityIds.toList() shouldBe listOf(initial.entityId)
        backend.writes.size shouldBe 1
    }

    "unpausable identity frames keep AFK and rear updates within normal packet admission and privacy cleanup" {
        val backend = RecordingPacketBackend().apply { ordinaryTransactions = 0 }
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val rear = PacketDisplayView(0.0, 64.0, 0.0, 0.0, 0.0, 1.0)
        val afk = rear.copy(afk = true)
        val initial = frame(content = PacketDisplayContent.Text(Component.text("Target"))).copy(
            z = -8.0,
            pauseWhenNotObserved = false,
            metadata = PacketDisplayMetadata(PacketDisplayContent.Text(Component.text("Target")),
                displayWidth = 1f, displayHeight = 0.5f, teleportDuration = 1),
        )
        rear.behind(initial, false) shouldBe true
        connection.submitViewAsync(listOf(initial), afk)
        backend.tasks.removeFirst().invoke() // Capture admission still cannot bypass the visual packet budget.
        backend.tasks.removeFirst().invoke()
        backend.writes shouldBe emptyList()
        backend.ordinaryTransactions = Int.MAX_VALUE
        backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().single().entityId shouldBe initial.entityId
        backend.clear()

        val moved = initial.copy(x = 0.5, metadata = initial.metadata.copy(
            content = PacketDisplayContent.Text(Component.text("Target 19/20"))))
        connection.submitViewAsync(listOf(moved), afk); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerEntityTeleport>().single().position.x shouldBe 0.5
        backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().single().metadataValue(23) shouldBe Component.text("Target 19/20")
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>() shouldBe emptyList()
        backend.clear()

        connection.submitViewAsync(listOf(moved.copy(x = 1.0)), rear); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerEntityTeleport>().single().position.x shouldBe 1.0
        backend.clear()
        backend.ordinaryTransactions = 0
        connection.submitViewAsync(emptyList(), afk); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single().entityIds.toList() shouldBe listOf(initial.entityId)
        java.lang.ref.Reference.reachabilityFence(channel)
    }

    "rear motion freezes admitted state and returns directly to the newest pose without entity churn" {
        val backend = RecordingPacketBackend()
        // The transport intentionally holds channels weakly. This test owns the connection
        // while first-use item registry initialization may trigger garbage collection.
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val away = PacketDisplayView(0.0, 64.0, 0.0, 0.0, 0.0, 1.0)
        val toward = away.copy(directionZ = -1.0)
        val initial = frame(content = itemContent(TestItem.DIAMOND)).copy(z = -3.0,
            metadata = PacketDisplayMetadata(itemContent(TestItem.DIAMOND),
                transform = DisplayTransform(scale = DisplayVector(0.51f, 0.51f, 0.51f)),
                interpolationDuration = 1, teleportDuration = 1, billboard = 3))
        val spawn = connection.submitViewAsync(listOf(initial), away)
        backend.runAll(); spawn.join()
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().single().entityId shouldBe initial.entityId
        backend.clear()
        val latest = initial.copy(x = 0.3, metadata = initial.metadata.copy(
            transform = initial.metadata.transform.copy(leftRotation = DisplayRotation(0f, 0.5f, 0f, 0.8660254f))))
        connection.submitViewAsync(listOf(initial.copy(x = 0.1)), away)
        connection.submitViewAsync(listOf(latest), away)
        backend.runAll()
        backend.writes shouldBe emptyList()

        val resume = connection.submitViewAsync(listOf(latest), toward)
        backend.runAll(); resume.join()
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>() shouldBe emptyList()
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>() shouldBe emptyList()
        backend.writes.filterIsInstance<WrapperPlayServerEntityTeleport>().single().position.x shouldBe 0.3
        backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().single().let {
            it.metadataValue(9) shouldBe 0
            it.metadataValue(10) shouldBe 0
        }
        backend.clear()
        connection.submitViewAsync(listOf(latest), toward); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().single().let {
            it.metadataValue(9) shouldBe 1
            it.metadataValue(10) shouldBe 1
        }
        backend.writes.filterIsInstance<WrapperPlayServerEntityTeleport>() shouldBe emptyList()
        backend.clear()
        connection.submitViewAsync(listOf(latest), toward); backend.runAll()
        backend.writes shouldBe emptyList()
        java.lang.ref.Reference.reachabilityFence(channel)
    }

    "either visible endpoint advances once and hidden cleanup or chunk replay is never culled" {
        val backend = RecordingPacketBackend()
        val connection = PacketDisplayAttachment(Any(), Logger.getAnonymousLogger(), backend)
        val view = PacketDisplayView(0.0, 64.0, 0.0, 0.0, 0.0, 1.0)
        val back = frame().copy(z = -8.0)
        val front = back.copy(z = 8.0)
        connection.submitViewAsync(listOf(back), view); backend.runAll(); backend.clear()
        connection.submitViewAsync(listOf(front), view); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerEntityTeleport>().single().position.z shouldBe 8.0
        backend.clear()
        connection.submitViewAsync(listOf(back), view); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerEntityTeleport>().single().position.z shouldBe -8.0
        backend.clear()
        val moved = back.copy(x = 0.25)
        connection.submitViewAsync(listOf(moved), view); backend.runAll()
        backend.writes shouldBe emptyList()
        connection.submitViewAsync(listOf(moved), view, resetChunks = setOf(back.chunkKey)); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single()
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().single().entityId shouldBe back.entityId
        backend.clear()
        connection.submitViewAsync(emptyList(), view); backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single().entityIds.toList() shouldBe listOf(back.entityId)
    }

    "rear suspension is per viewer and denied resumption retains the last admitted baseline" {
        val hiddenBackend = RecordingPacketBackend()
        val visibleBackend = RecordingPacketBackend()
        val hidden = PacketDisplayAttachment(Any(), Logger.getAnonymousLogger(), hiddenBackend)
        val visible = PacketDisplayAttachment(Any(), Logger.getAnonymousLogger(), visibleBackend)
        val away = PacketDisplayView(0.0, 64.0, 0.0, 0.0, 0.0, 1.0)
        val toward = away.copy(directionZ = -1.0)
        val first = frame().copy(z = -8.0, metadata = frame().metadata.copy(interpolationDuration = 1, teleportDuration = 1))
        hidden.submitViewAsync(listOf(first), away); hiddenBackend.runAll(); hiddenBackend.clear()
        visible.submitViewAsync(listOf(first), toward); visibleBackend.runAll(); visibleBackend.clear()
        val next = first.copy(x = 0.2)
        hidden.submitViewAsync(listOf(next), away); hiddenBackend.runAll()
        visible.submitViewAsync(listOf(next), toward); visibleBackend.runAll()
        hiddenBackend.writes shouldBe emptyList()
        visibleBackend.writes.filterIsInstance<WrapperPlayServerEntityTeleport>().single().position.x shouldBe 0.2
        hiddenBackend.ordinaryTransactions = 0
        hidden.submitViewAsync(listOf(next), toward)
        hiddenBackend.tasks.removeFirst().invoke() // admit immutable view
        hiddenBackend.tasks.removeFirst().invoke() // budget denies the snap transaction
        hiddenBackend.writes shouldBe emptyList()
        hiddenBackend.ordinaryTransactions = Int.MAX_VALUE
        hiddenBackend.runAll()
        hiddenBackend.writes.filterIsInstance<WrapperPlayServerEntityTeleport>().single().position.x shouldBe 0.2
        hiddenBackend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().single().metadataValue(9) shouldBe 0
    }

    "async admission touches frames only on the event loop and terminal cleanup waits for admission" {
        val backend = RecordingPacketBackend()
        val connection = PacketDisplayAttachment(Any(), Logger.getAnonymousLogger(), backend)
        var reads = 0
        val captured = object : AbstractList<PacketDisplayFrame>() {
            override val size: Int get() { reads++; return 1 }
            override fun get(index: Int): PacketDisplayFrame { reads++; return frame() }
        }
        val admission = connection.submitAsync(captured)
        reads shouldBe 0
        admission.isDone shouldBe false
        val closing = connection.closeAsync()
        closing.isDone shouldBe false
        backend.runAll()
        admission.isCompletedExceptionally shouldBe false
        closing.isDone shouldBe true
        closing.isCompletedExceptionally shouldBe false
        // Closing can coalesce an unsent display; it cannot leave a received ID behind.
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().forEach { spawn ->
            backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().flatMap { it.entityIds.toList() } shouldContain spawn.entityId
        }
        connection.submitAsync(listOf(frame())).isCompletedExceptionally shouldBe true
    }

    "a blocked event-loop frame merge does not block native connection reuse or terminal admission" {
        val backend = RecordingPacketBackend()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), backend)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val captured = object : AbstractList<PacketDisplayFrame>() {
            override val size = 1
            override fun get(index: Int): PacketDisplayFrame {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return frame()
            }
        }
        connection.submitAsync(captured)
        val worker = Thread({ backend.tasks.removeFirst().invoke() }, "test-packet-event-loop")
        worker.start()
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Frame preparation owns its monitor here. These native-thread operations must not.
            val started = System.nanoTime()
            connection.isUsableFor(channel) shouldBe true
            val closing = connection.closeAsync()
            (System.nanoTime() - started < 1_000_000_000L) shouldBe true
            closing.isDone shouldBe false
            release.countDown()
            worker.join(5000)
            worker.isAlive shouldBe false
            backend.runAll()
            closing.join()
        } finally { release.countDown(); worker.join(5000) }
    }

    "terminal completion waits for writable cleanup and detached observers cannot cancel it" {
        val backend = RecordingPacketBackend()
        val connection = PacketDisplayAttachment(Any(), Logger.getAnonymousLogger(), backend)
        connection.submit(listOf(frame())); backend.runAll(); backend.clear()
        backend.writable = false
        val cancelledObserver = connection.closeAsync()
        cancelledObserver.cancel(false)
        val observed = connection.closeAsync()
        backend.tasks.removeFirst().invoke() // terminal admission
        backend.tasks.removeFirst().invoke() // cleanup denied and scheduled for retry
        observed.isDone shouldBe false
        backend.writable = true
        backend.runAll()
        observed.join()
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single().entityIds.toList() shouldContain 100
    }

    "lost channel fails terminal completion instead of claiming delivered cleanup" {
        val backend = RecordingPacketBackend()
        val connection = PacketDisplayAttachment(Any(), Logger.getAnonymousLogger(), backend)
        connection.submit(listOf(frame())); backend.runAll()
        val closing = connection.closeAsync()
        backend.open = false
        backend.runAll()
        closing.isCompletedExceptionally shouldBe true
    }

    "a display owner can close before PacketEvents is initialized" {
        val api = PacketEvents.getAPI()
        PacketEvents.setAPI(null)
        try {
            PacketEventsDisplayTransport(Logger.getAnonymousLogger(), RecordingPacketBackend()).close()
        } finally {
            PacketEvents.setAPI(api)
        }
    }

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

    "delayed mount admission keeps the initial display at its captured passenger origin" {
        val backend = RecordingPacketBackend().apply { ordinaryTransactions = 1 }
        val connection = PacketDisplayAttachment(Any(), Logger.getAnonymousLogger(), backend)
        val initial = frame()
        val mounted = initial.copy(y = 65.8, attachmentVehicleId = 77,
            metadata = initial.metadata.copy(transform = DisplayTransform(translation = DisplayVector(0f, .35f, 0f))))
        connection.submit(listOf(mounted), desiredPassengers = mapOf(77 to listOf(mounted.entityId)))
        backend.tasks.removeFirst().invoke()
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().size shouldBe 1
        backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().size shouldBe 1
        backend.writes.filterIsInstance<WrapperPlayServerSetPassengers>().size shouldBe 0
        backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().single().position.y shouldBe 65.8
        (backend.writes.filterIsInstance<WrapperPlayServerEntityMetadata>().single().metadataValue(11)
            as com.github.retrooper.packetevents.util.Vector3f).y shouldBe .35f

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
        val logs = CapturedLogs()
        val connection = PacketDisplayAttachment(channel, logs.logger, backend)
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
        logs.records.map { it.level } shouldBe listOf(Level.WARNING)
    }

    "a queued batch on a closed channel invalidates quietly and a replacement replays the scene" {
        val backend = RecordingPacketBackend()
        val logs = CapturedLogs()
        val channel = Any()
        val connection = PacketDisplayAttachment(channel, logs.logger, backend)
        val display = frame()

        connection.submit(listOf(display))
        backend.runAll()
        backend.clear()

        // Enqueue while open, then close before the event-loop drain runs.
        connection.submit(listOf(display.copy(x = 1.0)))
        backend.open = false
        backend.runAll()

        backend.writes shouldBe emptyList()
        backend.flushes shouldBe 0
        connection.isUsableFor(channel) shouldBe false
        logs.records shouldBe emptyList()

        // Cleanup IDs survive the closed-channel invalidation and remain usable if
        // this same channel is still available to finish its ordered cleanup.
        backend.open = true
        connection.submit(emptyList())
        backend.runAll()
        backend.writes.filterIsInstance<WrapperPlayServerDestroyEntities>().single()
            .entityIds.toList() shouldBe listOf(display.entityId)
        connection.deferReplacementUntilCleanup(channel) shouldBe false

        val replacementChannel = Any()
        connection.deferReplacementUntilCleanup(replacementChannel) shouldBe false
        backend.clear()
        val replacement = PacketDisplayAttachment(replacementChannel, logs.logger, backend)
        replacement.submit(listOf(display.copy(x = 2.0)))
        backend.runAll()

        val replay = backend.writes.filterIsInstance<WrapperPlayServerSpawnEntity>().single()
        replay.entityId shouldBe display.entityId
        replay.position.x shouldBe 2.0
        logs.records shouldBe emptyList()
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
    "two independent display queues interleave mounts and one owner's cleanup preserves the other" {
        val service = SharedDisplayPassengers()
        val channel = Any()
        val aState = PacketDisplayPassengerState(service.open(channel))
        val bState = PacketDisplayPassengerState(service.open(channel))
        val aBackend = RecordingPacketBackend()
        val bBackend = RecordingPacketBackend()
        val a = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), aBackend, aState)
        val b = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), bBackend, bState)
        val first = frame(100).copy(attachmentVehicleId = 77)
        val second = frame(200).copy(attachmentVehicleId = 77)
        a.submit(listOf(first), desiredPassengers = mapOf(77 to listOf(100)), nativePassengerSnapshots = mapOf(77 to listOf(11)))
        aBackend.runAll()
        b.submit(listOf(second), desiredPassengers = mapOf(77 to listOf(200)), nativePassengerSnapshots = mapOf(77 to listOf(11)))
        bBackend.runAll()
        bBackend.writes.filterIsInstance<WrapperPlayServerSetPassengers>().single().passengers.toList() shouldBe listOf(11, 100, 200)
        aState.observeNative(77, intArrayOf(12)).toList() shouldBe listOf(12, 100, 200)
        aBackend.clear()
        val closed = a.closeAsync(); aBackend.runAll(); closed.join()
        aBackend.writes.filterIsInstance<WrapperPlayServerSetPassengers>().single().passengers.toList() shouldBe listOf(12, 200)
        bState.nativePassengers(77) shouldBe listOf(12)
        bBackend.clear()
        b.closeAsync(); bBackend.runAll()
        bBackend.writes.filterIsInstance<WrapperPlayServerSetPassengers>().single().passengers.toList() shouldBe listOf(12)
        service.close()
        java.lang.ref.Reference.reachabilityFence(channel)
    }

    "rejected and partially written links never erase another owner's admitted passenger" {
        val service = SharedDisplayPassengers()
        val channel = Any()
        val aState = PacketDisplayPassengerState(service.open(channel))
        val bState = PacketDisplayPassengerState(service.open(channel))
        val aBackend = RecordingPacketBackend().apply { ordinaryTransactions = 1 }
        val bBackend = RecordingPacketBackend()
        val a = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), aBackend, aState)
        val b = PacketDisplayAttachment(channel, Logger.getAnonymousLogger(), bBackend, bState)
        val first = frame(100).copy(attachmentVehicleId = 77)
        val second = frame(200).copy(attachmentVehicleId = 77)
        b.submit(listOf(second), desiredPassengers = mapOf(77 to listOf(200)), nativePassengerSnapshots = mapOf(77 to listOf(11)))
        bBackend.runAll()
        a.submit(listOf(first), desiredPassengers = mapOf(77 to listOf(100)), nativePassengerSnapshots = mapOf(77 to listOf(11)))
        aBackend.tasks.removeFirst().invoke() // spawn admitted; link rejected
        bState.observeNative(77, intArrayOf(12)).toList() shouldBe listOf(12, 200)
        aBackend.clear()
        aBackend.ordinaryTransactions = 1
        aBackend.failOnFlush = 1 // mount may have reached the channel
        aBackend.runAll()
        bState.nativePassengers(77) shouldBe listOf(12, 100)
        aBackend.clear(); aBackend.failOnFlush = null
        a.deferReplacementUntilCleanup(channel) shouldBe true
        aBackend.runAll()
        aBackend.writes.filterIsInstance<WrapperPlayServerSetPassengers>().single().passengers.toList() shouldBe listOf(12, 200)
        bState.nativePassengers(77) shouldBe listOf(12)
        b.closeAsync(); bBackend.runAll()
        service.close()
        java.lang.ref.Reference.reachabilityFence(channel)
    }

})

internal class RecordingPacketBackend(
    var failOnWrite: Int? = null,
) : PacketDisplayChannelBackend {
    val tasks = ArrayDeque<() -> Unit>()
    val writes = mutableListOf<PacketWrapper<*>>()
    var flushes = 0
        private set
    var failEnqueue = false
    var writable = true
    var open = true
    var ordinaryTransactions = Int.MAX_VALUE
    var onWrite: ((PacketWrapper<*>) -> Unit)? = null
    var failOnFlush: Int? = null
    private var writeCount = 0

    override fun isOpen(channel: Any): Boolean = open

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

private class CapturedLogs {
    val records = mutableListOf<LogRecord>()
    val logger = Logger.getAnonymousLogger().apply {
        level = Level.ALL
        useParentHandlers = false
        filter = Filter { record ->
            records += record
            true
        }
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
