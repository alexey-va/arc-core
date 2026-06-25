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
    }
})
