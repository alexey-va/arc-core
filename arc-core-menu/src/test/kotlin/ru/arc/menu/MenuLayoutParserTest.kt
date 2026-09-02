package ru.arc.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files

class MenuLayoutParserTest : FreeSpec({
    "layout parser" - {
        "loads slots positions patterns ordered regions and pagination" {
            val config = config(
                """
                gui:
                  layouts:
                    main:
                      schema-version: 1
                      rows: 3
                      background:
                        template: background
                      pattern:
                        - '..F.L.M..'
                        - '.........'
                        - '.W..C..S.'
                      legend:
                        F: { element: farm }
                        L: { element: lumber }
                        M: { element: mine }
                        W: { element: workday }
                        C: { element: companies }
                        S: { element: stats }
                      elements:
                        back:
                          position: { row: 1, column: 0 }
                          template: back
                        frame:
                          kind: decoration
                          slots: [0, 8]
                      regions:
                        cards:
                          slots: ['10-12', 16]
                        activities:
                          elements: [farm, lumber, mine]
                      pagination:
                        region: cards
                        previous: back
                        next: companies
                """,
            )
            val contract = MenuContract(
                requiredElements = linkedSetOf(
                    "farm", "lumber", "mine", "companies", "back", "frame",
                ).mapTo(linkedSetOf(), MenuElementId::of),
                optionalElements = linkedSetOf("workday", "stats").mapTo(linkedSetOf(), MenuElementId::of),
                requiredRegions = linkedSetOf("cards", "activities").mapTo(linkedSetOf(), MenuRegionId::of),
            )

            val catalog = MenuLayoutParser.require(config, "gui.layouts", mapOf(MenuId.of("main") to contract))
            val layout = catalog.require(MenuId.of("main"))

            layout.rows shouldBe 3
            layout.backgroundTemplate shouldBe MenuTemplateId.of("background")
            layout.slot(MenuElementId.of("farm")).index shouldBe 2
            layout.slot(MenuElementId.of("lumber")).index shouldBe 4
            layout.slot(MenuElementId.of("mine")).index shouldBe 6
            layout.slot(MenuElementId.of("back")).index shouldBe 9
            layout.elements.getValue(MenuElementId.of("frame")).slots.map(MenuSlot::index)
                .shouldContainExactly(0, 8)
            layout.region(MenuRegionId.of("cards")).map(MenuSlot::index)
                .shouldContainExactly(10, 11, 12, 16)
            layout.regions.getValue(MenuRegionId.of("activities")).kind shouldBe MenuRegionKind.GROUP
            layout.region(MenuRegionId.of("activities")).map(MenuSlot::index)
                .shouldContainExactly(2, 4, 6)
            layout.pagination shouldBe MenuPaginationLayout(
                region = MenuRegionId.of("cards"),
                previous = MenuElementId.of("back"),
                next = MenuElementId.of("companies"),
            )
        }

        "aggregates structural failures across the complete catalog" {
            val config = config(
                """
                gui:
                  layouts:
                    broken-one:
                      schema-version: 2
                      rows: 7
                      pattern: ['short']
                      legend:
                        X: missing
                    broken-two:
                      schema-version: 1
                      rows: nope
                      elements:
                        back: { slot: 54 }
                """,
            )

            val result = MenuLayoutParser.parse(
                config,
                "gui.layouts",
                mapOf(
                    MenuId.of("broken-one") to MenuContract(allowUnknownElements = true, allowUnknownRegions = true),
                    MenuId.of("broken-two") to MenuContract(allowUnknownElements = true, allowUnknownRegions = true),
                ),
            )

            val rejected = result as MenuCatalogLoadResult.Rejected
            rejected.issues.map(MenuValidationIssue::code).toSet() shouldBe setOf(
                MenuValidationCode.INVALID_SCHEMA_VERSION,
                MenuValidationCode.INVALID_ROWS,
                MenuValidationCode.INVALID_PATTERN,
                MenuValidationCode.INVALID_VALUE,
                MenuValidationCode.SLOT_OUT_OF_BOUNDS,
            )
        }

        "rejects missing contracts and leaves no partially loaded catalog" {
            val result = MenuLayoutParser.parse(
                config(
                    """
                    gui:
                      layouts:
                        known: { schema-version: 1, rows: 1 }
                        unknown: { schema-version: 1, rows: 1 }
                    """,
                ),
                "gui.layouts",
                mapOf(MenuId.of("known") to MenuContract()),
            )

            (result as MenuCatalogLoadResult.Rejected).issues.map(MenuValidationIssue::code)
                .shouldContainExactly(MenuValidationCode.UNKNOWN_REFERENCE)
        }
    }
})

private fun config(yaml: String): Config {
    val root = Files.createTempDirectory("arc-menu-parser")
    Files.writeString(root.resolve("menus.yml"), yaml.trimIndent() + "\n")
    return Config(root, "menus.yml")
}
