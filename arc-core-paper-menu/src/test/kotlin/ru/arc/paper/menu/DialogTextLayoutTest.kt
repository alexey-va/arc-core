package ru.arc.paper.menu

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult

class DialogTextLayoutTest : FreeSpec({
    "the actual pack sample has four lines with the same 392px content box and correct edges" {
        val fonts = javaClass.getResourceAsStream("/fonts/dialog-font-metrics.json")!!
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("fonts") }
        val sample = MiniMessage.miniMessage().deserialize(
            "<bold>Горожанин</bold>\nДома: 1 → 2\nЧанки: 64 → 96\nБаланс: 12 500 💰")
        data class Line(var width: Int = 0, var start: Int? = null, var end: Int = 0)
        TextAlignment.entries.forEach { alignment ->
            val result = DialogTextLayout.layout(sample, alignment) as TextLayoutResult.Aligned
            result.lineCount shouldBe 4
            val lines = mutableListOf(Line())
            fun visit(component: Component, inherited: Style) {
                val style = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
                val font = style.font()?.asString() ?: "minecraft:default"
                (component as TextComponent).content().codePoints().forEach { point ->
                    if (point == '\n'.code) lines += Line()
                    else {
                        val line = lines.last()
                        if (point in 0xF0F01..0xF0F0A) line.width += 1 shl (point - 0xF0F01)
                        else {
                            val weight = if (style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) "bold" else "normal"
                            val advance = fonts.getAsJsonObject(font).getAsJsonObject(weight)
                                .get("U+%04X".format(point)).asInt
                            if (line.start == null) line.start = line.width
                            line.width += advance
                            line.end = line.width
                        }
                    }
                }
                component.children().forEach { visit(it, style) }
            }
            visit(result.component, Style.empty())
            lines.size shouldBe 4
            lines.forEach { line ->
                line.width shouldBe 392
                when (alignment) {
                    TextAlignment.LEFT -> line.start shouldBe 0
                    TextAlignment.RIGHT -> line.end shouldBe 392
                    TextAlignment.CENTER -> line.start shouldBe (392 - (line.end - line.start!!)) / 2
                }
            }
        }
        fonts.getAsJsonObject("minecraft:default").getAsJsonObject("normal").get("U+0020").asInt shouldBe 4
        fonts.getAsJsonObject("minecraft:default").getAsJsonObject("normal").get("U+1F4B0").asInt shouldBe 10
    }
    "farm point glyph has measured normal and bold advances" {
        DialogTextLayout.glyphWidth('\uE5A0') shouldBe 9
        listOf(false, true).forEach { bold ->
            val result = DialogTextLayout.layout(
                Component.text("100 \uE5A0").decoration(TextDecoration.BOLD, bold), TextAlignment.LEFT,
            )
            (result is TextLayoutResult.Aligned) shouldBe true
        }
    }
    "unmeasured custom fonts and unresolved client translations are explicitly unsupported" {
        DialogTextLayout.layout(Component.text("a").font(Key.key("other:font")), TextAlignment.LEFT) shouldBe
            TextLayoutResult.Unsupported(TextLayoutResult.Reason.GLYPH)
        DialogTextLayout.layout(Component.translatable("block.minecraft.stone"), TextAlignment.RIGHT) shouldBe
            TextLayoutResult.Unsupported(TextLayoutResult.Reason.COMPONENT)
    }
})
