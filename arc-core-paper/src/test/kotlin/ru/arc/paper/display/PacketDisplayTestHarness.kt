package ru.arc.paper.display

import org.bukkit.plugin.Plugin
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcNativeCleanupService
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.BooleanSupplier

/** Deterministic ports for the existing geometry/privacy oracles. Async tests use the real queues. */
internal abstract class TestPacketDisplayAudience : PacketDisplayAudienceSource {
    override fun isCurrent(viewer: PacketDisplayViewer) = true
    abstract fun capture(): List<PacketDisplayViewer>
    final override fun captureSteps(accept: (PacketDisplayViewer) -> Unit): Iterator<Unit> = sequence {
        for (viewer in capture()) { accept(viewer); yield(Unit) }
    }.iterator()
}

private val schedulers = WeakHashMap<PaperPacketDisplays, TestTaskScheduler>()
internal fun testPacketDisplays(plugin: Plugin, transport: PacketDisplayTransport,
    tasks: LifecycleTaskScope, audience: PacketDisplayAudienceSource): PaperPacketDisplays {
    val scheduler = TestTaskScheduler()
    val queue = BudgetedTaskQueue(LifecycleTaskScope(scheduler), maximumNanos = Long.MAX_VALUE)
    val cleanup = object : ArcNativeCleanupService {
        override fun submit(owner: String, step: BooleanSupplier): CompletionStage<Void> {
            return try {
                var count = 0
                while (!step.asBoolean) check(++count < 100_000) { "Test cleanup did not finish" }
                CompletableFuture.completedFuture(null)
            } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
        }
    }
    return PaperPacketDisplays(plugin, transport, tasks, audience, queue, true, cleanup).also { schedulers[it] = scheduler }
}
internal fun PaperPacketDisplays.refreshFully() {
    val done = refresh()
    var ticks = 0
    while (!done.isDone) { check(++ticks < 10_000); schedulers.getValue(this).tick() }
    done.join()
}
