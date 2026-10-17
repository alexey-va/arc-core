package ru.arc.paper.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicReference

class PaperNativeCleanupServiceProviderTest : FreeSpec({
    "all consumers share a fair per-tick budget and completion follows the final native step" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcCore")
            PaperNativeCleanupServiceProvider(host, 2, 1_000_000L) { 0L }.use { service ->
                val calls = mutableListOf<String>()
                var firstRemaining = 3
                var secondRemaining = 2
                val first = service.submit("first:entities") {
                    Bukkit.isPrimaryThread() shouldBe true
                    calls += "first"
                    --firstRemaining == 0
                }.toCompletableFuture()
                val second = service.submit("second:tickets") {
                    calls += "second"
                    --secondRemaining == 0
                }.toCompletableFuture()

                calls shouldBe emptyList()
                paper.performTicks(1)
                calls shouldBe listOf("first", "second")
                first.isDone shouldBe false
                second.isDone shouldBe false
                paper.performTicks(1)
                calls shouldBe listOf("first", "second", "first", "second")
                second.isDone shouldBe true
                first.isDone shouldBe false
                paper.performTicks(1)
                first.join() shouldBe null
                firstRemaining shouldBe 0
            }
        }
    }

    "consumer disable and global task reset do not cancel accepted host cleanup" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcCore")
            val consumer = paper.createSimplePlugin("ArcCrates")
            PaperNativeCleanupServiceProvider(host, 2, 1_000_000L) { 0L }.use { service ->
                var remaining = 5
                val cleanup = service.submit("ArcCrates:shutdown") { --remaining == 0 }.toCompletableFuture()
                try {
                    PaperArcRuntime.installScheduling(consumer)
                    paper.server.pluginManager.disablePlugin(consumer)
                    Tasks.reset()
                    paper.server.servicesManager.load(ArcNativeCleanupService::class.java) shouldBe service
                    paper.performTicks(2)
                    remaining shouldBe 1
                    cleanup.isDone shouldBe false
                    paper.performTicks(1)
                    cleanup.join() shouldBe null
                } finally { Tasks.reset() }
            }
        }
    }

    "observers cannot cancel accepted cleanup but a failing cursor fails its own observation" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcCore")
            PaperNativeCleanupServiceProvider(host, 2, 1_000_000L) { 0L }.use { service ->
                var remaining = 3
                val accepted = service.submit("cleanup") { --remaining == 0 }
                accepted.toCompletableFuture().cancel(false) shouldBe true
                val failure = IllegalStateException("native failure")
                val failed = service.submit("failed") { throw failure }.toCompletableFuture()
                paper.performTicks(3)
                remaining shouldBe 0
                accepted.toCompletableFuture().join() shouldBe null
                shouldThrow<CompletionException> { failed.join() }.cause shouldBe failure
            }
        }
    }

    "host close fails pending observations and removes every scheduled callback" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcCore")
            val service = PaperNativeCleanupServiceProvider(host, 2, 1_000_000L) { 0L }
            var calls = 0
            val pending = service.submit("pending") { calls++; false }.toCompletableFuture()
            service.close()
            service.close()
            pending.isCompletedExceptionally shouldBe true
            service.submit("late") { calls++; true }.toCompletableFuture().isCompletedExceptionally shouldBe true
            paper.server.servicesManager.load(ArcNativeCleanupService::class.java) shouldBe null
            paper.server.scheduler.pendingTasks.count { it.owner === host && !it.isCancelled } shouldBe 0
            paper.performTicks(3)
            calls shouldBe 0
        }
    }

    "duplicate registration preserves the existing service and off-thread admission is rejected" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcCore")
            val other = paper.createSimplePlugin("Other")
            PaperNativeCleanupServiceProvider(host).use { service ->
                shouldThrow<IllegalStateException> { PaperNativeCleanupServiceProvider(other) }
                paper.server.servicesManager.load(ArcNativeCleanupService::class.java) shouldBe service
                shouldThrow<IllegalArgumentException> { service.submit("bad owner") { true } }
                val failure = AtomicReference<Throwable>()
                val worker = Thread {
                    try { service.submit("worker") { true } } catch (error: Throwable) { failure.set(error) }
                }
                worker.start()
                worker.join(5_000)
                worker.isAlive shouldBe false
                (failure.get() is IllegalStateException) shouldBe true
                val cleanup = service.submit("valid") { true }.toCompletableFuture()
                paper.performTicks(1)
                cleanup.join() shouldBe null
            }
        }
    }
})
