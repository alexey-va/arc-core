package ru.arc.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import io.kotest.assertions.throwables.shouldThrow

class TaskSchedulerTest : FreeSpec({
    "TestTaskScheduler" - {
        "should run delayed task on tick" {
            val scheduler = TestTaskScheduler()
            val counter = AtomicInteger()

            Tasks.withScheduler(scheduler) {
                delayed(5) { counter.incrementAndGet() }
                counter.get() shouldBe 0
                scheduler.tick(5)
                counter.get() shouldBe 1
            }
        }
    }

    "ExecutorTaskScheduler" - {
        "one-shot tasks are untracked after execution" {
            val executor = Executors.newSingleThreadScheduledExecutor()
            val scheduler = ExecutorTaskScheduler(executor, Executors.newScheduledThreadPool(1))
            val counter = AtomicInteger()

            scheduler.runLater(0) { counter.incrementAndGet() }
            scheduler.trackedCount() shouldBe 1
            Thread.sleep(50)
            counter.get() shouldBe 1
            scheduler.trackedCount() shouldBe 0

            executor.shutdownNow()
        }

        "repeating tasks stay tracked until cancelled" {
            val executor = Executors.newSingleThreadScheduledExecutor()
            val scheduler = ExecutorTaskScheduler(executor, Executors.newScheduledThreadPool(1))
            val counter = AtomicInteger()

            val task = scheduler.runTimer(0, 1) { counter.incrementAndGet() }
            scheduler.trackedCount() shouldBe 1
            Thread.sleep(200)
            counter.get() shouldBeGreaterThan 0
            task.cancel()
            scheduler.trackedCount() shouldBe 0

            executor.shutdownNow()
        }

        "rejected tasks are not retained in the scheduler registry" {
            val executor = Executors.newSingleThreadScheduledExecutor()
            val asyncExecutor = Executors.newSingleThreadScheduledExecutor()
            val scheduler = ExecutorTaskScheduler(executor, asyncExecutor)
            executor.shutdownNow()

            shouldThrow<RejectedExecutionException> {
                scheduler.runLater(1) {}
            }
            scheduler.trackedCount() shouldBe 0

            asyncExecutor.shutdownNow()
        }

        "cancellation during scheduling also cancels the newly attached future" {
            lateinit var scheduler: ExecutorTaskScheduler
            val executor =
                object : ScheduledThreadPoolExecutor(1) {
                    override fun scheduleAtFixedRate(
                        command: Runnable,
                        initialDelay: Long,
                        period: Long,
                        unit: TimeUnit,
                    ): ScheduledFuture<*> {
                        val future = super.scheduleAtFixedRate(command, initialDelay, period, unit)
                        scheduler.cancelAll()
                        return future
                    }
                }.apply {
                    removeOnCancelPolicy = true
                }
            val asyncExecutor = Executors.newSingleThreadScheduledExecutor()
            scheduler = ExecutorTaskScheduler(executor, asyncExecutor)

            val task = scheduler.runTimer(20, 20) {}

            task.isCancelled shouldBe true
            executor.queue.size shouldBe 0
            executor.shutdownNow()
            asyncExecutor.shutdownNow()
        }
    }
})
