package ru.arc.paper.menu

import com.google.gson.JsonParser
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import java.io.DataOutputStream
import java.io.OutputStream
import java.io.UTFDataFormatException
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
                    point in 0xFA300..0xFAF1B && (point and 15) <= 11 -> 17
                    point in 0x100000..0x109834 && (point and 31) <= 20 -> 17
                    point in 0xFA200..0xFA209 -> -(1 shl (point - 0xFA200))
                    point in 0xFA220..0xFA229 -> 1 shl (point - 0xFA220)
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
    fun textWithoutPackGlyphs(component: Component): String = buildString {
        fun appendText(node: Component) {
            if (node is TextComponent) node.content().codePoints().forEach { point ->
                if (point !in 0xE540..0xE59E && point !in 0xF0F01..0xF0F0A && point != 0xF0F11) {
                    appendCodePoint(point)
                }
            }
            node.children().forEach(::appendText)
        }
        appendText(component)
    }
    fun textRuns(component: Component): List<Pair<String, Style>> = buildList {
        fun visit(node: Component, parent: Style) {
            val style = node.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
            if (node is TextComponent && node.content().isNotEmpty()) add(node.content() to style)
            node.children().forEach { visit(it, style) }
        }
        visit(component, Style.empty())
    }
    fun assertClientPrefixes(component: Component, budget: Int) {
        var cursor = 0
        textRuns(component).forEach { (text, style) ->
            text.codePoints().forEach { point ->
                if (point == 10) cursor = 0
                else {
                    cursor += lineWidths(Component.text(String(Character.toChars(point))).style(style)).single()
                    withClue("Client wraps at U+${point.toString(16)} before any later negative spacer") {
                        (cursor <= budget) shouldBe true
                    }
                }
            }
        }
    }
    fun topFrameGeometry(component: Component, frame: DialogTables.Frame): Pair<Int, Int> {
        val firstLine = buildList {
            fun visit(node: Component) {
                if (node is TextComponent) node.content().codePoints().forEach(::add)
                node.children().forEach(::visit)
            }
            visit(component)
        }.takeWhile { it != '\n'.code }
        val left = firstLine.indexOf(frame.base)
        val right = firstLine.indexOf(frame.base + 3)
        require(left >= 0 && right > left)
        fun padding(points: List<Int>) = points.sumOf { point ->
            if (point in 0xF0F01..0xF0F0A) 1 shl (point - 0xF0F01) else 0
        }
        val border = firstLine.subList(left, right + 1)
        val tiles = border.count { it in frame.base..frame.base + 14 }
        val joins = border.count { it == 0xF0F11 }
        return padding(firstLine.take(left)) to (tiles * 10 - joins)
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

    "independent material columns keep three dividers and every wrapped cell aligned" {
        val headers = listOf("Предмет", "Запас", "Нужно", "У вас").map(Component::text)
        val rows = listOf(
            listOf("Резная книжная полка", "3", "4", "64"),
            listOf("Очень длинное имя строительного материала", "9000001", "2000003", "0"),
            listOf("Факел", "", "34", "0"),
        ).map { row -> row.map(Component::text) }
        for (frame in DialogTables.Frame.entries) for (width in listOf(320, 400, 468)) {
            val body = DialogTables.body(rows, headers, frame, width, listOf(5, 2, 2, 2))
            countCodePoint(body.text, frame.base + 2) shouldBe 3
            countCodePoint(body.text, frame.base + 9) shouldBe 3
            countCodePoint(body.text, frame.base + 13) shouldBe 3
            lineWidths(body.text).forEach { it shouldBe width - 8 }
            val content = PlainTextComponentSerializer.plainText().serialize(body.text).lineSequence()
                .filter { it.contains((frame.base + 4).toChar()) }.map { it.split((frame.base + 5).toChar()) }.toList()
            for (column in headers.indices) {
                content.joinToString("") { textWithoutPackGlyphs(Component.text(it[column])) }.filterNot(Char::isWhitespace) shouldBe
                    (listOf(headers) + rows).joinToString("") {
                        PlainTextComponentSerializer.plainText().serialize(it[column])
                    }.filterNot(Char::isWhitespace)
            }
        }
    }

    "multi column fallback preserves data and invalid cell counts fail before rendering" {
        val unknown = Component.text("custom value").font(Key.key("unknown:font"))
        val row = listOf(Component.text("Material"), unknown, Component.text("7"), Component.text("9"))
        val fallback = DialogTables.body(listOf(row), null, DialogTables.Frame.EPIC, 400, listOf(5, 2, 2, 2))
        PlainTextComponentSerializer.plainText().serialize(fallback.text) shouldBe "Material · custom value · 7 · 9"
        shouldThrow<IllegalArgumentException> {
            DialogTables.body(listOf(row), null, DialogTables.Frame.EPIC, 400, listOf(1, 1))
        }
        shouldThrow<IllegalArgumentException> {
            DialogTables.body(listOf(row), null, DialogTables.Frame.EPIC, 400, listOf(1, 0, 1, 1))
        }
    }

    "single framed text keeps one outer border, explicit blank lines and inline colors" {
        val frame = DialogTables.Frame.EPIC
        val content = Component.text("Красная строка\n\n", NamedTextColor.RED)
            .append(Component.text("Зелёная строка", NamedTextColor.GREEN))
        val body = DialogTables.framedBody(content, frame, 320)

        body.width shouldBe 320
        countCodePoint(body.text, frame.base + 0) shouldBe 1
        countCodePoint(body.text, frame.base + 3) shouldBe 1
        countCodePoint(body.text, frame.base + 11) shouldBe 1
        countCodePoint(body.text, frame.base + 14) shouldBe 1
        countCodePoint(body.text, frame.base + 4) shouldBe 3
        countCodePoint(body.text, frame.base + 6) shouldBe 3
        listOf(2, 5, 7, 8, 9, 10, 13).forEach { offset ->
            countCodePoint(body.text, frame.base + offset) shouldBe 0
        }
        lineWidths(body.text).forEach { it shouldBe 312 }
        textWithoutPackGlyphs(body.text).contains("Красная строка\n\nЗелёная строка") shouldBe true
        textRuns(body.text).first { it.first.contains("Красная строка") }.second.color() shouldBe NamedTextColor.RED
        textRuns(body.text).first { it.first.contains("Зелёная строка") }.second.color() shouldBe NamedTextColor.GREEN
    }

    "single framed text aligns its outer inset and border width with same-width tables" {
        val frame = DialogTables.Frame.EPIC
        val width = 320
        val table = DialogTables.body(
            listOf(Component.text("Описание") to Component.text("Содержимое")),
            frame = frame,
            width = width,
        )
        val framed = DialogTables.framedBody(Component.text("Содержимое"), frame, width)

        topFrameGeometry(table.text, frame) shouldBe (11 to 289)
        topFrameGeometry(framed.text, frame) shouldBe topFrameGeometry(table.text, frame)
        lineWidths(table.text).forEach { it shouldBe width - 8 }
        lineWidths(framed.text).forEach { it shouldBe width - 8 }
    }

    "single framed text wraps measured content and safely falls back when framing is unsupported" {
        val words = (1..80).joinToString(" ") { "длинное-$it" }
        val body = DialogTables.framedBody(Component.text(words), width = 320)
        lineWidths(body.text).forEach { it shouldBe 312 }
        words.split(' ').forEach { word ->
            textWithoutPackGlyphs(body.text).contains(word) shouldBe true
        }

        val unsupported = Component.translatable("block.minecraft.stone")
        val fallback = DialogTables.framedBody(unsupported, width = 320)
        fallback.text shouldBe unsupported

        val narrow = DialogTables.framedBody(Component.text("short"), width = 24)
        narrow.text shouldBe Component.text("short")
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

    "filled panels support one to eight independently wrapped columns with all backgrounds first" {
        for (count in 1..8) {
            val width = if (count <= 3) 508 else 1024
            val rows = List(3) { row -> List(count) { column ->
                Component.text(if (column == 0) "Длинное описание строки $row с сохранёнными словами и переносом" else "Ячейка $row/$column")
            } }
            val body = DialogTables.filledBody(rows, List(count) { Component.text("Колонка $it") }, width, List(count) { 1 })
            val widths = lineWidths(body.text)
            widths.forEach { it shouldBe width - 10 }
            assertClientPrefixes(body.text, width - 8)
            val runs = textRuns(body.text)
            val background = runs.first()
            background.second.font() shouldBe Key.key("arc:dialog_ui_preview")
            background.second.color() shouldBe NamedTextColor.WHITE
            background.second.shadowColor() shouldBe ShadowColor.none()
            val points = background.first.codePoints().toArray().filter { it in 0xFA300..0xFAF1B }
            points.map { (it - 0xFA300) / 16 }.toSet() shouldBe (0 until widths.size).toSet()
            points.count { (it and 15) in listOf(3, 7, 11) } shouldBe widths.size * (count - 1)
            runs.drop(1).forEach { (text, style) ->
                text.codePoints().noneMatch { it in 0xFA300..0xFAF1B } shouldBe true
                if (text.any(Char::isLetter)) {
                    style.font() shouldBe Key.key("minecraft:default")
                    style.shadowColor() shouldBe null
                }
            }
            val plain = PlainTextComponentSerializer.plainText().serialize(body.text)
            rows.flatten().forEach { cell ->
                val words = PlainTextComponentSerializer.plainText().serialize(cell).split(' ')
                words.forEach { plain.contains(it) shouldBe true }
            }
        }
    }

    "filled panel reaches the final row bank with compact background and preserved explicit text style" {
        val text = Component.text("А\nБ\nВ", NamedTextColor.AQUA).shadowColor(ShadowColor.shadowColor(0x80443322.toInt()))
        val wireFailures = mutableListOf<String>()
        for (columns in listOf(1, 8)) {
            val maximum = DialogTables.filledBody(List(64) { List(columns) { text } }, null, 1024, List(columns) { Int.MAX_VALUE })
            val background = maximum.text.children().first()
            withClue("The background uses a few bounded strings, not one component per tile") {
                (background.children().size < 512) shouldBe true
            }
            fun checkWireString(node: Component) {
                if (node is TextComponent) {
                    val value = node.content()
                    val bytes = value.sumOf { when (it.code) { in 1..127 -> 1; in 0..2047 -> 2; else -> 3 } }
                    if (value.isNotEmpty() && node.style().isEmpty()) (bytes <= 49152) shouldBe true
                    try {
                        DataOutputStream(OutputStream.nullOutputStream()).use { it.writeUTF(value) }
                    } catch (_: UTFDataFormatException) {
                        wireFailures += "$columns columns: ${value.codePointCount(0, value.length)} codepoints / $bytes modified UTF-8 bytes"
                    }
                }
                node.children().forEach(::checkWireString)
            }
            checkWireString(maximum.text)
        }
        withClue("Every actual text node must fit the NBT StringTag DataOutput.writeUTF boundary") { wireFailures shouldBe emptyList() }
        val body = DialogTables.filledBody(List(64) { listOf(text) }, null, 1024, listOf(Int.MAX_VALUE))
        lineWidths(body.text) shouldBe List(194) { 1014 }
        assertClientPrefixes(body.text, 1016)
        val runs = textRuns(body.text)
        countCodePoint(body.text, 0xFA300 + 193 * 16 + 8) shouldBe 1
        runs.filter { it.second.font() == Key.key("arc:dialog_ui_preview") }
            .any { it.first.codePoints().anyMatch { point -> point == 0xFA300 + 193 * 16 + 10 } } shouldBe true
        runs.drop(1).filter { it.first.any(Char::isLetter) }.forEach { (_, style) ->
            style.color() shouldBe NamedTextColor.AQUA
            style.shadowColor() shouldBe text.shadowColor()
        }
        body.text.children().first().children().size shouldBe 4
        val weighted = DialogTables.filledBody(listOf(List(8) { Component.text("А") }), null, 1024, List(8) { Int.MAX_VALUE })
        lineWidths(weighted.text).forEach { it shouldBe 1014 }
    }

    "filled panel limits and unknown metrics preserve all cells and invalid shapes fail early" {
        val cases = listOf(
            List(65) { listOf(Component.text("строка $it")) } to 320,
            listOf(listOf(Component.text((1..193).joinToString("\n") { "строка $it" }))) to 320,
            listOf(listOf(Component.text("неизвестный").font(Key.key("unknown:font")))) to 320,
            listOf(listOf(Component.text("узкий"))) to 24,
        )
        cases.forEach { (rows, width) ->
            val body = DialogTables.filledBody(rows, null, width, listOf(1))
            val plain = PlainTextComponentSerializer.plainText().serialize(body.text)
            plain.codePoints().noneMatch { it in 0xFA300..0xFAF1B } shouldBe true
            rows.flatten().forEach { plain.contains(PlainTextComponentSerializer.plainText().serialize(it)) shouldBe true }
        }
        shouldThrow<IllegalArgumentException> { DialogTables.filledBody(listOf(listOf(Component.text("a"))), null, 320, listOf(1, 1)) }
        shouldThrow<IllegalArgumentException> { DialogTables.filledBody(emptyList(), null, 320, listOf(0)) }
        shouldThrow<IllegalArgumentException> { DialogTables.filledBody(emptyList(), null, 320, List(9) { 1 }) }
    }

    "all skins retain the exact original foreground and weighted column geometry" {
        val rows = listOf(listOf(Component.text("Подпись"), Component.text("Значение")))
        val original = DialogTables.filledBody(rows, null, 320, listOf(1, 2))
        DialogTables.PanelSkin.entries.forEach { skin ->
            val themed = DialogTables.filledBody(rows, null, 320, listOf(1, 2), skin)
            themed.text.children().drop(1) shouldBe original.text.children().drop(1)
            lineWidths(themed.text) shouldBe lineWidths(original.text)
            assertClientPrefixes(themed.text, 312)
            textRuns(themed.text).first().second.font() shouldBe Key.key(skin.fontName)
        }
    }

    "compact facts fit measured content across skins layouts and columns without right justified values" {
        val facts = listOf("Баланс:" to "67.7M", "Жетоны:" to "3", "Дома:" to "1 / 3", "Приваты:" to "4 (124 чанков)")
            .map { (label, value) -> Component.text(label) to Component.text(value) }
        for (skin in DialogTables.PanelSkin.entries) for (layout in DialogTables.FactsLayout.entries) for (columns in 1..8) {
            val body = DialogTables.factsBody(facts, layout, DialogTables.PanelSpec(skin, 1024, columns, true))
            (body.width < 700) shouldBe true
            lineWidths(body.text).forEach { it shouldBe body.width - 10 }
            assertClientPrefixes(body.text, body.width - 8)
            val runs = textRuns(body.text)
            runs.first().second.font() shouldBe Key.key(skin.fontName)
            runs.first().second.shadowColor() shouldBe ShadowColor.none()
            runs.first().second.color() shouldBe NamedTextColor.WHITE
            runs.drop(1).filter { it.first.any(Char::isLetter) }.forEach { (_, style) ->
                style.font() shouldBe Key.key("minecraft:default")
                style.shadowColor() shouldBe null
            }
            facts.forEach { (label, value) ->
                val plain = PlainTextComponentSerializer.plainText().serialize(body.text)
                plain.contains(label.content()) shouldBe true
                plain.contains(value.content()) shouldBe true
            }
        }
        val body = DialogTables.factsBody(facts, DialogTables.FactsLayout.ROWS,
            DialogTables.PanelSpec(DialogTables.PanelSkin.NAVY, 468, 1, true))
        (body.width < 170) shouldBe true
        val fixed = DialogTables.factsBody(facts, DialogTables.FactsLayout.ROWS,
            DialogTables.PanelSpec(DialogTables.PanelSkin.NAVY, 468, 1, false))
        fixed.width shouldBe 468
        // Both forms share the same measured label/value block despite different outer width.
        fun gapBeforeValue(body: PaperDialogBody): Int {
            val runs = textRuns(body.text)
            val label = runs.indexOfFirst { it.first == "Приваты:" }
            val value = runs.indexOfFirst { it.first == "4 (124 чанков)" }
            return runs.subList(label + 1, value).sumOf { lineWidths(Component.text(it.first).style(it.second)).single() }
        }
        gapBeforeValue(body) shouldBe 8
        gapBeforeValue(fixed) shouldBe 8
        val five = facts + (Component.text("Мир:") to Component.text("Спавн"))
        val fourColumns = DialogTables.factsBody(five, DialogTables.FactsLayout.ROWS,
            DialogTables.PanelSpec(DialogTables.PanelSkin.NAVY, 1024, 4, true))
        countCodePoint(fourColumns.text, DialogTables.PanelSkin.NAVY.base) shouldBe 4
    }

    "narrow wrapped groups retain titles values and foreground styling with every background first" {
        val colored = Component.text("Очень длинное значение с сохранённым цветом", NamedTextColor.AQUA)
        val groups = listOf(
            DialogTables.FactsGroup(Component.text("Кошелёк и достижения"), listOf(Component.text("Длинная подпись") to colored)),
            DialogTables.FactsGroup(Component.text("Территория"), listOf(Component.text("Приваты") to Component.text("4 (124 чанков)"))),
        )
        for (skin in DialogTables.PanelSkin.entries) {
            val body = DialogTables.groupedFactsBody(groups, DialogTables.PanelSpec(skin, 250, 2, true))
            lineWidths(body.text).forEach { it shouldBe body.width - 10 }
            assertClientPrefixes(body.text, body.width - 8)
            val runs = textRuns(body.text)
            val firstForeground = runs.indexOfFirst { it.first.any(Char::isLetter) }
            runs.drop(firstForeground).all { it.second.font() != Key.key(skin.fontName) } shouldBe true
            val plain = PlainTextComponentSerializer.plainText().serialize(body.text)
            fun visible(value: String) = value.codePoints().toArray().filter {
                it !in 0xF0F01..0xF0F0A && it !in 0xFA200..0xFA229 && it !in 0x100000..0x109FFF &&
                    !Character.isWhitespace(it)
            }.sorted()
            val expected = groups.joinToString("") { group ->
                PlainTextComponentSerializer.plainText().serialize(group.title) + group.facts.joinToString("") {
                    PlainTextComponentSerializer.plainText().serialize(it.first) + PlainTextComponentSerializer.plainText().serialize(it.second)
                }
            }
            visible(plain) shouldBe visible(expected)
            runs.filter { it.first.contains("значение") }.forEach { it.second.color() shouldBe NamedTextColor.AQUA }
        }
        val unknown = Component.text("Неизвестный").font(Key.key("unknown:font"))
        val fallback = DialogTables.groupedFactsBody(listOf(DialogTables.FactsGroup(Component.text("Группа"),
            listOf(Component.text("Подпись") to unknown))), DialogTables.PanelSpec(DialogTables.PanelSkin.NAVY, 250, 1, true))
        PlainTextComponentSerializer.plainText().serialize(fallback.text) shouldBe "Группа\nПодпись · Неизвестный"
        val overflow = List(65) { Component.text("Подпись $it") to Component.text("Значение $it") }
        val unframed = DialogTables.factsBody(overflow, DialogTables.FactsLayout.METRICS,
            DialogTables.PanelSpec(DialogTables.PanelSkin.PLUM, 320, 1, true))
        PlainTextComponentSerializer.plainText().serialize(unframed.text).contains("Значение 64") shouldBe true
        textRuns(unframed.text).none { it.second.font() == Key.key("arc:dialog_panel_plum") } shouldBe true
    }

    "modular maximum panel keeps native wrap headroom and every NBT string bounded" {
        val rows = List(64) { Component.text("А\nБ\nВ") to Component.text("1\n2\n3") }
        for (skin in DialogTables.PanelSkin.entries) {
            val body = DialogTables.factsBody(rows, DialogTables.FactsLayout.ROWS, DialogTables.PanelSpec(skin, 1024, 1, false))
            lineWidths(body.text) shouldBe List(194) { 1014 }
            countCodePoint(body.text, skin.base + 193 * 32 + 14) shouldBe 1
            assertClientPrefixes(body.text, 1016)
            textRuns(body.text).forEach { (text, _) ->
                DataOutputStream(OutputStream.nullOutputStream()).use { it.writeUTF(text) }
            }
            (body.text.children().first().children().size < 512) shouldBe true
        }
    }

    "export first actual compact and narrow wrapped panel components" {
        val gold = net.kyori.adventure.text.format.TextColor.color(0xF4D87A)
        val facts = listOf(
            Component.text("Баланс:") to Component.text("67.7M ", gold).append(Component.text("💰", NamedTextColor.WHITE)),
            Component.text("Жетоны:") to Component.text("3 ", gold).append(Component.text("\uE516", NamedTextColor.WHITE)),
            Component.text("Дома:") to Component.text("1 / 3"),
            Component.text("Приваты:") to Component.text("4 (124 чанков)"),
        )
        val examples = listOf(
            "rows" to DialogTables.factsBody(facts, DialogTables.FactsLayout.ROWS,
                DialogTables.PanelSpec(DialogTables.PanelSkin.NAVY, 468, 1, true)),
            "rows-long" to DialogTables.factsBody(facts + (Component.text("Описание территории:") to
                Component.text("Длинное описание переносится внутри своей колонки и остаётся полностью читаемым")),
                DialogTables.FactsLayout.ROWS, DialogTables.PanelSpec(DialogTables.PanelSkin.NAVY, 180, 1, true)),
            "metrics" to DialogTables.factsBody(facts, DialogTables.FactsLayout.METRICS,
                DialogTables.PanelSpec(DialogTables.PanelSkin.PLUM, 468, 2, true)),
            "capsules" to DialogTables.factsBody(facts, DialogTables.FactsLayout.CAPSULES,
                DialogTables.PanelSpec(DialogTables.PanelSkin.EVERGREEN, 468, 2, true)),
            "groups" to DialogTables.groupedFactsBody(listOf(DialogTables.FactsGroup(Component.text("Кошелёк"), facts.take(2)),
                DialogTables.FactsGroup(Component.text("Территория"), facts.drop(2))),
                DialogTables.PanelSpec(DialogTables.PanelSkin.COCOA, 468, 2, true)),
            "ornate-icons" to DialogTables.factsBody(facts.mapIndexed { index, (label, value) ->
                DialogDecoration.fieldLabel(listOf(DialogDecoration.FieldIcon.BALANCE, DialogDecoration.FieldIcon.TOKENS,
                    DialogDecoration.FieldIcon.HOMES, DialogDecoration.FieldIcon.LANDS)[index], label) to value
            }, DialogTables.FactsLayout.ROWS, DialogTables.PanelSpec(DialogTables.PanelSkin.ORNATE, 468, 1, true)),
            "heading" to DialogDecoration.library().headingBody(Component.text("Ресурсы и территория"), 468),
            "list" to DialogDecoration.library().listBody(listOf(
                DialogDecoration.ListEntry(DialogDecoration.Marker.BULLET, Component.text("Обычный пункт")),
                DialogDecoration.ListEntry(DialogDecoration.Marker.COMPLETE, Component.text("Условие выполнено")),
                DialogDecoration.ListEntry(DialogDecoration.Marker.PENDING, Component.text("Длинное условие переносится с сохранением отступа")),
            ), 180),
            "steps" to DialogDecoration.library().stepsBody(listOf(
                DialogDecoration.Step(DialogDecoration.StepState.COMPLETE, Component.text("Выбрать участок")),
                DialogDecoration.Step(DialogDecoration.StepState.CURRENT, Component.text("Проверить границы")),
                DialogDecoration.Step(DialogDecoration.StepState.PENDING, Component.text("Подтвердить создание")),
            ), 220),
            "progress" to DialogDecoration.library().progressBody(Component.text("Изучение"), 3.0, 10.0,
                Component.text("3 / 10"), 250, 10, DialogDecoration.ProgressColor.BLUE),
            "state" to PaperDialogBody(DialogTextLayout.layout(DialogDecoration.stateBadge(Component.text("Готово"),
                DialogDecoration.StateTone.SUCCESS), ru.arc.text.TextAlignment.LEFT, 160).let {
                    (it as ru.arc.text.TextLayoutResult.Aligned).component
                }, 160),
        )
        val report = Path.of("build/reports/dialog-panels/components.json")
        Files.createDirectories(report.parent)
        val serializer = GsonComponentSerializer.gson()
        Files.writeString(report, JsonArray().apply { examples.forEach { (id, body) ->
            add(JsonObject().apply { addProperty("id", id); addProperty("width", body.width)
                add("component", JsonParser.parseString(serializer.serialize(body.text))) })
        } }.toString())
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
