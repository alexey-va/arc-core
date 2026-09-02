package ru.arc.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class MenuCatalogRepositoryTest : FreeSpec({
    "catalog repository" - {
        "increments generations only for complete replacements" {
            val first = catalog(slot = 4)
            val repository = MenuCatalogRepository(first)

            repository.current().generation shouldBe 0L
            val replaced = repository.replace(catalog(slot = 13))
            replaced.previous.generation shouldBe 0L
            replaced.current.generation shouldBe 1L
            repository.current().require("main").slot("open").index shouldBe 13
        }

        "retains the exact catalog instance after a rejected candidate" {
            val first = catalog(slot = 4)
            val repository = MenuCatalogRepository(first)
            val issue = MenuValidationIssue(
                MenuValidationCode.SLOT_COLLISION,
                "main",
                "elements.close.slot",
                "occupied",
            )

            repository.replace(MenuCatalogLoadResult.Rejected(listOf(issue))) shouldBe
                MenuCatalogReplaceResult.Rejected(listOf(issue))
            (repository.current() === first) shouldBe true
            repository.current().generation shouldBe 0L
        }
    }
})

private fun catalog(slot: Int): MenuCatalog {
    val open = MenuElementId.of("open")
    return MenuCatalog(
        generation = 0,
        layouts = mapOf(
            MenuId.of("main") to MenuLayout(
                id = MenuId.of("main"),
                rows = 3,
                elements = mapOf(open to MenuElementLayout.button(open, MenuSlot.of(slot))),
            ),
        ),
    )
}
