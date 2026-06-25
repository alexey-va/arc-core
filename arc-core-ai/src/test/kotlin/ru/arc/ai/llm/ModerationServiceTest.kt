package ru.arc.ai.llm

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.config.TestLlmModuleConfig

class ModerationServiceTest : FreeSpec({
    "ModerationService" - {
        val service = ModerationService(OpenRouterLlmClient.create(TestLlmModuleConfig()), TestLlmModuleConfig())

        "should parse OK marker" {
            service.parseMarkers("All good OK here").outcome shouldBe ModerationOutcome.OK
        }

        "should parse BAD marker" {
            service.parseMarkers("BAD content").outcome shouldBe ModerationOutcome.BAD
        }

        "should extract comment" {
            service.parseMarkers("BAD spam COMMENT: too rude").comment shouldBe "too rude"
        }

        "should return UNKNOWN when no marker" {
            service.parseMarkers("maybe").outcome shouldBe ModerationOutcome.UNKNOWN
        }
    }
})
