package ru.arc.ai.llm

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import ru.arc.ai.config.TestLlmModuleConfig
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class ModerationServiceTest : FreeSpec({
    "ModerationService" - {
        val config = TestLlmModuleConfig()
        val service = ModerationService(OpenRouterLlmClient.create(config), config, DIRECT_EXECUTOR)

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

        "should return UNKNOWN when response contains conflicting markers" {
            service.parseMarkers("OK BAD COMMENT: ambiguous").outcome shouldBe ModerationOutcome.UNKNOWN
        }

        "should return null without scheduling when LLM is disabled" {
            val disabledConfig = TestLlmModuleConfig(apiKey = "none")
            val executorCalled = AtomicBoolean()
            val disabledService =
                ModerationService(
                    OpenRouterLlmClient.create(disabledConfig),
                    disabledConfig,
                    Executor {
                        executorCalled.set(true)
                        it.run()
                    },
                )

            disabledService.moderate("text").join().shouldBeNull()
            executorCalled.get() shouldBe false
        }

        "should turn rejected scheduling into no result" {
            val enabledConfig = TestLlmModuleConfig()
            val rejectedService =
                ModerationService(
                    OpenRouterLlmClient.create(enabledConfig),
                    enabledConfig,
                    Executor { throw RejectedExecutionException("closed") },
                )

            rejectedService.moderate("text").join().shouldBeNull()
        }
    }
})

private val DIRECT_EXECUTOR = Executor { it.run() }
