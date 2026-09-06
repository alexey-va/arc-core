package ru.arc.text

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextDecoration

class PixelSpacingTest : FreeSpec({
    val font = Key.key("minecraft:default")
    val spacing = PixelSpacing(font, 0xF0F01)
    "every supported padding uses existing supplementary ItemsAdder glyphs with an exact total advance" {
        spacing.padding(0) shouldBe Component.empty()
        for (width in 1..1023) {
            val text = spacing.padding(width) as TextComponent
            text.font() shouldBe font
            val points = text.content().codePoints().toArray()
            points.all { it in 0xF0F01..0xF0F0A } shouldBe true
            points.sumOf { 1 shl (it - 0xF0F01) } shouldBe width
            TextDecoration.values().forEach { text.decoration(it) shouldBe TextDecoration.State.FALSE }
            text.clickEvent() shouldBe null
            text.hoverEvent() shouldBe null
        }
    }
    "layout accepts an existing font and does not emit legacy private font characters" {
        val result = ComponentTextLayout(GlyphWidths { _, _, _ -> 5 }, spacing)
            .layout(Component.text("x"), 10, TextAlignment.RIGHT) as TextLayoutResult.Aligned
        (result.component.children().first() as TextComponent).content().codePoints().toArray().toList() shouldBe
            listOf(0xF0F03, 0xF0F01)
    }
    "invalid widths and invalid Unicode ranges fail at the boundary" {
        shouldThrow<IllegalArgumentException> { spacing.padding(-1) }
        shouldThrow<IllegalArgumentException> { spacing.padding(1024) }
        listOf(-1, 0xD7F8, 0xD800, 0xDFFF, 0x10FFFF, Int.MAX_VALUE).forEach { point ->
            shouldThrow<IllegalArgumentException> { PixelSpacing(font, point) }
        }
    }
})
