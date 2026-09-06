package ru.arc.text

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextDecoration

class ComponentTextLayoutTest : FreeSpec({
    val spacerFont = Key.key("arc:layout-spacer")

    "pads left center and right edges to the exact pixel width" {
        val layout = layoutOf(mapOf('a'.code to 2, 'b'.code to 2))
        val left = layout.layout(Component.text("ab"), 10, TextAlignment.LEFT).requireAligned()
        val center = layout.layout(Component.text("ab"), 10, TextAlignment.CENTER).requireAligned()
        val right = layout.layout(Component.text("ab"), 10, TextAlignment.RIGHT).requireAligned()

        left.width shouldBe 10
        center.width shouldBe 10
        right.width shouldBe 10
        left.component.firstText().content() shouldBe "ab"
        center.component.firstText().content() shouldBe "\uE001\uE000"
        right.component.children().first().font() shouldBe spacerFont
        right.component.children().last().let { (it as TextComponent).content() } shouldBe "ab"
        listOf(left, center, right).forEach { pixelWidth(it.component) shouldBe 10 }
    }

    "keeps supplementary code points intact" {
        val emoji = "😀"
        val result = layoutOf(mapOf(0x1F600 to 7)).layout(Component.text(emoji), 8, TextAlignment.LEFT).requireAligned()
        result.component.textContent() shouldBe emoji + "\uE000"
        result.lineCount shouldBe 1
    }

    "uses explicit child bold false and preserves nested style inheritance" {
        val calls = mutableListOf<Boolean>()
        val widths = GlyphWidths { _, point, bold -> calls += bold; if (point == 'a'.code) 2 else null }
        val text = Component.text("a").decorate(TextDecoration.BOLD)
            .append(Component.text("a").decoration(TextDecoration.BOLD, TextDecoration.State.FALSE))
        val result = ComponentTextLayout(widths, spacerFont).layout(text, 8, TextAlignment.LEFT).requireAligned()

        calls shouldBe listOf(true, false)
        result.component.children().filterIsInstance<TextComponent>().map { it.decoration(TextDecoration.BOLD) }
            .take(2) shouldBe listOf(TextDecoration.State.TRUE, TextDecoration.State.FALSE)
    }

    "keeps click and hover events on text while spacers stay inert" {
        val text = Component.text("x")
            .clickEvent(ClickEvent.runCommand("/demo"))
            .hoverEvent(HoverEvent.showText(Component.text("info")))
        val result = layoutOf(mapOf('x'.code to 2)).layout(text, 8, TextAlignment.RIGHT).requireAligned()
        val runs = result.component.children().filterIsInstance<TextComponent>()
        runs.single { it.content() == "x" }.clickEvent()!!.value() shouldBe "/demo"
        runs.filter { it.font() == spacerFont }.forEach {
            it.clickEvent() shouldBe null
            it.hoverEvent() shouldBe null
        }
    }

    "keeps newline paragraphs and wraps long words" {
        val layout = layoutOf((0..127).associateWith { 1 })
        layout.layout(Component.text("a\nb"), 4, TextAlignment.LEFT).requireAligned().lineCount shouldBe 2
        layout.layout(Component.text("abcdef"), 3, TextAlignment.LEFT).requireAligned().lineCount shouldBe 2
        val paragraphs = layout.layout(Component.text("a\n\nb"), 4, TextAlignment.LEFT).requireAligned()
        paragraphs.lineCount shouldBe 3
        paragraphs.component.textContent().count { it == '\n' } shouldBe 2
        val words = layout.layout(Component.text("ab cd ef"), 5, TextAlignment.LEFT).requireAligned()
        words.component.textContent().filter { it !in '\uE000'..'\uE009' } shouldBe "ab cd\nef"
        layout.layout(Component.text("a".repeat(8193)), 1023, TextAlignment.LEFT)
            .shouldBeInstanceOf<TextLayoutResult.Unsupported>().reason shouldBe TextLayoutResult.Reason.LIMIT
    }

    "returns typed failures for unsupported content, fonts, directions and wide glyphs" {
        layoutOf(emptyMap()).layout(Component.translatable("block.minecraft.stone"), 10, TextAlignment.LEFT)
            .shouldBeInstanceOf<TextLayoutResult.Unsupported>().reason shouldBe TextLayoutResult.Reason.COMPONENT
        ComponentTextLayout(GlyphWidths { font, _, _ -> if (font == Key.key("minecraft:default")) null else 1 }, spacerFont)
            .layout(Component.text("a"), 10, TextAlignment.LEFT)
            .shouldBeInstanceOf<TextLayoutResult.Unsupported>().reason shouldBe TextLayoutResult.Reason.GLYPH
        layoutOf(mapOf('a'.code to 4)).layout(Component.text("a"), 3, TextAlignment.LEFT)
            .shouldBeInstanceOf<TextLayoutResult.Unsupported>().reason shouldBe TextLayoutResult.Reason.GLYPH_TOO_WIDE
        layoutOf(mapOf(0x05D0 to 1)).layout(Component.text("א"), 10, TextAlignment.LEFT)
            .shouldBeInstanceOf<TextLayoutResult.Unsupported>().reason shouldBe TextLayoutResult.Reason.DIRECTION
    }

    "rejects content widths outside the protocol-safe bounds" {
        val layout = layoutOf(mapOf('a'.code to 1))
        shouldThrow<IllegalArgumentException> { layout.layout(Component.text("a"), 0, TextAlignment.LEFT) }
        shouldThrow<IllegalArgumentException> { layout.layout(Component.text("a"), 1024, TextAlignment.LEFT) }
    }
}) {
    companion object {
        private fun layoutOf(widths: Map<Int, Int>) = ComponentTextLayout(
            GlyphWidths { font, point, _ -> if (font == Key.key("minecraft:default")) widths[point] else null },
            Key.key("arc:layout-spacer"),
        )

        private fun TextLayoutResult.requireAligned() = this as TextLayoutResult.Aligned

        private fun Component.firstText() = children().filterIsInstance<TextComponent>().first()

        private fun Component.textContent(): String = buildString {
            if (this@textContent is TextComponent) append(this@textContent.content())
            children().forEach { append(it.textContent()) }
        }

        private fun pixelWidth(component: Component): Int = component.textContent().codePoints().toArray().sumOf { point ->
            if (point in 0xE000..0xE009) 1 shl (point - 0xE000) else when (point) {
                'a'.code, 'b'.code -> 2
                else -> 0
            }
        }
    }
}
