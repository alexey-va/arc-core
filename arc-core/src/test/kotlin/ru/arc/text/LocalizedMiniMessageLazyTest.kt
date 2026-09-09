package ru.arc.text

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class LocalizedMiniMessageLazyTest : FreeSpec({
    val plain = PlainTextComponentSerializer.plainText()
    fun renderer() = LocalizedMiniMessage(
        catalogs = mapOf(
            "en" to object : LocaleCatalog {
                override fun scalar(path: String) = mapOf(
                    "prefix" to "Network",
                    "message" to "<prefix> <player> / <player>",
                    "optional" to "<player>",
                )[path]
                override fun lines(path: String) = listOf("<player>", "<player> again")
            },
            "ru" to object : LocaleCatalog {
                override fun scalar(path: String) = mapOf("prefix" to "Сеть", "optional" to "")[path]
                override fun lines(path: String): List<String>? = null
            },
        ),
        defaultLocale = { "en" },
    )

    "uses lazy values once per render with locale fallback and literal insertion" {
        val renderer = renderer()
        var calls = 0
        val values = mapOf(
            "player" to { calls++; Component.text("<red>name</red>") },
            "unused" to { error("unused supplier evaluated") },
        )
        plain.serialize(renderer.renderLazy("message", "ru-RU", values)) shouldBe
            "Сеть <red>name</red> / <red>name</red>"
        calls shouldBe 1
        renderer.renderLazy("message", "en", values)
        calls shouldBe 2
    }

    "shares a snapshot across fallback lines but never across requests" {
        var calls = 0
        val renderer = renderer()
        val values = mapOf("player" to { Component.text((++calls).toString()) })
        renderer.renderLinesLazy("lore", "ru", values).map(plain::serialize) shouldBe listOf("1", "1 again")
        renderer.renderLinesLazy("lore", "ru", values).map(plain::serialize) shouldBe listOf("2", "2 again")
    }

    "disabled optional surfaces evaluate no suppliers" {
        renderer().renderOptionalLazy("optional", "ru", mapOf("player" to { error("disabled") })) shouldBe null
        plain.serialize(requireNotNull(renderer().renderOptionalLazy(
            "optional", "en", mapOf("player" to { Component.text("enabled") }),
        ))) shouldBe "enabled"
    }

    "validates unused names and protects the prefix without evaluating values" {
        listOf("bad.tag", "prefix").forEach { name ->
            shouldThrow<IllegalArgumentException> {
                renderer().renderLazy("message", values = mapOf(name to { error("invalid") }))
            }
        }
    }

    "supplier failures reach the caller and do not poison the next request" {
        val renderer = renderer()
        var calls = 0
        shouldThrow<IllegalStateException> {
            renderer.renderLazy("message", values = mapOf("player" to { calls++; error("lookup failed") }))
        }
        calls shouldBe 1
        plain.serialize(renderer.renderLazy("message", values = mapOf("player" to { Component.text("ok") })))
            .shouldBe("Network ok / ok")
    }
})
