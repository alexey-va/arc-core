package ru.arc.testing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.util.concurrent.RejectedExecutionException

class TestingFixturesTest : FreeSpec({
    "deterministic clock supports expiry and rollback scenarios" {
        val clock = DeterministicClock.atMillis(1_000)
        clock.advance(Duration.ofMillis(25)) shouldBe Instant.ofEpochMilli(1_025)
        clock.advance(Duration.ofMillis(-30)) shouldBe Instant.ofEpochMilli(995)
        clock.millis() shouldBe 995
    }

    "controlled executor is FIFO, bounded and closes cleanly" {
        val executor = ControlledExecutor(maxPendingTasks = 2)
        val observed = mutableListOf<Int>()
        executor.execute { observed += 1 }
        executor.execute { observed += 2 }
        shouldThrow<RejectedExecutionException> { executor.execute {} }

        executor.runAll() shouldBe 2
        observed shouldBe listOf(1, 2)
        executor.close()
        shouldThrow<RejectedExecutionException> { executor.execute {} }
    }

    "failure injector consumes only its named configured failures" {
        val injector = FailureInjector()
        injector.failNext("after-commit", times = 2) { IllegalArgumentException("boom") }

        repeat(2) {
            shouldThrow<IllegalArgumentException> { injector.check("after-commit") }.message shouldBe "boom"
        }
        injector.check("after-commit")
        injector.remaining("after-commit") shouldBe 0
    }
})
