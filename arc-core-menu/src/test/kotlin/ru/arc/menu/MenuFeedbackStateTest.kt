package ru.arc.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class MenuFeedbackStateTest : FreeSpec({
    "feedback state" - {
        "only expires the latest token after its deadline" {
            val state = MenuFeedbackState()
            val first = state.show(MenuElementId.of("buy"), expiresAtTick = 10)
            val second = state.show(MenuElementId.of("buy"), expiresAtTick = 12)

            state.expire(first, currentTick = 20) shouldBe null
            state.expire(second, currentTick = 11) shouldBe null
            state.expire(second, currentTick = 12) shouldBe MenuElementId.of("buy")
            state.active shouldBe null
        }

        "full render and generation replacement invalidate delayed restore" {
            val state = MenuFeedbackState()
            val renderToken = state.show(MenuElementId.of("confirm"), 10)
            state.invalidateForRender()
            state.expire(renderToken, 10) shouldBe null

            val generationToken = state.show(MenuElementId.of("confirm"), 20)
            state.invalidateForGeneration(4)
            state.expire(generationToken, 20) shouldBe null
        }
    }
})
