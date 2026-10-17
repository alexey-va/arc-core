package ru.arc.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BudgetedTaskQueueTest : FunSpec({
    test("one count budget is shared fairly by simultaneous operations") {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val queue = BudgetedTaskQueue(scope, nanoTime = { 0 })
        val counts = IntArray(100)
        val results = counts.indices.map { index -> queue.submit({ true }) { ++counts[index] == 3 } }
        counts.sum() shouldBe 0
        scheduler.tick()
        counts.sum() shouldBe 128
        counts.min() shouldBe 1
        counts.max() shouldBe 2
        scheduler.tick(2)
        counts.sum() shouldBe 300
        results.all { it.isDone && !it.isCompletedExceptionally } shouldBe true
        queue.close()
        scope.close()
    }

    test("time exhaustion yields and stale or closed work cannot mutate again") {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        var nanos = 0L
        val queue = BudgetedTaskQueue(scope, nanoTime = { nanos })
        var current = true
        var calls = 0
        val result = queue.submit({ current }) { nanos += 1_000_000; calls++; false }
        scheduler.tick()
        calls shouldBe 2
        result.isDone shouldBe false
        current = false
        scheduler.tick()
        result.isCompletedExceptionally shouldBe true
        val abandoned = queue.submit({ true }) { calls++; false }
        queue.close()
        scheduler.tick()
        abandoned.isCompletedExceptionally shouldBe true
        calls shouldBe 2
        scope.close()
    }

    test("full admission and failed steps never fall back inline or strand siblings") {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val queue = BudgetedTaskQueue(scope, maximumPending = 2)
        val failed = queue.submit({ true }) { error("failed native step") }
        var calls = 0
        val healthy = queue.submit({ true }) { calls++; true }
        queue.submit({ true }) { calls++; true }.isCompletedExceptionally shouldBe true
        calls shouldBe 0
        scheduler.tick()
        failed.isCompletedExceptionally shouldBe true
        healthy.isCompletedExceptionally shouldBe false
        healthy.isDone shouldBe true
        calls shouldBe 1
        queue.close()
        scope.close()
    }

    test("close from inside a native step cancels its unfinished future") {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val queue = BudgetedTaskQueue(scope)
        val pending = queue.submit({ true }) { queue.close(); false }
        scheduler.tick()
        pending.isCompletedExceptionally shouldBe true
        scope.close()
    }
})
