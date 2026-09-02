package ru.arc.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class MenuPageStateTest : FreeSpec({
    "page state" - {
        "keeps one empty page and exposes one-based display values" {
            val state = MenuPageState.of(totalItems = 0, pageSize = 7, requestedPage = 5)

            state.pageIndex shouldBe 0
            state.displayPage shouldBe 1
            state.pageCount shouldBe 1
            state.slice(emptyList<String>()).shouldContainExactly()
        }

        "clamps page changes and slices the requested page" {
            val state = MenuPageState.of(totalItems = 8, pageSize = 3, requestedPage = 20)

            state.pageIndex shouldBe 2
            state.pageCount shouldBe 3
            state.slice((0..7).toList()).shouldContainExactly(6, 7)
            state.previous().pageIndex shouldBe 1
            state.next().pageIndex shouldBe 2
            state.first().previous().pageIndex shouldBe 0
        }

        "rejects non-positive page sizes and inconsistent content" {
            runCatching { MenuPageState.of(1, 0) }.isFailure shouldBe true
            runCatching { MenuPageState.of(2, 1).slice(listOf(1)) }.isFailure shouldBe true
        }
    }
})
