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
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.nio.file.Files
import java.nio.file.Path

class DialogTablesTest : FreeSpec({
    val fonts = javaClass.getResourceAsStream("/fonts/dialog-font-metrics.json")!!.bufferedReader()
        .use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("fonts") }
    fun lineWidths(component: Component): List<Int> {
        val lines = mutableListOf(0)
        fun visit(node: Component, parent: Style) {
            val style = node.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
            val bold = style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE
            (node as TextComponent).content().codePoints().forEach { point ->
                if (point == 10) lines += 0
                else lines[lines.lastIndex] += when {
                    point in 0xF0F01..0xF0F0A -> 1 shl (point - 0xF0F01)
                    point == 0xF0F11 -> -1
                    point in 0xE540..0xE59E && (point and 15) <= 14 -> {
                        bold shouldBe false
                        style.color() shouldBe NamedTextColor.WHITE
                        val frame = (point - 0xE540) / 16
                        when (point and 15) {
                            4 -> listOf(2, 2, 2, 4, 4, 6)[frame]
                            5 -> listOf(6, 6, 6, 6, 6, 7)[frame]
                            6 -> listOf(10, 10, 10, 8, 8, 8)[frame]
                            10 -> if (frame >= 4) 8 else 10
                            else -> 10
                        }
                    }
                    else -> fonts.getAsJsonObject(style.font()?.asString() ?: "minecraft:default")
                        .getAsJsonObject(if (bold) "bold" else "normal").get("U+%04X".format(point)).asInt
                }
            }
            node.children().forEach { visit(it, style) }
        }
        visit(component, Style.empty())
        return lines
    }
    fun countCodePoint(component: Component, codePoint: Int): Int {
        fun count(node: Component): Int =
            node.children().sumOf(::count) + node.let { (it as TextComponent).content().codePoints().filter { point -> point == codePoint }.count().toInt() }
        return count(component)
    }

    "all frame joins remain aligned with wrapped values, blank cells and different dialog widths" {
        val rows = listOf(
            Component.text("Владелец") to Component.text("Очень длинное имя владельца поселения"),
            Component.text("Координаты") to Component.text("-123456, 128, 987654"),
            Component.text("Описание") to Component.text("Первая строка\nВторая строка", NamedTextColor.GREEN),
            Component.empty() to Component.text("Пустая подпись"),
        )
        for (frame in DialogTables.Frame.entries) for (width in listOf(220, 280, 320, 400, 468, 600)) {
            val result = DialogTables.render(rows, Component.text("Параметр") to Component.text("Значение"), frame, width)
            require(result is DialogTables.Result.Framed)
            lineWidths(result.component).forEach { it shouldBe width - 8 }
        }
    }

    "compact sibling tables keep the same edges and column divider" {
        val summary = listOf(Component.text("Прогресс") to Component.text("120 / 500"))
        val perks = listOf(Component.text("Опыт специализации") to Component.text(
            "Длинное описание бонуса переносится внутри колонки без потери текста"))
        val tables = listOf(summary, perks).map { rows ->
            val body = DialogTables.body(rows, columns = DialogTables.Columns.BALANCED)
            body.width shouldBe 320
            lineWidths(body.text).forEach { it shouldBe 312 }
            DialogTables.render(rows, width = body.width, columns = DialogTables.Columns.BALANCED) as DialogTables.Result.Framed
        }
        tables[0].columnWidths shouldBe tables[1].columnWidths
    }

    "row separators are opt-in and the header separator stays independent" {
        val rows = listOf(Component.text("A") to Component.text("one\ntwo"), Component.text("B") to Component.text("three"))
        DialogTables.Frame.entries.forEach { frame ->
            val plain = DialogTables.render(rows, frame = frame)
            countCodePoint(plain.component, frame.base + 7) shouldBe 0
            val explicit = DialogTables.render(rows, frame = frame, spec = DialogTables.Spec())
            explicit.component shouldBe plain.component
            val header = DialogTables.render(rows, headers = Component.text("Key") to Component.text("Value"), frame = frame)
            countCodePoint(header.component, frame.base + 7) shouldBe 1
            val divided = DialogTables.body(rows, DialogTables.Spec(rowSeparators = true), frame = frame)
            countCodePoint(divided.text, frame.base + 7) shouldBe 1
        }
    }

    "wrapped tables separate logical rows once, with no trailing or per-wrap separators" {
        val rows = listOf(
            Component.text("Короткая") to Component.text("Первая строка\nВторая строка\nТретья строка"),
            Component.text("Ещё одна") to Component.text("Короткое значение"),
            Component.text("Финальная") to Component.text("Последнее значение"),
        )
        val result = DialogTables.render(rows, frame = DialogTables.Frame.EPIC, width = 320, spec = DialogTables.Spec(rowSeparators = true))
        require(result is DialogTables.Result.Framed)
        countCodePoint(result.component, DialogTables.Frame.EPIC.base + 7) shouldBe 2
        lineWidths(result.component).forEach { it shouldBe 312 }
    }

    "header and short logical rows each receive one separator" {
        val shortRows = listOf(
            Component.text("Имя") to Component.text("Значение"),
            Component.text("Ещё") to Component.text("Данные"),
        )
        val short = DialogTables.render(shortRows, frame = DialogTables.Frame.EPIC, width = 320, spec = DialogTables.Spec(rowSeparators = true))
        require(short is DialogTables.Result.Framed)
        countCodePoint(short.component, DialogTables.Frame.EPIC.base + 7) shouldBe 1

        val wrapped = DialogTables.render(
            listOf(
                Component.text("Имя") to Component.text("Значение\nПродолжение"),
                Component.text("Ещё") to Component.text("Данные"),
            ),
            headers = Component.text("Поле") to Component.text("Значение"),
            frame = DialogTables.Frame.EPIC,
            width = 320,
            spec = DialogTables.Spec(rowSeparators = true),
        )
        require(wrapped is DialogTables.Result.Framed)
        countCodePoint(wrapped.component, DialogTables.Frame.EPIC.base + 7) shouldBe 2
        lineWidths(wrapped.component).forEach { it shouldBe 312 }
    }

    "export the six path rows for visual renderer review" {
        val rows = listOf(
            Component.text("Земледелие") to Component.text("716 / 50000\nСтупень: Без ступени"),
            Component.text("Промышленность") to Component.text("11 / 25000\nСтупень: Без ступени"),
            Component.text("Торговля") to Component.text("100014280 / 4000000\nСтупень: III"),
            Component.text("Исследование") to Component.text("2260 / 400000"),
            Component.text("Строительство") to Component.text("12 / 80000"),
            Component.text("Сообщество") to Component.text("93 / 4000"),
        )
        val result = DialogTables.render(
            rows,
            width = 320,
            spec = DialogTables.Spec(rowSeparators = true),
            columns = DialogTables.Columns.BALANCED,
            frame = DialogTables.Frame.EPIC,
        )
        require(result is DialogTables.Result.Framed)
        lineWidths(result.component).forEach { it shouldBe 312 }

        val report = Path.of("build/reports/dialog-tables/path-screenshot.txt")
        Files.createDirectories(report.parent)
        Files.writeString(report, MiniMessage.miniMessage().serialize(result.component))
    }

    "the approved demo keeps its exact geometry" {
        val rows = listOf(Component.text("Владелец") to Component.text("Тихий мастер"))
        DialogTables.Frame.entries.forEach { frame ->
            val result = DialogTables.render(rows, frame = frame, columns = DialogTables.Columns.LABEL_WIDE)
            require(result is DialogTables.Result.Framed)
            result.columnWidths.first shouldBe 251
            lineWidths(result.component).forEach { it shouldBe 392 }
        }
    }

    "unsupported fonts and client translations retain readable data without broken frame glyphs" {
        val values = listOf(Component.text("custom").font(Key.key("unknown:font")), Component.translatable("block.minecraft.stone"))
        values.forEach { value ->
            val result = DialogTables.render(listOf(Component.text("Предмет") to value))
            require(result is DialogTables.Result.Unframed)
            val plain = PlainTextComponentSerializer.plainText().serialize(result.component)
            require(plain.startsWith("Предмет: "))
            require(plain.codePoints().noneMatch { it in 0xE540..0xE59E })
        }
    }
})
