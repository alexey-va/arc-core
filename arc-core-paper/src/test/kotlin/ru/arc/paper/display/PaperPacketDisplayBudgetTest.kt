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
import java.util.concurrent.CompletionStage
import java.util.function.BooleanSupplier

class PaperPacketDisplayBudgetTest : FreeSpec({
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
            val handles = List(30) { displays.spawnText(Location(world, 0.0, 64.0, 0.0), Component.empty()) }
            val sweep = displays.refresh()
            scheduler.tick(10) // audience and frame capture finished; the visibility pass is partial
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
