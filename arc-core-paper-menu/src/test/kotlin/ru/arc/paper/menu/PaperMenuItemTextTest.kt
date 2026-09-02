package ru.arc.paper.menu

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import ru.arc.config.Config
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class PaperMenuItemTextTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    val contract = PaperMenuTextContract(
        values = setOf("player", "amount"),
        flags = setOf("claimable"),
        repeats = mapOf("history" to setOf("date", "reward")),
    )

    "config owns item name lore conditional lines and repeated rows" {
        val template = PaperMenuItemTemplateParser.require(
            textConfig(
                """
                gui:
                  templates:
                    action:
                      material: diamond
                      name: '<gold><player> — <amount>'
                      lore:
                        - '<gray>Всего: <amount>'
                        - text: '<green>Можно забрать'
                          when: claimable
                        - text: '<red>Пока нельзя'
                          unless: claimable
                        - repeat: history
                          text: '<dark_gray>• <date>: <reward>'
                """,
            ),
            "gui.templates",
            textContracts = mapOf("action" to contract),
        ).getValue("action")

        val item = PaperMenuItemFactory().create(
            template,
            PaperMenuItemRenderContext(
                values = mapOf(
                    "player" to Component.text("<red>Alex"),
                    "amount" to Component.text(7),
                ),
                flags = setOf("claimable"),
                repeats = mapOf(
                    "history" to listOf(
                        mapOf("date" to Component.text("01.09"), "reward" to Component.text("100")),
                        mapOf("date" to Component.text("02.09"), "reward" to Component.text("250")),
                    ),
                ),
            ),
        )

        item.type shouldBe Material.DIAMOND
        plain(item.itemMeta.displayName()!!) shouldBe "<red>Alex — 7"
        item.itemMeta.lore()!!.map(::plain).shouldContainExactly(
            "Всего: 7",
            "Можно забрать",
            "• 01.09: 100",
            "• 02.09: 250",
        )
    }

    "conditional lore selects the unless branch without recompiling" {
        val template = PaperMenuItemTemplateParser.require(
            textConfig(
                """
                gui:
                  templates:
                    action:
                      material: stone
                      name: '<player>'
                      lore:
                        - { text: '<green>Да', when: claimable }
                        - { text: '<red>Нет', unless: claimable }
                """,
            ),
            "gui.templates",
            textContracts = mapOf("action" to contract),
        ).getValue("action")

        val item = PaperMenuItemFactory().create(
            template,
            PaperMenuItemRenderContext(values = mapOf("player" to Component.text("Alex"))),
        )

        item.itemMeta.lore()!!.map(::plain).shouldContainExactly("Нет")
    }

    "candidate rejects unknown value flag repeat and row tags together" {
        val result = PaperMenuItemTemplateParser.parse(
            textConfig(
                """
                gui:
                  templates:
                    bad:
                      material: stone
                      name: '<unknown>'
                      lore:
                        - { text: '<amount>', when: hidden }
                        - { repeat: purchases, text: '<price>' }
                        - { repeat: history, text: '<unknown-row>' }
                """,
            ),
            "gui.templates",
            textContracts = mapOf("bad" to contract),
        )

        val rejected = result as PaperMenuItemTemplateLoadResult.Rejected
        rejected.issues.map(PaperMenuItemTemplateIssue::code).toSet() shouldBe setOf(
            PaperMenuItemTemplateIssueCode.UNKNOWN_TEXT_TAG,
            PaperMenuItemTemplateIssueCode.UNKNOWN_TEXT_FLAG,
            PaperMenuItemTemplateIssueCode.UNKNOWN_TEXT_REPEAT,
        )
    }

    "render rejects a missing referenced value and incomplete repeat row" {
        val template = PaperMenuItemTemplateParser.require(
            textConfig(
                """
                gui:
                  templates:
                    action:
                      material: stone
                      name: '<player>'
                      lore:
                        - { repeat: history, text: '<date>: <reward>' }
                """,
            ),
            "gui.templates",
            textContracts = mapOf("action" to contract),
        ).getValue("action")

        shouldThrow<IllegalArgumentException> {
            PaperMenuItemFactory().create(template, PaperMenuItemRenderContext())
        }.message shouldBe "Menu item text is missing values: [player]"

        shouldThrow<IllegalArgumentException> {
            PaperMenuItemFactory().create(
                template,
                PaperMenuItemRenderContext(
                    values = mapOf("player" to Component.text("Alex")),
                    repeats = mapOf("history" to listOf(mapOf("date" to Component.text("01.09")))),
                ),
            )
        }.message shouldBe "Menu item repeat 'history' row 0 is missing values: [reward]"
    }
})

private fun textConfig(yaml: String): Config {
    val root = Files.createTempDirectory("arc-paper-menu-text")
    Files.writeString(root.resolve("menus.yml"), yaml.trimIndent() + "\n")
    return Config(root, "menus.yml")
}

private fun plain(component: Component): String =
    PlainTextComponentSerializer.plainText().serialize(component)
