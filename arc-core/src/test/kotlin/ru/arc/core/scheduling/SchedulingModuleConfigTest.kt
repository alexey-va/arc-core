package ru.arc.core.scheduling

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.TickConstants

class SchedulingModuleConfigTest : FreeSpec({
    "TestSchedulingModuleConfig" - {
        "should default tick-ms to 50" {
            TestSchedulingModuleConfig().tickMs shouldBe TickConstants.TICK_MS.toInt()
        }

        "should default subtick enabled" {
            TestSchedulingModuleConfig().subtickEnabled shouldBe true
        }
    }
})
