package ru.arc.observability

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class RuntimeEventTest : FreeSpec({
    "renders canonical fields before bounded domain fields" {
        val line = StructuredRuntimeEventLine().line(
            RuntimeEvent(
                type = RuntimeEventType.RECOVERY_COMPLETED,
                component = "player-state",
                outcome = RuntimeEventOutcome.OK,
                fields = listOf("records" to 2, "detail" to "safe value"),
            ),
        )

        line shouldBe
            "ARC_RUNTIME event=recovery-completed component=player-state outcome=ok records=2 detail=\"safe value\""
    }

    "rejects unstable components and duplicate normalized fields" {
        shouldThrow<IllegalArgumentException> {
            RuntimeEvent(RuntimeEventType.PLUGIN_READY, "Player State", RuntimeEventOutcome.OK)
        }
        shouldThrow<IllegalArgumentException> {
            StructuredRuntimeEventLine().line(
                RuntimeEvent(
                    RuntimeEventType.PLUGIN_READY,
                    "plugin",
                    RuntimeEventOutcome.OK,
                    fields = listOf("Event" to "shadow"),
                ),
            )
        }
    }

    "bounds rendered values without leaking multiline payloads" {
        val rendered = StructuredRuntimeEventLine().line(
            RuntimeEvent(
                RuntimeEventType.REDIS_MESSAGE_REJECTED,
                "redis",
                RuntimeEventOutcome.REJECTED,
                fields = listOf("reason" to "bad\nwire"),
            ),
        )
        rendered shouldBe
            "ARC_RUNTIME event=redis-message-rejected component=redis outcome=rejected reason=\"bad wire\""
    }
})
