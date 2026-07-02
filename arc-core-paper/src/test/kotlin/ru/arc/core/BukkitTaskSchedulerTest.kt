package ru.arc.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import ru.arc.paper.player.TestPaperPlugin
import java.util.concurrent.atomic.AtomicInteger

class BukkitTaskSchedulerTest : FreeSpec({

    lateinit var server: ServerMock
    lateinit var plugin: TestPaperPlugin
    lateinit var scheduler: BukkitTaskScheduler

    beforeSpec {
        MockBukkit.mock()
        server = MockBukkit.getMock()!!
        plugin = MockBukkit.load(TestPaperPlugin::class.java)
    }

    afterSpec {
        MockBukkit.unmock()
    }

    beforeEach {
        scheduler = BukkitTaskScheduler(plugin)
    }

    "one-shot tasks are untracked after execution" {
        val counter = AtomicInteger()
        scheduler.runLater(1) { counter.incrementAndGet() }
        scheduler.trackedCount() shouldBe 1
        server.scheduler.performTicks(1)
        counter.get() shouldBe 1
        scheduler.trackedCount() shouldBe 0
    }

    "repeating tasks stay tracked until cancelled" {
        val counter = AtomicInteger()
        val task = scheduler.runTimer(1, 1) { counter.incrementAndGet() }
        scheduler.trackedCount() shouldBe 1
        server.scheduler.performTicks(3)
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
        server.scheduler.performTicks(5)
        counter.get() shouldBe 0
    }
})
