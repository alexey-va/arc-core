package ru.arc.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LifecycleTaskScopeTest : FreeSpec({
    "a cancelled epoch cannot enter a replacement runtime" {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val first = scope.token()
        val calls = AtomicInteger()

        scope.runLater(first, 5) { calls.incrementAndGet() }
        val second = scope.restart()
        scope.isCurrent(first) shouldBe false
        scope.isCurrent(second) shouldBe true
        scheduler.tick(5)
        calls.get() shouldBe 0

        scope.runSync(second) { calls.incrementAndGet() }
        scheduler.executeImmediate()
        calls.get() shouldBe 1
    }

    "one-shot tasks untrack themselves while timers remain owned" {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        scope.runSync {}
        scope.runTimer(1, 1) {}
        scope.trackedCount() shouldBe 2
        scheduler.executeImmediate()
        scope.trackedCount() shouldBe 1
        scope.cancelAll()
        scope.trackedCount() shouldBe 0
        scheduler.timerCount() shouldBe 0
    }

    "completion callbacks are dropped after invalidation" {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val token = scope.token()
        val called = AtomicInteger()
        val future = CompletableFuture<Int>()
        future.whenCompleteSync(scope, token) { value, failure ->
            value shouldBe 42
            failure shouldBe null
            called.incrementAndGet()
        }
        future.complete(42)
        scope.cancelAll()
        scheduler.executeImmediate()
        called.get() shouldBe 0
    }

    "queued native promises complete on cancellation and cannot enter a later epoch" {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        var calls = 0
        val abandoned = scope.callSync { calls++; 42 }
        abandoned.isDone shouldBe false
        scope.restart()
        abandoned.isCompletedExceptionally shouldBe true
        scheduler.executeImmediate()
        calls shouldBe 0
        val current = scope.callSync { calls++; 43 }
        scheduler.executeImmediate()
        current.getNow(null) shouldBe 43
        val closing = scope.callSync { calls++; 44 }
        scope.close()
        closing.isCompletedExceptionally shouldBe true
        scope.callSync { calls++ }.isCompletedExceptionally shouldBe true
        calls shouldBe 1
    }

    "an already executing native stage owns its actual outcome after reentrant close" {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        var changed = false
        val outcome = scope.callSync {
            changed = true
            scope.close()
            "mutation completed"
        }
        scheduler.executeImmediate()
        changed shouldBe true
        outcome.getNow(null) shouldBe "mutation completed"
    }

    "concurrent close cancels queued work but waits for no executing native promise" {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val outcome = scope.callSync {
            entered.countDown()
            check(unblock.await(5, TimeUnit.SECONDS))
            "durable native outcome"
        }
        val execution = Thread { scheduler.executeImmediate() }
        try {
            execution.start()
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            scope.close()
            outcome.isDone shouldBe false
            unblock.countDown()
            outcome.get(5, TimeUnit.SECONDS) shouldBe "durable native outcome"
        } finally { unblock.countDown(); execution.join(5_000); scope.close() }
    }

    "cancellation racing task attachment cancels the returned handle" {
        lateinit var scope: LifecycleTaskScope
        val handle = RecordingTask()
        val scheduler = object : TaskScheduler by NoOpTaskScheduler() {
            override fun runLater(delayTicks: Long, task: Runnable): ScheduledTask {
                scope.cancelAll()
                return handle
            }
        }
        scope = LifecycleTaskScope(scheduler)

        scope.runLater(1) {} shouldBe null
        handle.isCancelled shouldBe true
        scope.trackedCount() shouldBe 0
    }

    "close is idempotent and terminal" {
        val scope = LifecycleTaskScope(TestTaskScheduler())
        scope.close()
        scope.close()
        scope.runSync {} shouldBe null
        shouldThrow<IllegalStateException> { scope.activate() }
        shouldThrow<IllegalStateException> { scope.token() }
    }
}) {
    private class RecordingTask : ScheduledTask {
        override val id: Int = 1
        private var cancelled = false
        override val isCancelled: Boolean get() = cancelled
        override fun cancel() {
            cancelled = true
        }
    }

    private open class NoOpTaskScheduler : TaskScheduler {
        private fun task() = RecordingTask()
        override fun runAsync(task: Runnable): ScheduledTask = task()
        override fun runSync(task: Runnable): ScheduledTask = task()
        override fun runLater(delayTicks: Long, task: Runnable): ScheduledTask = task()
        override fun runLaterAsync(delayTicks: Long, task: Runnable): ScheduledTask = task()
        override fun runTimer(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask = task()
        override fun runTimerAsync(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask = task()
        override fun cancelAll() = Unit
    }
}
