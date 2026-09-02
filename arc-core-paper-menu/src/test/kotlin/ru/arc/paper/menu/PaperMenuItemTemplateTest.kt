package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemFlag
import ru.arc.config.Config
import java.nio.file.Files

class PaperMenuItemTemplateTest : FreeSpec({
    "item template parser" - {
        "loads material and external templates with presentation flags" {
            val templates = PaperMenuItemTemplateParser.require(
                config(
                    """
                    gui:
                      templates:
                        background:
                          material: black_stained_glass_pane
                          amount: 1
                          hide-tooltip: true
                        premium:
                          external-item: itemsadder:rank_caesar
                          fallback-material: barrier
                          amount: 99
                          custom-model-data: 0
                          glint: true
                          item-flags: [hide_attributes, hide_additional_tooltip]
                    """,
                ),
                "gui.templates",
            )

            templates.getValue("background") shouldBe PaperMenuItemTemplate(
                source = PaperMenuItemSource.MaterialItem(Material.BLACK_STAINED_GLASS_PANE),
                hideTooltip = true,
            )
            templates.getValue("premium") shouldBe PaperMenuItemTemplate(
                source = PaperMenuItemSource.ExternalItem(NamespacedKey("itemsadder", "rank_caesar")),
                fallbackMaterial = Material.BARRIER,
                amount = 99,
                customModelData = 0,
                glint = true,
                itemFlags = linkedSetOf(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP),
            )
        }

        "rejects invalid sources and unsafe item bounds as one candidate" {
            val result = PaperMenuItemTemplateParser.parse(
                config(
                    """
                    gui:
                      templates:
                        both: { material: stone, external-item: bad:id, amount: 0 }
                        air: { material: air, custom-model-data: -1 }
                        huge: { material: stone, amount: 100 }
                        malformed: { external-item: not-namespaced }
                    """,
                ),
                "gui.templates",
            )

            val rejected = result as PaperMenuItemTemplateLoadResult.Rejected
            rejected.issues.map(PaperMenuItemTemplateIssue::code).toSet() shouldBe setOf(
                PaperMenuItemTemplateIssueCode.INVALID_SOURCE,
                PaperMenuItemTemplateIssueCode.INVALID_AMOUNT,
                PaperMenuItemTemplateIssueCode.INVALID_MATERIAL,
                PaperMenuItemTemplateIssueCode.INVALID_CUSTOM_MODEL_DATA,
                PaperMenuItemTemplateIssueCode.INVALID_EXTERNAL_ID,
            )
            rejected.issues.map(PaperMenuItemTemplateIssue::template).distinct()
                .shouldContainExactly("both", "air", "huge", "malformed")
        }
    }
})

private fun config(yaml: String): Config {
    val root = Files.createTempDirectory("arc-paper-menu-template")
    Files.writeString(root.resolve("menus.yml"), yaml.trimIndent() + "\n")
    return Config(root, "menus.yml")
}
