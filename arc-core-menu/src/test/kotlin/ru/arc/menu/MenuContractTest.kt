package ru.arc.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly

class MenuContractTest : FreeSpec({
    "menu contracts" - {
        "report every missing required element and region" {
            val layout = MenuLayout(MenuId.of("main"), rows = 3)
            val contract = MenuContract(
                requiredElements = linkedSetOf(MenuElementId.of("back"), MenuElementId.of("confirm")),
                requiredRegions = linkedSetOf(MenuRegionId.of("content")),
            )

            layout.validate(contract).map(MenuValidationIssue::code).shouldContainExactly(
                MenuValidationCode.MISSING_REQUIRED_ELEMENT,
                MenuValidationCode.MISSING_REQUIRED_ELEMENT,
                MenuValidationCode.MISSING_REQUIRED_REGION,
            )
        }

        "allow declared optional elements to be absent" {
            val layout = MenuLayout(MenuId.of("main"), rows = 3)
            val contract = MenuContract(optionalElements = setOf(MenuElementId.of("admin")))

            layout.validate(contract).shouldContainExactly()
        }

        "reject unknown elements for a closed contract" {
            val unknown = MenuElementId.of("surprise")
            val layout = MenuLayout(
                id = MenuId.of("main"),
                rows = 3,
                elements = mapOf(unknown to MenuElementLayout.button(unknown, MenuSlot.of(4))),
            )

            layout.validate(MenuContract()).map(MenuValidationIssue::code)
                .shouldContainExactly(MenuValidationCode.UNKNOWN_ELEMENT)
        }

        "allow extension elements for an open contract" {
            val extension = MenuElementId.of("extension")
            val layout = MenuLayout(
                id = MenuId.of("main"),
                rows = 3,
                elements = mapOf(extension to MenuElementLayout.button(extension, MenuSlot.of(4))),
            )

            layout.validate(MenuContract(allowUnknownElements = true)).shouldContainExactly()
        }

        "reject regions that overlap fixed elements" {
            val back = MenuElementId.of("back")
            val content = MenuRegionId.of("content")
            val layout = MenuLayout(
                id = MenuId.of("main"),
                rows = 3,
                elements = mapOf(back to MenuElementLayout.button(back, MenuSlot.of(18))),
                regions = mapOf(content to MenuRegionLayout(content, listOf(MenuSlot.of(10), MenuSlot.of(18)))),
            )

            layout.validate(
                MenuContract(requiredElements = setOf(back), requiredRegions = setOf(content)),
            ).map(MenuValidationIssue::code).shouldContainExactly(MenuValidationCode.SLOT_COLLISION)
        }
    }
})
