package ru.arc.logging

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class ArcLoggingTest : FreeSpec({

    "format" - {
        "should substitute placeholders" {
            ArcLogging.format("hello {} {}", "world", 42) shouldBe "hello world 42"
        }
    }

    "matchesQuietSource" - {
        "should match package prefix" {
            ArcLogging.matchesQuietSource(
                "ru.arc.sync.base.SyncRepo",
                setOf("ru.arc.sync"),
            ) shouldBe true
        }
    }

    "plainForBuffer" - {
        "should strip MiniMessage tags" {
            ArcLogging.plainForBuffer("<red>warn</red> text") shouldBe "warn text"
        }
    }
})
