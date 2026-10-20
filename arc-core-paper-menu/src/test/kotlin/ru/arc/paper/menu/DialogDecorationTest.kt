package ru.arc.paper.menu

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class DialogDecorationTest : FreeSpec({
    val font = Key.key("test:dialog")
    val decoration = DialogDecoration(font, 0xF8000, 0xF8010)
    val frame = DialogDecoration.ButtonFrame(
        DialogDecoration.Glyph(0xF8100, 16), DialogDecoration.Glyph(0xF8101, 17), DialogDecoration.Glyph(0xF8102, 15),
    )
    val icon = DialogDecoration.Glyph(0xF8103, 11)
    val advances = mapOf(0xF8100 to 16, 0xF8101 to 17, 0xF8102 to 15, 0xF8103 to 11) +
        (0xF8200..0xF8206).associateWith { 13 }
    val fonts = javaClass.getResourceAsStream("/fonts/dialog-font-metrics.json")!!
        .bufferedReader().use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("fonts") }
    data class Ink(val point: Int, val x: Int, val style: Style)
    data class Line(var width: Int = 0, val ink: MutableList<Ink> = mutableListOf())
    fun measure(component: Component): List<Line> {
        val lines = mutableListOf(Line())
        fun visit(node: Component, inherited: Style) {
            val style = node.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
            (node as TextComponent).content().codePoints().forEach { point ->
                if (point == 10) lines += Line() else {
                    val line = lines.last()
                    val spacing = when (point) {
                        in 0xF8000..0xF8009 -> 1 shl (point - 0xF8000)
                        in 0xF8010..0xF8019 -> -(1 shl (point - 0xF8010))
                        in 0xF0F01..0xF0F0A -> 1 shl (point - 0xF0F01)
                        else -> null
                    }
                    if (spacing != null) line.width += spacing else {
                        line.ink += Ink(point, line.width, style)
                        val weight = if (style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) "bold" else "normal"
                        line.width += if (style.font() == font) advances.getValue(point) else
                            fonts.getAsJsonObject((style.font() ?: Key.key("minecraft:default")).asString())
                                .getAsJsonObject(weight).get("U+%04X".format(point)).asInt
                    }
                }
            }
            node.children().forEach { visit(it, style) }
        }
        visit(component, Style.empty())
        return lines
    }

    "native widget widths retain whole-widget centering and independent 16px icon inset" {
        val label = Component.text("Поиск").color(NamedTextColor.AQUA)
        val textWidth = measure(label).single().width
        for (width in listOf(150, 159, 166, 200, 230)) {
            for (rightIcon in listOf(false, true)) {
                val result = measure(decoration.buttonLabel(label, width, frame, icon, rightIcon)).single()
                result.width shouldBe width - 4
                result.ink.first().point shouldBe 0xF8100
                result.ink.first().x shouldBe -1
                result.ink.single { it.point == 'П'.code }.x + 2 shouldBe (width - textWidth) / 2
                val expectedIcon = if (rightIcon) width - 28 else 14
                result.ink.single { it.point == icon.codePoint }.x shouldBe expectedIcon
                result.ink.filter { it.style.font() == font }.forEach {
                    it.style.color() shouldBe NamedTextColor.WHITE
                    TextDecoration.values().forEach { decoration -> it.style.decoration(decoration) shouldBe TextDecoration.State.FALSE }
                }
                val fill = result.ink.filter { it.point == frame.fill.codePoint }
                fill.first().x shouldBe 14
                fill.last().x + 16 shouldBe width - 18
                fill.zipWithNext().all { (a, b) -> b.x <= a.x + 16 } shouldBe true
            }
        }
    }

    "unmeasured or multiline labels preserve the original native label without partial artwork" {
        val unknown = Component.translatable("gui.back")
        decoration.buttonLabel(unknown, 166, frame, icon) shouldBe unknown
        val multiline = Component.text("Первая\nВторая")
        decoration.buttonLabel(multiline, 166, frame) shouldBe multiline
        val long = Component.text("Подробная подпись")
        val output = measure(decoration.buttonLabel(long, 150, frame, icon)).single()
        output.ink.none { it.point == icon.codePoint } shouldBe true
        output.width shouldBe 146
        decoration.buttonLabel(long, 1024, frame, icon) shouldBe long
    }

    "open corners and a divider have equal measured edges and preserve content" {
        val corners = DialogDecoration.Corners(DialogDecoration.Glyph(0xF8200, 13), DialogDecoration.Glyph(0xF8201, 13),
            DialogDecoration.Glyph(0xF8202, 13), DialogDecoration.Glyph(0xF8203, 13))
        val body = decoration.openCorners(Component.text("Игрок: GrocerMC\nДома: 2 / 3"), 320, corners)
        val rows = measure(body.text)
        rows.size shouldBe 4
        rows.forEach { it.width shouldBe 312 }
        val plain = PlainTextComponentSerializer.plainText().serialize(body.text)
        plain.contains("Игрок: GrocerMC") shouldBe true
        plain.contains("Дома: 2 / 3") shouldBe true
        val divider = decoration.divider(320, DialogDecoration.Divider(
            DialogDecoration.Glyph(0xF8204, 13), DialogDecoration.Glyph(0xF8205, 13), DialogDecoration.Glyph(0xF8206, 13),
        ))
        measure(divider).single().width shouldBe 312
        measure(decoration.openCorners(Component.text("Текст"), 1024, corners).text).forEach { it.width shouldBe 1016 }
    }
})
