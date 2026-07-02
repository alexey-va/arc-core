package ru.arc.velocity.core

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask as VelocityScheduledTaskHandle
import com.velocitypowered.api.scheduler.Scheduler
import com.velocitypowered.api.scheduler.TaskStatus
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VelocityTaskSchedulerTest : FreeSpec({

    fun schedulerHarness(): Triple<ProxyServer, Scheduler, VelocityTaskScheduler> {
        val proxyServer = mockk<ProxyServer>()
        val velocityScheduler = mockk<Scheduler>()
        every { proxyServer.scheduler } returns velocityScheduler
        return Triple(proxyServer, velocityScheduler, VelocityTaskScheduler(proxyServer, "proxyarc"))
    }

    "one-shot tasks are untracked after execution" {
        val (proxyServer, velocityScheduler, scheduler) = schedulerHarness()
        val runnableSlot = slot<Runnable>()
        val builder = mockk<Scheduler.TaskBuilder>()
        val handle = mockk<VelocityScheduledTaskHandle>()

        every { velocityScheduler.buildTask("proxyarc", capture(runnableSlot)) } returns builder
        every { builder.schedule() } answers {
            runnableSlot.captured.run()
            handle
        }
        every { handle.status() } returns TaskStatus.FINISHED
        every { handle.cancel() } returns Unit

        val counter = AtomicInteger()
        scheduler.runSync { counter.incrementAndGet() }

        counter.get() shouldBe 1
        scheduler.trackedCount() shouldBe 0
        verify { proxyServer.scheduler }
    }

    "repeating tasks stay tracked until cancelled" {
        val (proxyServer, velocityScheduler, scheduler) = schedulerHarness()
        val handle = mockk<VelocityScheduledTaskHandle>()
        val builder = mockk<Scheduler.TaskBuilder>()

        every { velocityScheduler.buildTask("proxyarc", any<Runnable>()) } returns builder
        every { builder.delay(any(), any<TimeUnit>()) } returns builder
        every { builder.repeat(any(), any<TimeUnit>()) } returns builder
        every { builder.schedule() } returns handle
        every { handle.status() } returns TaskStatus.SCHEDULED
        every { handle.cancel() } returns Unit

        val task = scheduler.runTimer(0, 20) {}
        scheduler.trackedCount() shouldBe 1
        task.cancel()
        scheduler.trackedCount() shouldBe 0
    }

    "cancelled delayed task is untracked before execution" {
        val (proxyServer, velocityScheduler, scheduler) = schedulerHarness()
        val runnableSlot = slot<Runnable>()
        val builder = mockk<Scheduler.TaskBuilder>()
        val handle = mockk<VelocityScheduledTaskHandle>()

        every { velocityScheduler.buildTask("proxyarc", capture(runnableSlot)) } returns builder
        every { builder.delay(1000L, TimeUnit.MILLISECONDS) } returns builder
        every { builder.schedule() } returns handle
        every { handle.status() } returns TaskStatus.CANCELLED
        every { handle.cancel() } returns Unit

        val task = scheduler.runLater(20) {}
        scheduler.trackedCount() shouldBe 1
        task.cancel()
        scheduler.trackedCount() shouldBe 0

        runnableSlot.captured.run()
    }
})
