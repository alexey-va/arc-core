package ru.arc.paper.menu

import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
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
                        in 0xF9F00..0xF9F09 -> 1 shl (point - 0xF9F00)
                        in 0xF9F10..0xF9F19 -> -(1 shl (point - 0xF9F10))
                        else -> null
                    }
                    if (spacing != null) line.width += spacing else {
                        line.ink += Ink(point, line.width, style)
                        val weight = if (style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) "bold" else "normal"
                        line.width += if (style.font() == font) advances.getValue(point)
                        else if (style.font() == Key.key("arc:dialog_ui")) when (point) {
                            in 0xF9104..0xF9106, in 0xF9108..0xF910A -> 13
                            0xF9107 -> 2
                            0xF910B -> 6
                            0xF910C, 0xF910D -> 10
                            in 0xF9138..0xF9147 -> if (point % 2 == 0) 10 else 9
                            else -> error("Unmeasured library glyph $point")
                        } else
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
        val narrow = measure(decoration.buttonLabel(Component.text("ОК"), 64, frame, icon)).single()
        narrow.width shouldBe 60
        narrow.ink.none { it.point == icon.codePoint } shouldBe true
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

    "library lists keep explicit states and hanging indents through wrapped and unknown text" {
        val library = DialogDecoration.library()
        val entries = listOf(
            DialogDecoration.ListEntry(DialogDecoration.Marker.BULLET, Component.text("Обычный пункт")),
            DialogDecoration.ListEntry(DialogDecoration.Marker.COMPLETE, Component.text("Сделано\nПродолжение", NamedTextColor.AQUA)),
            DialogDecoration.ListEntry(DialogDecoration.Marker.PENDING, Component.text("Осталось")),
        )
        val body = library.listBody(entries, 180)
        val measured = measure(body.text)
        measured.map { it.width } shouldBe List(4) { 172 }
        measured[1].ink.first().point shouldBe 0xF910C
        measured[2].ink.first().x shouldBe 16
        measured[2].ink.first().style.color() shouldBe NamedTextColor.AQUA
        measured[3].ink.first().point shouldBe 0xF910D
        measured.flatMap { it.ink }.filter { it.style.font() == Key.key("arc:dialog_ui") }.forEach {
            it.style.color() shouldBe NamedTextColor.WHITE
            it.style.shadowColor() shouldBe ShadowColor.none()
        }
        val unknown = Component.text("Данные").font(Key.key("unknown:font"))
        PlainTextComponentSerializer.plainText().serialize(library.listBody(
            listOf(DialogDecoration.ListEntry(DialogDecoration.Marker.COMPLETE, unknown)), 180).text) shouldBe "✓ Данные"
    }

    "connected steps preserve explicit states and connector alignment through wrapped labels" {
        val body = DialogDecoration.library().stepsBody(listOf(
            DialogDecoration.Step(DialogDecoration.StepState.COMPLETE, Component.text("Готово\nПодробности")),
            DialogDecoration.Step(DialogDecoration.StepState.CURRENT, Component.text("Сейчас")),
            DialogDecoration.Step(DialogDecoration.StepState.PENDING, Component.text("Далее")),
        ), 180)
        val measured = measure(body.text)
        measured.forEach { it.width shouldBe 172 }
        measured.flatMap { it.ink }.filter { it.point in 0xF9108..0xF910A }.map { it.point } shouldBe
            listOf(0xF9108, 0xF9109, 0xF910A)
        measured.flatMap { it.ink }.filter { it.point == 0xF910B }.forEach { it.x shouldBe 2 }
        measured.last().ink.none { it.point == 0xF910B } shouldBe true
    }

    "progress bars use supplied finite numbers and omit artwork for unknown progress" {
        val library = DialogDecoration.library()
        val body = library.progressBody(Component.text("Цель"), 3.0, 10.0, Component.text("3 / 10"),
            320, 10, DialogDecoration.ProgressColor.MINT)
        val measured = measure(body.text)
        measured.forEach { it.width shouldBe 312 }
        measured.last().ink.count { it.point == 0xF9138 } shouldBe 3
        measured.last().ink.count { it.point == 0xF913A } shouldBe 7
        val complete = library.progressBody(Component.text("Цель"), 15.0, 10.0, Component.text("15 / 10"),
            320, 10, DialogDecoration.ProgressColor.BLUE)
        measure(complete.text).last().ink.count { it.point == 0xF913C } shouldBe 10
        val unknown = library.progressBody(Component.text("Цель"), null, null, Component.text("Нет данных"),
            320, 10, DialogDecoration.ProgressColor.MINT)
        PlainTextComponentSerializer.plainText().serialize(unknown.text) shouldBe "Цель · Нет данных"
        shouldThrow<IllegalArgumentException> {
            library.progressBody(Component.text("Цель"), Double.NaN, 10.0, Component.text("Нет данных"),
                320, 10, DialogDecoration.ProgressColor.MINT)
        }
    }

    "heading caps retain exact outer ink insets and matching gaps to measured native title" {
        val title = Component.text("Ресурсы и территория")
        val titleWidth = DialogTextLayout.naturalWidth(title)!!
        for (width in listOf(300, 467, 468, 600)) {
            val line = measure(DialogDecoration.library().headingBody(title, width).text).single()
            line.width shouldBe width - 8
            val caps = line.ink.filter { it.point == 0xF9104 || it.point == 0xF9105 }
            caps.size shouldBe 4
            caps.first().x shouldBe 12
            caps.last().x + 12 shouldBe width - 20
            val start = line.ink.single { it.point == 'Р'.code }.x
            start - (caps[1].x + 12) shouldBe 12
            caps[2].x - (start + titleWidth) shouldBe 12
            kotlin.math.abs(2 * start + titleWidth - (width - 8)) shouldBe (width - 8 - titleWidth) % 2
            line.ink.filter { it.point in 0xF9104..0xF9107 }.forEach { it.style.shadowColor() shouldBe ShadowColor.none() }
        }
        DialogDecoration.library().headingBody(title, 80).text shouldBe title.colorIfAbsent(net.kyori.adventure.text.format.TextColor.color(0xE8DFD2))
    }

    "state badge preserves explicit words and only supplies readable missing color" {
        val original = Component.text("Готово", NamedTextColor.WHITE)
        val badge = DialogDecoration.stateBadge(original, DialogDecoration.StateTone.SUCCESS)
        PlainTextComponentSerializer.plainText().serialize(badge) shouldBe "[ Готово ]"
        measure(badge).single().ink.single { it.point == 'Г'.code }.style.color() shouldBe NamedTextColor.WHITE
    }

    "diamond heading keeps native gold wings within mirrored outer bounds and preserves the plain overload" {
        val library = DialogDecoration.library()
        val title = Component.text("Ресурсы и территория")
        val titleWidth = DialogTextLayout.naturalWidth(title)!!
        for (width in listOf(300, 467, 468, 600)) {
            library.headingBody(title, width) shouldBe library.headingBody(title, width, DialogDecoration.HeadingStyle.PLAIN)
            val line = measure(library.headingBody(title, width, DialogDecoration.HeadingStyle.DIAMOND).text).single()
            line.width shouldBe width - 8
            val ornaments = line.ink.filter { it.point == '─'.code || it.point == '◆'.code }
            val diamonds = ornaments.filter { it.point == '◆'.code }
            diamonds.size shouldBe 2
            ornaments.first().x shouldBe 12
            ornaments.last().x + 8 shouldBe width - 20
            (kotlin.math.abs(diamonds.first().x + diamonds.last().x + 5 - (width - 8)) <= 1) shouldBe true
            val start = line.ink.single { it.point == 'Р'.code }.x
            val leftEnd = ornaments.filter { it.x < start }.maxOf { it.x + if (it.point == '─'.code) 8 else 5 }
            val rightStart = ornaments.filter { it.x > start + titleWidth }.minOf { it.x }
            start - leftEnd shouldBe 12
            rightStart - start - titleWidth shouldBe 12
            ornaments.forEach {
                it.style.font() shouldBe Key.key("minecraft:default")
                it.style.shadowColor() shouldBe ShadowColor.none()
            }
        }
        library.headingBody(title, 80, DialogDecoration.HeadingStyle.DIAMOND).text shouldBe
            title.colorIfAbsent(net.kyori.adventure.text.format.TextColor.color(0xE8DFD2))
        val body = library.headingBody(title, 468, DialogDecoration.HeadingStyle.DIAMOND)
        val record = com.google.gson.JsonObject().apply {
            addProperty("id", "heading-diamond"); addProperty("width", body.width)
            add("component", JsonParser.parseString(net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(body.text)))
        }
        val path = java.nio.file.Path.of("build/reports/dialog-panels/heading-diamond.json")
        java.nio.file.Files.createDirectories(path.parent)
        java.nio.file.Files.writeString(path, com.google.gson.JsonArray().apply { add(record) }.toString())
    }

    "field icons preserve canonical currencies and a measured 12px label start" {
        DialogDecoration.FieldIcon.entries.forEach { icon ->
            val label = DialogDecoration.fieldLabel(icon, Component.text("Поле", NamedTextColor.AQUA))
            val line = measure(label).single()
            line.ink.first().point shouldBe icon.point
            line.ink.first().style.font() shouldBe Key.key(icon.fontName)
            line.ink.first().style.color() shouldBe NamedTextColor.WHITE
            line.ink.first().style.shadowColor() shouldBe null
            line.ink.single { it.point == 'П'.code }.x shouldBe 12
            line.ink.single { it.point == 'П'.code }.style.color() shouldBe NamedTextColor.AQUA
        }
        DialogDecoration.FieldIcon.BALANCE.point shouldBe 0x1F4B0
        DialogDecoration.FieldIcon.TOKENS.point shouldBe 0xE516
    }
})
