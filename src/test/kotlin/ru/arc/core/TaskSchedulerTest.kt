package ru.arc.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger

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
})
