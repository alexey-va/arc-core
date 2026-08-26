package ru.arc.observability

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class StructuredDebugLineTest : StringSpec({
    "formats ordered fields for stable agent readback" {
        StructuredDebugLine("ARC_TEST").line(
            "Event Name" to "queue.joined",
            "player" to ".Bedrock_1",
            "count" to 2,
        ) shouldBe "ARC_TEST event_name=queue.joined player=.Bedrock_1 count=2"
    }

    "bounds and escapes untrusted values onto one line" {
        val formatter = StructuredDebugLine("ARC_TEST", maxValueCharacters = 12)
        formatter.line("unsafe" to "a\n\\\"very-long-value") shouldBe "ARC_TEST unsafe=\"a \\\\\\\u0022very-lon\""
        formatter.line("empty" to null) shouldBe "ARC_TEST empty=-"
    }

    "normalizes unusable field names without dropping their value" {
        StructuredDebugLine("ARC_TEST").line("!!!" to "value") shouldBe "ARC_TEST field=value"
    }

    "rejects unstable prefixes" {
        shouldThrow<IllegalArgumentException> { StructuredDebugLine("debug") }
        shouldThrow<IllegalArgumentException> { StructuredDebugLine("A") }
    }

    "bounds field count and rejects ambiguous normalized duplicates" {
        val formatter = StructuredDebugLine("ARC_TEST", maxFields = 2)
        shouldThrow<IllegalArgumentException> { formatter.line("one" to 1, "two" to 2, "three" to 3) }
        shouldThrow<IllegalArgumentException> { formatter.line("Event Name" to 1, "event_name" to 2) }
    }
})
