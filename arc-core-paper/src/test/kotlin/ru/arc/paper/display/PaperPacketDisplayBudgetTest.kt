package ru.arc.paper.display

import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItemStack
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.player.TestPaperPlugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.function.BooleanSupplier
import java.util.logging.Filter
import java.util.logging.LogRecord
import java.util.logging.Level

class PaperPacketDisplayBudgetTest : FreeSpec({
    "disconnect cancellation is quiet but admission failures stay observable and the next sweep retries" {
        for (failure in listOf(CancellationException("channel closed"),
            CompletionException(CompletionException(CancellationException("channel closed"))),
            IllegalStateException("encoder failed", CancellationException("not an expected disconnect")))) {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadPlugin<TestPaperPlugin>()
                val player = paper.addPlayer("Viewer")
                val world = paper.addSimpleWorld("disconnect")
                val scheduler = TestTaskScheduler()
                val work = BudgetedTaskQueue(LifecycleTaskScope(scheduler), maximumNanos = Long.MAX_VALUE)
                val cleanup = QueueCleanup()
                val transport = BudgetTransport().apply { admission = CompletableFuture.failedFuture(failure) }
                val displays = PaperPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()),
                    fixedAudience(player, world.uid), work, false, cleanup)
                val records = mutableListOf<LogRecord>()
                val previousLevel = plugin.logger.level
                plugin.logger.level = Level.ALL
                val previousFilter = plugin.logger.filter
                plugin.logger.filter = Filter { records.add(it); false }
                try {
                    displays.spawnText(Location(world, 0.0, 64.0, 0.0), Component.text("one"))
                    val failed = displays.refresh()
                    scheduler.tick(20)
                    failed.isCompletedExceptionally shouldBe true
                    records.map { it.message } shouldBe if (failure.javaClass == IllegalStateException::class.java) listOf(
                        "Packet display admission failed", "Packet display capture failed; the next sweep will retry",
                    ) else emptyList()
                    transport.admission = CompletableFuture.completedFuture(null)
                    val retry = displays.refresh()
                    scheduler.tick(20)
                    retry.join()
                    transport.frames.size shouldBe 2
                    val closing = displays.closeAsync(); cleanup.drain(closing)
                } finally { plugin.logger.filter = previousFilter; plugin.logger.level = previousLevel; work.close() }
            }
        }
    }

    "forgetting a disconnected viewer stays quiet without claiming successful cleanup" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val player = paper.addPlayer("Viewer")
            val world = paper.addSimpleWorld("quit")
            val scheduler = TestTaskScheduler()
            val work = BudgetedTaskQueue(LifecycleTaskScope(scheduler), maximumNanos = Long.MAX_VALUE)
            val cleanup = QueueCleanup(); val transport = BudgetTransport()
            var online = true
            val audience = object : TestPacketDisplayAudience() {
                override fun checkThread() = Unit
                override fun capture() = if (online) fixedAudience(player, world.uid).capture() else emptyList()
            }
            val displays = PaperPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), audience, work, false, cleanup)
            val records = mutableListOf<LogRecord>()
            val previousFilter = plugin.logger.filter
            plugin.logger.filter = Filter { records.add(it); false }
            try {
                displays.spawnText(Location(world, 0.0, 64.0, 0.0), Component.text("one"))
                val initial = displays.refresh(); scheduler.tick(20); initial.join()
                online = false
                transport.terminal = CompletableFuture.failedFuture(CancellationException("Packet display channel is closed"))
                val forgotten = displays.refresh(); scheduler.tick(20)
                forgotten.isCompletedExceptionally shouldBe true
                transport.closeAsync().isCompletedExceptionally shouldBe true
                val next = displays.refresh(); scheduler.tick(20); next.join()
                records shouldBe emptyList()
                val closing = displays.closeAsync(); cleanup.drain(closing)
            } finally { plugin.logger.filter = previousFilter; work.close() }
        }
    }

    "large plain and grouped scenes admit every consecutive frame within 128 steps" {
        for ((handleCount, viewerCount, grouped) in listOf(Triple(168, 1, false), Triple(200, 4, true), Triple(336, 1, false), Triple(336, 2, true))) {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadPlugin<TestPaperPlugin>()
                val players = List(viewerCount) { paper.addPlayer("Viewer-$it") }
                val world = paper.addSimpleWorld("large-frames")
                val scheduler = TestTaskScheduler()
                val work = BudgetedTaskQueue(LifecycleTaskScope(scheduler), maximumSteps = 128, maximumNanos = 2_000_000L, nanoTime = { 0 })
                val cleanup = QueueCleanup(); val transport = BudgetTransport()
                var tick = 0L
                var groupCalls = 0
                val audience = object : TestPacketDisplayAudience() {
                    override fun checkThread() = Unit
                    override fun capture() = players.map { PacketDisplayViewer(it, it.uniqueId, world.uid, it.entityId,
                        emptySet(), 0.0, 64.0, 0.0, setOf(0L)) }
                }
                val displays = PaperPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), audience,
                    work, false, cleanup, java.util.function.LongSupplier { tick })
                val group = if (grouped) displays.createAudienceGroup {
                    org.bukkit.Bukkit.isPrimaryThread() shouldBe true
                    groupCalls++
                    true
                } else null
                val handles = List(handleCount) {
                    displays.spawnText(Location(world, 0.0, 64.0, 0.0), Component.text("moving")).apply { audienceGroup = group }
                }
                val ids = handles.map { it.entityId }.toSet()
                repeat(4) { frame ->
                    if (frame > 0) { tick++; scheduler.tick() }
                    handles.forEach { it.teleport(Location(world, frame.toDouble(), 64.0, 0.0)) }
                    displays.refresh().isDone shouldBe true
                    transport.frames.size shouldBe (frame + 1) * viewerCount
                    transport.frames.takeLast(viewerCount).forEach { captured ->
                        captured.size shouldBe handleCount
                        captured.map { it.entityId }.toSet() shouldBe ids
                        captured.map { it.x }.toSet() shouldBe setOf(frame.toDouble())
                    }
                    groupCalls shouldBe if (grouped) (frame + 1) * viewerCount else 0
                }
                transport.frames.first().map { it.x }.toSet() shouldBe setOf(0.0)
                val closing = displays.closeAsync(); cleanup.drain(closing); work.close()
            }
        }
    }

    "uncached audience predicates retain individual native budget steps" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val player = paper.addPlayer("Viewer")
            val world = paper.addSimpleWorld("native-predicates")
            val scheduler = TestTaskScheduler()
            val work = BudgetedTaskQueue(LifecycleTaskScope(scheduler), 4, Long.MAX_VALUE)
            val cleanup = QueueCleanup(); val transport = BudgetTransport()
            val displays = PaperPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()),
                fixedAudience(player, world.uid), work, false, cleanup)
            var calls = 0
            repeat(30) {
                displays.spawnText(Location(world, 0.0, 64.0, 0.0), Component.empty()).audienceGroup =
                    displays.createAudienceGroup { calls++; true }
            }
            val sweep = displays.refresh()
            scheduler.tick()
            calls shouldBe 1 // Audience plus two frame chunks leave one native predicate step.
            scheduler.tick()
            calls shouldBe 5
            sweep.isDone shouldBe false
            transport.frames shouldBe emptyList()
            scheduler.tick(20)
            sweep.join()
            calls shouldBe 30
            transport.frames.single().size shouldBe 30
            val closing = displays.closeAsync(); cleanup.drain(closing); work.close()
        }
    }

    "short frames use the current tick allowance and async admission completes without a native polling tick" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val player = paper.addPlayer("FastViewer")
            val world = paper.addSimpleWorld("short-frames")
            val scheduler = TestTaskScheduler()
            val work = BudgetedTaskQueue(LifecycleTaskScope(scheduler), nanoTime = { 0 })
            val cleanup = QueueCleanup()
            val transport = BudgetTransport()
            var tick = 0L
            val displays = PaperPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()),
                fixedAudience(player, world.uid), work, false, cleanup, java.util.function.LongSupplier { tick })
            val handle = displays.spawnText(Location(world, 0.0, 64.0, 0.0), Component.text("moving"))
            displays.refresh().isDone shouldBe true
            transport.frames.size shouldBe 1
            repeat(3) {
                tick++
                scheduler.tick()
                handle.teleport(Location(world, tick.toDouble(), 64.0, 0.0))
                displays.refresh().isDone shouldBe true
                transport.frames.size shouldBe tick.toInt() + 1
            }
            tick++
            transport.admission = CompletableFuture()
            val pending = displays.refresh()
            pending.isDone shouldBe false
            val sent = transport.frames.size
            handle.teleport(Location(world, 10.0, 64.0, 0.0))
            (pending === displays.refresh()) shouldBe true
            transport.frames.size shouldBe sent
            transport.admission.complete(null)
            pending.isDone shouldBe true // No scheduler.tick() is needed to release the capture gate.
            tick++
            displays.refresh().isDone shouldBe true
            transport.frames.last().single().x shouldBe 10.0
            val closing = displays.closeAsync(); cleanup.drain(closing); work.close()
        }
    }

    "two owners share the same native budget and coalesce a partially captured sweep" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val scheduler = TestTaskScheduler()
            val work = BudgetedTaskQueue(LifecycleTaskScope(scheduler), 8, Long.MAX_VALUE)
            val cleanup = QueueCleanup()
            var calls = 0
            val audience = object : PacketDisplayAudienceSource {
                override fun checkThread() = Unit
                override fun isCurrent(viewer: PacketDisplayViewer) = true
                override fun captureSteps(accept: (PacketDisplayViewer) -> Unit) = sequence {
                    repeat(40) { calls++; yield(Unit) }
                }.iterator()
            }
            val a = PaperPacketDisplays(plugin, BudgetTransport(), LifecycleTaskScope(TestTaskScheduler()), audience, work, false, cleanup)
            val b = PaperPacketDisplays(plugin, BudgetTransport(), LifecycleTaskScope(TestTaskScheduler()), audience, work, false, cleanup)
            val world = paper.addSimpleWorld("budget")
            repeat(300) { a.spawnText(Location(world, 0.0, 64.0, 0.0), Component.empty()) }
            b.spawnText(Location(world, 0.0, 64.0, 0.0), Component.empty())
            val first = a.refresh(); val second = b.refresh()
            (first === a.refresh()) shouldBe true
            calls shouldBe 0
            scheduler.tick()
            calls shouldBe 8
            first.isDone shouldBe false; second.isDone shouldBe false
            repeat(100) { scheduler.tick() }
            first.join(); second.join()
            calls shouldBe 80
            val ca = a.closeAsync(); val cb = b.closeAsync()
            cleanup.drain(ca, cb)
            work.close()
        }
    }

    "close is nonwaiting and keeps the transport until accepted admission and actual cleanup finish" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val player = paper.addPlayer("Viewer")
            val world = paper.addSimpleWorld("drain")
            val scheduler = TestTaskScheduler()
            val work = BudgetedTaskQueue(LifecycleTaskScope(scheduler), 8, Long.MAX_VALUE)
            val cleanup = QueueCleanup()
            val transport = BudgetTransport().apply {
                admission = CompletableFuture(); terminal = CompletableFuture()
            }
            val audience = fixedAudience(player, world.uid)
            val displays = PaperPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), audience, work, false, cleanup)
            val handle = displays.spawnText(Location(world, 0.0, 64.0, 0.0), Component.text("one"))
            val sweep = displays.refresh()
            repeat(10) { scheduler.tick() }
            transport.frames.size shouldBe 1
            sweep.isDone shouldBe false
            val closing = displays.closeAsync()
            handle.isValid shouldBe false
            transport.closed shouldBe false
            closing.isDone shouldBe false
            cleanup.scheduler.tick(10)
            transport.closed shouldBe false
            transport.admission.complete(null)
            cleanup.scheduler.tick()
            closing.isDone shouldBe false
            transport.terminal.complete(null)
            cleanup.drain(closing)
            transport.closed shouldBe true
            work.close()
        }
    }

    "host stop cancels incomplete cleanup and never reports successful drain" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val work = BudgetedTaskQueue(LifecycleTaskScope(TestTaskScheduler()))
            val cleanup = QueueCleanup()
            val transport = BudgetTransport()
            val displays = PaperPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), fixedAudience(null, UUID.randomUUID()), work, false, cleanup)
            var cleaned = 0
            displays.participate(object : PacketDisplayCaptureParticipant {
                override fun captureSteps(viewers: List<PacketDisplayViewer>) = emptyList<Unit>().iterator()
                override fun closeSteps() = sequence { repeat(500) { cleaned++; yield(Unit) } }.iterator()
            })
            val closing = displays.closeAsync()
            cleanup.scheduler.tick()
            cleaned shouldBe 3
            closing.isDone shouldBe false
            cleanup.work.close()
            closing.isCompletedExceptionally shouldBe true
            transport.closed shouldBe true
            work.close()
        }
    }

    "privacy revoked midway through a large capture does not publish the prepared audience" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<TestPaperPlugin>()
            val player = paper.addPlayer("PrivateViewer")
            val world = paper.addSimpleWorld("privacy")
            val scheduler = TestTaskScheduler()
            val work = BudgetedTaskQueue(LifecycleTaskScope(scheduler), 4, Long.MAX_VALUE)
            val cleanup = QueueCleanup(); val transport = BudgetTransport()
            val displays = PaperPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), fixedAudience(player, world.uid), work, false, cleanup)
            var groupCalls = 0
            val group = displays.createAudienceGroup { groupCalls++; true }
            val handles = List(30) {
                displays.spawnText(Location(world, 0.0, 64.0, 0.0), Component.empty()).apply { audienceGroup = group }
            }
            val sweep = displays.refresh()
            scheduler.tick() // Audience, two frame chunks, then the first eligible group's native predicate.
            groupCalls shouldBe 1
            sweep.isDone shouldBe false
            transport.frames shouldBe emptyList()
            handles.forEach { it.hideFrom(player) }
            repeat(100) { scheduler.tick() }
            sweep.join()
            transport.frames shouldBe emptyList()
            val closing = displays.closeAsync(); cleanup.drain(closing); work.close()
        }
    }
})

