package ru.arc.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class MenuSlotExpressionTest : FreeSpec({
    "slot expressions" - {
        "preserve ordered scalars inclusive ranges and unions" {
            MenuSlotExpression.parse(listOf("19", "10-12", "4,6-7"))
                .getOrThrow()
                .map(MenuSlot::index)
                .shouldContainExactly(19, 10, 11, 12, 4, 6, 7)
        }

        "reject descending malformed duplicate and physical overflow values" {
            listOf(
                listOf("12-10"),
                listOf("ten"),
                listOf("1", "1"),
                listOf("54"),
                listOf("1-"),
            ).forEach { expression ->
                MenuSlotExpression.parse(expression).isFailure shouldBe true
            }
        }
    }
})
