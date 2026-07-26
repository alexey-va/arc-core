package ru.arc.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class TasksTest : FreeSpec({
    "Tasks" - {
        "should throw when scheduler not installed" {
            Tasks.reset()
            shouldThrow<IllegalStateException> { Tasks.scheduler }
        }

        "should return installed scheduler" {
            val test = TestTaskScheduler()
            Tasks.install(test)
            Tasks.scheduler shouldBe test
            Tasks.reset()
        }

        "withScheduler should restore previous" {
            Tasks.reset()
            val outer = TestTaskScheduler()
            val inner = TestTaskScheduler()
            Tasks.install(outer)
            Tasks.withScheduler(inner) {
                Tasks.scheduler shouldBe inner
            }
            Tasks.scheduler shouldBe outer
            Tasks.reset()
        }

        "withScheduler should preserve outer tasks and clean up inner tasks" {
            Tasks.reset()
            val outer = TestTaskScheduler()
            val inner = TestTaskScheduler()
            Tasks.install(outer)
            val outerTask = outer.runTimer(0, 20) {}
            lateinit var innerTask: ScheduledTask

            Tasks.withScheduler(inner) {
                innerTask = inner.runTimer(0, 20) {}
                outerTask.isCancelled shouldBe false
            }

            outerTask.isCancelled shouldBe false
            innerTask.isCancelled shouldBe true
            Tasks.reset()
        }

        "install should cancel previous scheduler by default" {
            Tasks.reset()
            val first = ExecutorTaskScheduler()
            val second = ExecutorTaskScheduler()
            Tasks.install(first)
            first.runTimer(0, 20) {}
            first.trackedCount() shouldBe 1
            Tasks.install(second)
            first.trackedCount() shouldBe 0
            Tasks.scheduler shouldBe second
            Tasks.reset()
        }

        "reset should cancel installed scheduler tasks" {
            Tasks.reset()
            val scheduler = TestTaskScheduler()
            Tasks.install(scheduler)
            val task = scheduler.runTimer(0, 20) {}

            Tasks.reset()

            task.isCancelled shouldBe true
        }

        "reset should close the installed scheduler" {
            Tasks.reset()
            var closed = false
            val scheduler =
                object : TaskScheduler by TestTaskScheduler() {
                    override fun close() {
                        closed = true
                    }
                }
            Tasks.install(scheduler)

            Tasks.reset()

            closed shouldBe true
        }
    }
})