private fun fixedAudience(player: Player?, world: UUID) = object : TestPacketDisplayAudience() {
    override fun checkThread() = Unit
    override fun capture() = player?.let { listOf(PacketDisplayViewer(it, it.uniqueId, world, it.entityId,
        emptySet(), 0.0, 64.0, 0.0, setOf(0L))) }.orEmpty()
}
private class QueueCleanup : ArcNativeCleanupService {
    val scheduler = TestTaskScheduler()
    val work = BudgetedTaskQueue(LifecycleTaskScope(scheduler), 3, Long.MAX_VALUE)
    override fun submit(owner: String, step: BooleanSupplier): CompletionStage<Void> =
        work.submit({ true }, step::getAsBoolean).thenApply<Void> { null }.minimalCompletionStage()
    fun drain(vararg futures: CompletableFuture<Void>) {
        var ticks = 0
        while (futures.any { !it.isDone }) { check(++ticks < 1000); scheduler.tick() }
        futures.forEach { it.join() }
    }
}
private class BudgetTransport : PacketDisplayTransport, PacketDisplayConnection {
    var closed = false
    var admission = CompletableFuture.completedFuture<Void>(null)
    var terminal = CompletableFuture.completedFuture<Void>(null)
    val frames = mutableListOf<List<PacketDisplayFrame>>()
    private var next = 0
    override val identity = Any()
    override fun blockStateId(block: BlockData): Int = error("Not used")
    override fun itemSnapshot(item: ItemStack): PacketItemStack = error("Not used")
    override fun nextEntityId() = ++next
    override fun connection(player: Player): PacketDisplayConnection = this
    override fun forget(player: Player) = Unit
    override fun close() { closed = true }
    override fun closeAsync() = terminal.copy()
    override fun submit(desired: List<PacketDisplayFrame>, resetChunks: Set<Long>, resetAll: Boolean,
        desiredPassengers: Map<Int,List<Int>>, nativePassengerSnapshots: Map<Int,List<Int>>, liveVehicleIds: Set<Int>?) { frames.add(desired) }
    override fun submitAsync(desired: List<PacketDisplayFrame>, resetChunks: Set<Long>, resetAll: Boolean,
        desiredPassengers: Map<Int,List<Int>>, nativePassengerSnapshots: Map<Int,List<Int>>, liveVehicleIds: Set<Int>?): CompletableFuture<Void> {
        frames.add(desired); return admission.copy()
    }
}
