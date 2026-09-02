package ru.arc.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class MenuLayoutTest : FreeSpec({
    "menu identifiers" - {
        "accept stable lowercase keys and reject ambiguous values" {
            MenuId.of("rank-overview").value shouldBe "rank-overview"
            MenuElementId.of("weekly_kit.next").value shouldBe "weekly_kit.next"
            MenuRegionId.of("content.cards").value shouldBe "content.cards"

            listOf("", "UPPER", "with space", "../escape").forEach { value ->
                runCatching { MenuElementId.of(value) }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
            }
        }
    }

    "menu slots" - {
        "accept every physical chest slot" {
            (0..53).map { MenuSlot.of(it).index }.shouldContainExactly((0..53).toList())
        }

        "reject values outside a six-row chest" {
            listOf(-1, 54).forEach { value ->
                runCatching { MenuSlot.of(value) }.exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
            }
        }

        "convert zero-based row and column" {
            MenuSlot.at(row = 2, column = 4) shouldBe MenuSlot.of(22)
        }
    }

    "layout validation" - {
        "accept one-row and six-row boundaries" {
            layout(rows = 1, buttonSlot = 8).validate(contract()).shouldBe(emptyList())
            layout(rows = 6, buttonSlot = 53).validate(contract()).shouldBe(emptyList())
        }

        "report rows outside the supported boundary" {
            listOf(0, 7).forEach { rows ->
                val issues = layout(rows = rows, buttonSlot = 0).validate(contract())
                issues.map(MenuValidationIssue::code).shouldContainExactly(MenuValidationCode.INVALID_ROWS)
            }
        }

        "report an element outside the selected inventory size" {
            val issues = layout(rows = 1, buttonSlot = 9).validate(contract())
            issues.map(MenuValidationIssue::code).shouldContainExactly(MenuValidationCode.SLOT_OUT_OF_BOUNDS)
            issues.single().path shouldBe "elements.open.slot"
        }

        "report collisions deterministically" {
            val layout = MenuLayout(
                id = MenuId.of("main"),
                rows = 3,
                elements = linkedMapOf(
                    MenuElementId.of("open") to MenuElementLayout.button(MenuElementId.of("open"), MenuSlot.of(4)),
                    MenuElementId.of("close") to MenuElementLayout.button(MenuElementId.of("close"), MenuSlot.of(4)),
                ),
            )

            val issues = layout.validate(
                MenuContract(
                    requiredElements = setOf(MenuElementId.of("open"), MenuElementId.of("close")),
                ),
            )

            issues.map(MenuValidationIssue::code).shouldContainExactly(MenuValidationCode.SLOT_COLLISION)
            issues.single().path shouldBe "elements.close.slot"
        }

        "retain sparse region declaration order" {
            val layout = layout(
                rows = 3,
                buttonSlot = 4,
                regionSlots = listOf(19, 10, 12),
            ).validated(contract(requiredRegion = true))

            layout.region(MenuRegionId.of("cards")).map(MenuSlot::index)
                .shouldContainExactly(19, 10, 12)
        }
    }
})

private fun layout(
    rows: Int,
    buttonSlot: Int,
    regionSlots: List<Int> = emptyList(),
): MenuLayout = MenuLayout(
    id = MenuId.of("main"),
    rows = rows,
    elements = linkedMapOf(
        MenuElementId.of("open") to MenuElementLayout.button(MenuElementId.of("open"), MenuSlot.of(buttonSlot)),
    ),
    regions = if (regionSlots.isEmpty()) {
        emptyMap()
    } else {
        linkedMapOf(
            MenuRegionId.of("cards") to MenuRegionLayout(
                MenuRegionId.of("cards"),
                regionSlots.map(MenuSlot::of),
            ),
        )
    },
)

private fun contract(requiredRegion: Boolean = false): MenuContract = MenuContract(
    requiredElements = setOf(MenuElementId.of("open")),
    requiredRegions = if (requiredRegion) setOf(MenuRegionId.of("cards")) else emptySet(),
)
