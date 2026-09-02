package ru.arc.paper.menu

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.config.Config
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class PaperMenuConfigurationTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    val menu = MenuId.of("main")
    val action = MenuElementId.of("action")
    val contract = mapOf(menu to MenuContract(requiredElements = setOf(action)))

    "loads topology and templates as one validated generation" {
        val configuration = PaperMenuConfigurationParser.require(
            config(
                """
                gui:
                  layouts:
                    main:
                      schema-version: 1
                      rows: 1
                      background: { template: background }
                      elements:
                        action: { slot: 4, template: action }
                  templates:
                    background: { material: black_stained_glass_pane }
                    action: { material: diamond }
                    feedback: { material: barrier }
                """,
            ),
            "gui.layouts",
            "gui.templates",
            contract,
            requiredTemplates = setOf("feedback"),
        )

        configuration.catalog.require(menu).slot(action).index shouldBe 4
        configuration.template(menu, action).source shouldBe PaperMenuItemSource.MaterialItem(Material.DIAMOND)
    }

    "rejects a missing referenced or consumer-required template" {
        val failure = shouldThrow<IllegalArgumentException> {
            PaperMenuConfigurationParser.require(
                config(
                    """
                    gui:
                      layouts:
                        main:
                          schema-version: 1
                          rows: 1
                          background: { template: missing-background }
                          elements:
                            action: { slot: 4, template: missing-action }
                      templates:
                        present: { material: stone }
                    """,
                ),
                "gui.layouts",
                "gui.templates",
                contract,
                requiredTemplates = setOf("feedback"),
            )
        }

        failure.message shouldBe
            "Paper menu configuration references missing templates: [feedback, missing-action, missing-background]"
    }
})

private fun config(yaml: String): Config {
    val root = Files.createTempDirectory("arc-paper-menu-configuration")
    Files.writeString(root.resolve("menus.yml"), yaml.trimIndent() + "\n")
    return Config(root, "menus.yml")
}
