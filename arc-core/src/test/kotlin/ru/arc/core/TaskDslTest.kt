package ru.arc.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class TaskDslTest : FreeSpec({
    "TaskDsl" - {
        "should run delayed task after tick advance" {
            val scheduler = TestTaskScheduler()
            val counter = AtomicInteger()

            Tasks.withScheduler(scheduler) {
                delayed(20.ticks) { counter.incrementAndGet() }
                counter.get() shouldBe 0
                scheduler.tick(20)
                counter.get() shouldBe 1
            }
        }

        "should run subtick delayed task after advanceMs" {
            val scheduler = TestTaskScheduler()
            val counter = AtomicInteger()

            Tasks.withScheduler(scheduler) {
                delayed(25.milliseconds) { counter.incrementAndGet() }
                scheduler.advanceMs(24)
                counter.get() shouldBe 0
                scheduler.advanceMs(1)
                counter.get() shouldBe 1
            }
        }

        "supports schedulers that execute zero-delay tasks before returning" {
            val scheduler = EagerTaskScheduler()
            var ran = false

            val task =
                scheduler.async {
                    ran = true
                    cancel()
                }

            ran shouldBe true
            task.isCancelled shouldBe true
        }
    }
})

private class EagerTaskScheduler : TaskScheduler {
    private var nextId = 0

    override fun runAsync(task: Runnable): ScheduledTask = runEager(task)

    override fun runSync(task: Runnable): ScheduledTask = runEager(task)

    override fun runLater(
        delayTicks: Long,
        task: Runnable,
    ): ScheduledTask = runEager(task)

    override fun runLaterAsync(
        delayTicks: Long,
        task: Runnable,
    ): ScheduledTask = runEager(task)

    override fun runTimer(
        delayTicks: Long,
        periodTicks: Long,
        task: Runnable,
    ): ScheduledTask = runEager(task)

    override fun runTimerAsync(
        delayTicks: Long,
        periodTicks: Long,
        task: Runnable,
    ): ScheduledTask = runEager(task)

    override fun cancelAll() = Unit

    private fun runEager(task: Runnable): ScheduledTask {
        val handle = EagerScheduledTask(++nextId)
        task.run()
        return handle
    }
}

private class EagerScheduledTask(
    override val id: Int,
) : ScheduledTask {
    override var isCancelled = false
        private set

    override fun cancel() {
        isCancelled = true
    }
}
