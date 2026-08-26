package ru.arc.text

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class LocalizedMiniMessageTest : FreeSpec({
    val plain = PlainTextComponentSerializer.plainText()

    "selects exact or language locale and falls back per message" {
        val renderer = renderer()
        plain.serialize(renderer.render("welcome", "ru-RU")) shouldBe "Сеть • Привет"
        plain.serialize(renderer.render("fallback-only", "ru-RU")) shouldBe "Сеть • fallback"
        plain.serialize(renderer.render("welcome", "de-DE")) shouldBe "Network • Hello"
    }

    "renders list fallback and preserves order" {
        renderer().renderLines("lore", "ru-RU").map(plain::serialize)
            .shouldContainExactly("Первая", "Вторая")
        renderer().renderLines("fallback-lore", "ru-RU").map(plain::serialize)
            .shouldContainExactly("Only English")
    }

    "an explicitly blank optional message stays disabled instead of falling back" {
        val renderer = renderer()
        renderer.renderOptional("optional", "ru-RU") shouldBe null
        plain.serialize(requireNotNull(renderer.renderOptional("optional", "en"))) shouldBe "Enabled"
    }

    "component placeholders keep untrusted MiniMessage literal" {
        val renderer = renderer()
        val rendered = renderer.render(
            "player",
            "en",
            mapOf("player" to renderer.literal("<red>Injected</red>")),
        )
        plain.serialize(rendered) shouldBe "Network • <red>Injected</red>"
    }

    "rejects unsafe placeholder names and the owned prefix slot" {
        val renderer = renderer()
        shouldThrow<IllegalArgumentException> {
            renderer.render("player", values = mapOf("bad.tag" to renderer.literal("x")))
        }
        shouldThrow<IllegalArgumentException> {
            renderer.render("player", values = mapOf("prefix" to renderer.literal("x")))
        }
    }

    "validates every catalog rather than only the fallback" {
        val renderer = renderer()
        shouldThrow<IllegalArgumentException> {
            renderer.validate(LocaleRequirements(scalarPaths = setOf("fallback-only")))
        }
        renderer.validate(LocaleRequirements(scalarPaths = setOf("prefix", "welcome"), listPaths = setOf("lore")))
    }
}) {
    companion object {
        private fun renderer(): LocalizedMiniMessage = LocalizedMiniMessage(
            catalogs = mapOf(
                "en" to MapCatalog(
                    lists = mapOf("lore" to listOf("First", "Second"), "fallback-lore" to listOf("Only English")),
                    scalars = mapOf(
                        "prefix" to "<gray>Network •</gray>",
                        "welcome" to "<prefix> Hello",
                        "fallback-only" to "<prefix> fallback",
                        "player" to "<prefix> <player>",
                        "optional" to "Enabled",
                    ),
                ),
                "ru" to MapCatalog(
                    scalars = mapOf(
                        "prefix" to "<gray>Сеть •</gray>",
                        "welcome" to "<prefix> Привет",
                        "player" to "<player>",
                        "optional" to "",
                    ),
                    lists = mapOf("lore" to listOf("Первая", "Вторая")),
                ),
            ),
            defaultLocale = { "en" },
        )

        private class MapCatalog(
            private val scalars: Map<String, String>,
            private val lists: Map<String, List<String>>,
        ) : LocaleCatalog {
            override fun scalar(path: String): String? = scalars[path]
            override fun lines(path: String): List<String>? = lists[path]
        }
    }
}
