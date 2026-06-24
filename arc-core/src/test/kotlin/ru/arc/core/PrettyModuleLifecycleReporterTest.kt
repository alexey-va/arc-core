package ru.arc.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class PrettyModuleLifecycleReporterTest : FreeSpec({
    "PrettyModuleLifecycleReporter" - {
        "should format init success and failure lines" {
            val lines = mutableListOf<String>()
            val errors = mutableListOf<Pair<String, Throwable>>()
            val reporter =
                PrettyModuleLifecycleReporter(
                    consoleLog = { lines.add(it) },
                    logError = { msg, t -> errors.add(msg to t) },
                )

            reporter.onInitStart(2)
            reporter.onInitModuleSuccess("Redis", 12, 10)
            reporter.onInitModuleFailure("Broken", 12, 5, IllegalStateException("boom"))
            reporter.onInitComplete(ok = 1, failed = 1, totalMs = 15)

            lines.any { it.contains("Initializing") && it.contains("2") } shouldBe true
            lines.any { it.contains("Redis") && it.contains("10ms") } shouldBe true
            lines.any { it.contains("✗") && it.contains("boom") } shouldBe true
            lines.any { it.contains("1 ok") && it.contains("1 failed") } shouldBe true
            errors shouldContain ("Module 'Broken' failed to initialize" to errors.single().second)
        }
    }
})
