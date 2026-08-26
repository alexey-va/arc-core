package ru.arc.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import ru.arc.paper.player.TestPaperPlugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.RejectedExecutionException
import kotlin.time.Duration.Companion.milliseconds

class BukkitTaskSchedulerTest : FreeSpec({

    lateinit var paper: MockBukkitTestRuntime
    lateinit var plugin: TestPaperPlugin
    lateinit var scheduler: BukkitTaskScheduler

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        plugin = paper.loadPlugin<TestPaperPlugin>()
        scheduler = BukkitTaskScheduler(plugin)
    }

    afterEach {
        paper.close()
    }

    "one-shot tasks are untracked after execution" {
        val counter = AtomicInteger()
        scheduler.runLater(1) { counter.incrementAndGet() }
        scheduler.trackedCount() shouldBe 1
        paper.performTicks(1)
        counter.get() shouldBe 1
        scheduler.trackedCount() shouldBe 0
    }

    "repeating tasks stay tracked until cancelled" {
        val counter = AtomicInteger()
        val task = scheduler.runTimer(1, 1) { counter.incrementAndGet() }
        scheduler.trackedCount() shouldBe 1
        paper.performTicks(3)
        counter.get() shouldBe 3
        scheduler.trackedCount() shouldBe 1
        task.cancel()
        scheduler.trackedCount() shouldBe 0
    }

    "cancelled one-shot task is untracked before execution" {
        val counter = AtomicInteger()
        val task = scheduler.runLater(5) { counter.incrementAndGet() }
        scheduler.trackedCount() shouldBe 1
        task.cancel()
        scheduler.trackedCount() shouldBe 0
        paper.performTicks(5)
        counter.get() shouldBe 0
    }

    "Paper subtick scheduler closes its owned executor" {
        val subtick = PaperSubtickScheduler(BukkitTaskScheduler(plugin), plugin)

        subtick.close()

        subtick.isSubtickExecutorShutdown() shouldBe true
    }

    "rejected subtick task is not retained after close" {
        val subtick = PaperSubtickScheduler(BukkitTaskScheduler(plugin), plugin)
        subtick.close()

        shouldThrow<RejectedExecutionException> {
            subtick.runLater(25.milliseconds) {}
        }

        subtick.subtickTrackedCount() shouldBe 0
    }
})
