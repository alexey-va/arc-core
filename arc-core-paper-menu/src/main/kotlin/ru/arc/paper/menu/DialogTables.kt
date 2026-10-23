package ru.arc.paper.menu

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult
import ru.arc.text.PixelSpacing
import kotlin.math.abs

/**
 * RusCrafting pack adapter for native dialog tables (gallery T13–T18).
 * The existing core text engine owns measurement and wrapping; this adapter owns
 * the pack's 9px frame tiles, their joins and the client-approved cell insets.
 * Pure after font initialization; no player state, actions or markup parsing.
 * Unknown fonts/components retain all information in an unframed body.
 */
object DialogTables {
    enum class Frame(
        internal val base: Int,
        internal val leftAdvance: Int,
        internal val innerAdvance: Int,
        internal val rightAdvance: Int,
        internal val rightJointAdvance: Int = 10,
    ) {
        COMMON(0xE540, 2, 6, 10),
        UNCOMMON(0xE550, 2, 6, 10),
        RARE(0xE560, 2, 6, 10),
        EPIC(0xE570, 4, 6, 8),
        LEGENDARY(0xE580, 4, 6, 8, 8),
        ARTIFACT(0xE590, 6, 7, 8, 8),
    }

    /** AUTO minimizes wrapped row height; LABEL_WIDE preserves the T13–T18 demo. */
    enum class Columns { AUTO, BALANCED, LABEL_WIDE, VALUE_WIDE }

    data class Spec(val rowSeparators: Boolean = false)

    /** Complete authored skins; these do not change the six tooltip-frame styles. */
    enum class PanelSkin(internal val fontName: String, internal val base: Int) {
        NAVY("arc:dialog_panel_navy", 0x100000),
        PLUM("arc:dialog_panel_plum", 0x102000),
        EVERGREEN("arc:dialog_panel_evergreen", 0x104000),
        COCOA("arc:dialog_panel_cocoa", 0x106000),
        ORNATE("arc:dialog_panel_ornate", 0x108000),
    }

    enum class FactsLayout { ROWS, METRICS, CAPSULES }

    /** Width includes native padding. Fit content shrinks the whole composition, never the glyphs. */
    data class PanelSpec(val skin: PanelSkin, val maxWidth: Int, val columns: Int, val fitContent: Boolean) {
        init {
            require(maxWidth in 9..1024) { "Dialog panel width must be in 9..1024 GUI pixels" }
            require(columns in 1..8) { "Dialog panels require 1..8 columns" }
        }
    }

    data class FactsGroup(val title: Component, val facts: List<Pair<Component, Component>>)

    sealed interface Result {
        val component: Component
        data class Framed(override val component: Component, val columnWidths: Pair<Int, Int>) : Result
        data class Unframed(override val component: Component) : Result
    }

    private val font = Key.key("minecraft:default")
    private val undecorated = TextDecoration.values().associateWith { TextDecoration.State.FALSE }
    private val labelColor = TextColor.color(0x9AA8B7)
    private val valueColor = TextColor.color(0xE6EDF3)
    private val headerColor = TextColor.color(0xE8C383)
    private const val TILE = 9
    private const val MIN_OUTER_INSET = 11
    private const val TEXT_START = 10
    private val filledFont = Key.key("arc:dialog_ui_preview")
    private val filledTextColor = TextColor.color(0xE8DFD2)

    fun body(
        rows: List<Pair<Component, Component>>,
        headers: Pair<Component, Component>? = null,
        frame: Frame = Frame.EPIC,
        width: Int = 320,
        columns: Columns = Columns.AUTO,
    ): PaperDialogBody = PaperDialogBody(render(rows, headers, frame, width, columns).component, width)

    fun body(
        rows: List<Pair<Component, Component>>,
        spec: Spec,
        headers: Pair<Component, Component>? = null,
        frame: Frame = Frame.EPIC,
        width: Int = 320,
        columns: Columns = Columns.AUTO,
    ): PaperDialogBody = PaperDialogBody(render(rows, headers, frame, width, columns, spec).component, width)

    /**
     * Independent measured columns with fixed relative widths. Rows and optional
     * headers must match [columnWeights]; every weight is positive. Uses the same
     * pack tiles and cell insets as the paired table, wrapping each cell without
     * moving its neighbours. Unknown metrics retain every cell as readable text.
     */
    fun body(
        rows: List<List<Component>>,
        headers: List<Component>?,
        frame: Frame,
        width: Int,
        columnWeights: List<Int>,
    ): PaperDialogBody {
        require(width in 9..1024) { "Dialog table width must be in 9..1024 GUI pixels" }
        require(columnWeights.size in 2..8 && columnWeights.all { it > 0 }) { "Dialog tables require 2..8 positive column weights" }
        val count = columnWeights.size
        require(rows.all { it.size == count } && (headers == null || headers.size == count)) { "Dialog table cells must match column weights" }
        val heading = headers?.map { it.colorIfAbsent(headerColor).decorate(TextDecoration.BOLD) }
        val values = rows.map { row -> row.mapIndexed { index, cell -> cell.colorIfAbsent(if (index == 0) labelColor else valueColor) } }
        val allRows = listOfNotNull(heading) + values
        fun fallback() = PaperDialogBody(lines(allRows.map { row ->
            join(row.flatMapIndexed { index, cell -> if (index == 0) listOf(cell) else listOf(Component.text(" · ", valueColor), cell) })
        }), width)
        val tiles = (width - 8 - MIN_OUTER_INSET * 2 - 1) / TILE
        val repeats = tiles - count - 1
        if (repeats < count * 2 || allRows.isEmpty() || allRows.size > 64) return fallback()
        val lengths = weightedLengths(repeats, columnWeights, 2)
        val padding = TEXT_START - frame.leftAdvance
        val widths = lengths.mapIndexed { index, length ->
            TILE * (length + 1) - if (index == 0) TEXT_START else frame.innerAdvance + padding
        }
        val measured = allRows.map { row -> row.mapIndexed { index, cell -> cellLines(cell, widths[index]) ?: return fallback() } }
        if (measured.sumOf { row -> row.maxOf { it.size } } > 192) return fallback()
        val borderWidth = tiles * TILE + 1
        val outerLeft = (width - 8 - borderWidth) / 2
        val outerRight = width - 8 - borderWidth - outerLeft
        fun outside(content: Component) = join(listOf(gap(outerLeft), content, gap(outerRight)))
        fun border(start: Int, line: Int, middle: Int, end: Int) = outside(glued(frame, buildList {
            add(start)
            lengths.forEachIndexed { index, length ->
                repeat(length) { add(line) }
                add(if (index == lengths.lastIndex) end else middle)
            }
        }).append(gap(if (end == 10) 10 - frame.rightJointAdvance else 0)))
        val output = mutableListOf(border(0, 1, 2, 3))
        measured.forEachIndexed { index, cells ->
            repeat(cells.maxOf { it.size }) { line ->
                output += outside(join(buildList {
                    cells.forEachIndexed { column, cell ->
                        add(glyph(frame.base + if (column == 0) 4 else 5))
                        add(gap(padding))
                        add(cell.getOrElse(line) { gap(widths[column]) })
                    }
                    add(glyph(frame.base + 6))
                    add(gap(10 - frame.rightAdvance))
                }))
            }
            if (heading != null && index == 0 && index < measured.lastIndex) output += border(7, 8, 9, 10)
        }
        output += border(11, 12, 13, 14)
        return PaperDialogBody(lines(output), width)
    }

    /**
     * Opt-in opaque v03 panel from arc:dialog_ui_preview, with 1..8 independent
     * weighted columns. [width] includes 8px native padding and 2px wrap headroom.
     * Every background strip is emitted before all native text and shadows;
     * row-bank ascents place strips vertically without interleaving opaque pixels.
     * At most 64 logical rows (including [headers]) and 192 wrapped lines are
     * framed. Unknown metrics or content beyond those bounds stays readable and
     * unframed, with every cell preserved. Existing tooltip frames are unaffected.
     */
    fun filledBody(
        rows: List<List<Component>>,
        headers: List<Component>?,
        width: Int,
        columnWeights: List<Int>,
    ): PaperDialogBody {
        require(width in 9..1024) { "Dialog panel width must be in 9..1024 GUI pixels" }
        require(columnWeights.size in 1..8 && columnWeights.all { it > 0 }) { "Dialog panels require 1..8 positive column weights" }
        val count = columnWeights.size
        require(rows.all { it.size == count } && (headers == null || headers.size == count)) { "Dialog panel cells must match column weights" }
        val heading = headers?.map { it.colorIfAbsent(headerColor).decorate(TextDecoration.BOLD) }
        val allRows = listOfNotNull(heading) + rows.map { row -> row.map { it.colorIfAbsent(filledTextColor) } }
        fun fallback() = PaperDialogBody(lines(allRows.map { row ->
            join(row.flatMapIndexed { index, cell -> if (index == 0) listOf(cell) else listOf(Component.text(" · ", filledTextColor), cell) })
        }).font(font), width)
        val panelWidth = width - 10
        val contentWidth = panelWidth - 32 - (count - 1) * 16
        if (panelWidth < 48 || contentWidth < count || allRows.isEmpty() || allRows.size > 64) return fallback()
        val widths = weightedLengths(contentWidth, columnWeights, 1)
        val measured = allRows.map { row -> row.mapIndexed { index, cell -> cellLines(cell, widths[index]) ?: return fallback() } }
        val height = measured.sumOf { row -> row.maxOf { it.size } }
        if (height > 192) return fallback()
        val dividers = widths.dropLast(1).runningFold(16) { x, cellWidth -> x + cellWidth + 16 }
            .drop(1).map { it - 16 }

        val background = panelBackground(
            listOf(PanelRect(0, 0, panelWidth, height + 2, dividers = dividers)),
            panelWidth, filledFont, 0xFA300, 16, 4,
        )
        val output = mutableListOf(background)
        measured.forEach { cells ->
            repeat(cells.maxOf { it.size }) { line ->
                output += join(buildList {
                    add(gap(16))
                    cells.forEachIndexed { column, cell ->
                        add(cell.getOrElse(line) { gap(widths[column]) })
                        add(gap(16))
                    }
                })
            }
        }
        output += gap(panelWidth)
        return PaperDialogBody(lines(output).font(font), width)
    }

    /** Additive themed counterpart; the original four-argument v03 entry point remains unchanged. */
    fun filledBody(
        rows: List<List<Component>>, headers: List<Component>?, width: Int,
        columnWeights: List<Int>, skin: PanelSkin,
    ): PaperDialogBody {
        val original = filledBody(rows, headers, width, columnWeights)
        val background = original.text.children().firstOrNull()
            ?.takeIf { it.font() == filledFont && it.shadowColor() == ShadowColor.none() } ?: return original
        fun remap(child: Component): Component {
            val nested = child.children(child.children().map(::remap))
            if (nested !is TextComponent) return nested
            val text = buildString {
                nested.content().codePoints().forEach { point ->
                    if (point in 0xFA300..0xFAF1B) {
                        val relative = point - 0xFA300
                        appendCodePoint(skin.base + relative / 16 * 32 + relative % 16 / 4 * 7 + relative % 4)
                    } else appendCodePoint(point)
                }
            }
            return nested.content(text)
        }
        return PaperDialogBody(original.text.children(listOf(remap(background).font(Key.key(skin.fontName))) +
            original.text.children().drop(1)), width)
    }

    /**
     * Compact measured facts, with at most 64 pairs and 1..8 columns. ROWS groups
     * consecutive facts in compact columns; METRICS puts the label above its value;
     * CAPSULES keeps each pair together. Labels and values have an 8px minimum gap,
     * independent wrapping and 12px side insets. Grid rows use the native 9px rhythm.
     * The complete composition must fit 194 native rows, including its borders.
     * Unknown metrics and excessive height preserve every fact as unframed text.
     */
    fun factsBody(facts: List<Pair<Component, Component>>, layout: FactsLayout, spec: PanelSpec): PaperDialogBody {
        fun fallback() = factsFallback(listOf(FactsGroup(Component.empty(), facts)), spec.maxWidth)
        if (facts.isEmpty() || facts.size > 64) return fallback()
        val colored = facts.map { (label, value) -> label.colorIfAbsent(filledTextColor) to value.colorIfAbsent(filledTextColor) }
        val groups = if (layout == FactsLayout.ROWS) {
            val count = minOf(spec.columns, colored.size)
            var offset = 0
            List(count) { column ->
                val size = colored.size / count + if (column < colored.size % count) 1 else 0
                colored.subList(offset, offset + size).also { offset += size }
            }
        } else colored.map { listOf(it) }
        val columns = minOf(spec.columns, groups.size)
        val desired = groups.map { rows ->
            val labels = rows.map { DialogTextLayout.naturalWidth(it.first) ?: return fallback() }
            val values = rows.map { DialogTextLayout.naturalWidth(it.second) ?: return fallback() }
            24 + if (layout == FactsLayout.METRICS) maxOf(labels.max(), values.max()) else labels.max() + 8 + values.max()
        }.max().coerceAtLeast(48)
        val available = (spec.maxWidth - 10 - (columns - 1) * 8) / columns
        if (available < 48) return fallback()
        val cardWidth = if (spec.fitContent) minOf(desired, available) else available
        val cards = groups.map { rows ->
            if (layout == FactsLayout.METRICS) metricCard(rows.single(), cardWidth) ?: return fallback()
            else pairCard(rows, cardWidth, layout == FactsLayout.CAPSULES) ?: return fallback()
        }
        return panelGrid(cards, cardWidth, columns, spec) ?: fallback()
    }

    /** Semantic groups use centred measured title tabs; group titles are never inferred or discarded. */
    fun groupedFactsBody(groups: List<FactsGroup>, spec: PanelSpec): PaperDialogBody {
        fun fallback() = factsFallback(groups, spec.maxWidth)
        if (groups.isEmpty() || groups.size > 64 || groups.sumOf { it.facts.size.toLong() } > 64) return fallback()
        val columns = minOf(spec.columns, groups.size)
        val desired = groups.map { group ->
            val title = DialogTextLayout.naturalWidth(group.title) ?: return fallback()
            val label = group.facts.maxOfOrNull { DialogTextLayout.naturalWidth(it.first) ?: return fallback() } ?: 0
            val value = group.facts.maxOfOrNull { DialogTextLayout.naturalWidth(it.second) ?: return fallback() } ?: 0
            maxOf(64, title + 40, label + value + 32)
        }.max()
        val available = (spec.maxWidth - 10 - (columns - 1) * 8) / columns
        if (available < 64) return fallback()
        val cardWidth = if (spec.fitContent) minOf(desired, available) else available
        val cards = groups.map { group ->
            val title = group.title.colorIfAbsent(filledTextColor)
            val tabWidth = (DialogTextLayout.naturalWidth(title)!! + 24).coerceIn(48, cardWidth - 16)
            val titleLines = cellLines(title, tabWidth - 24, TextAlignment.CENTER) ?: return fallback()
            val pairs = pairCard(group.facts.map { (label, value) ->
                label.colorIfAbsent(filledTextColor) to value.colorIfAbsent(filledTextColor)
            }, cardWidth, false) ?: return fallback()
            val tabHeight = titleLines.size + 2
            val tabX = (cardWidth - tabWidth) / 2
            PanelCard(
                height = pairs.height + tabHeight,
                panels = listOf(
                    PanelRect(0, 1, cardWidth, pairs.height + tabHeight - 1),
                    PanelRect(tabX, 0, tabWidth, tabHeight),
                ),
                text = titleLines.mapIndexed { row, text -> PlacedText(tabX + 12, row + 1, tabWidth - 24, text) } +
                    pairs.text.map { it.copy(y = it.y + tabHeight) },
            )
        }
        return panelGrid(cards, cardWidth, columns, spec) ?: fallback()
    }

    private data class PanelRect(
        val x: Int, val y: Int, val width: Int, val height: Int,
        val capsule: Boolean = false, val dividers: List<Int> = emptyList(),
    )
    private data class PlacedText(val x: Int, val y: Int, val width: Int, val text: Component)
    private data class PanelCard(val height: Int, val panels: List<PanelRect>, val text: List<PlacedText>)

    private fun pairCard(facts: List<Pair<Component, Component>>, width: Int, capsule: Boolean): PanelCard? {
        val label = facts.maxOfOrNull { DialogTextLayout.naturalWidth(it.first) ?: return null } ?: 0
        val value = facts.maxOfOrNull { DialogTextLayout.naturalWidth(it.second) ?: return null } ?: 0
        val space = width - 32
        val widths = if (label + value <= space) listOf(maxOf(1, label), maxOf(1, value)) else {
            // Long prose must not squeeze every short label into a few pixels.
            val left = minOf(maxOf(1, label), space - minOf(maxOf(1, value), space / 2))
            listOf(left, space - left)
        }
        val x = (width - widths.sum() - 8) / 2
        val content = mutableListOf<PlacedText>()
        var y = 1
        facts.forEach { (labelText, valueText) ->
            val labels = cellLines(labelText, widths[0]) ?: return null
            val values = cellLines(valueText, widths[1]) ?: return null
            labels.forEachIndexed { row, text -> content += PlacedText(x, y + row, widths[0], text) }
            values.forEachIndexed { row, text -> content += PlacedText(x + widths[0] + 8, y + row, widths[1], text) }
            y += maxOf(labels.size, values.size)
        }
        val height = maxOf(3, y + 1)
        return PanelCard(height, listOf(PanelRect(0, 0, width, height, capsule)), content)
    }

    private fun metricCard(fact: Pair<Component, Component>, width: Int): PanelCard? {
        val labels = cellLines(fact.first, width - 24, TextAlignment.CENTER) ?: return null
        val values = cellLines(fact.second, width - 24, TextAlignment.CENTER) ?: return null
        val height = labels.size + values.size + 3
        return PanelCard(height, listOf(PanelRect(0, 0, width, height)),
            labels.mapIndexed { row, text -> PlacedText(12, row + 1, width - 24, text) } +
                values.mapIndexed { row, text -> PlacedText(12, row + labels.size + 2, width - 24, text) })
    }

    private fun panelGrid(cards: List<PanelCard>, cardWidth: Int, columns: Int, spec: PanelSpec): PaperDialogBody? {
        val width = columns * cardWidth + (columns - 1) * 8
        val panels = mutableListOf<PanelRect>()
        val text = mutableListOf<PlacedText>()
        var y = 0
        cards.chunked(columns).forEachIndexed { row, group ->
            if (row > 0) y++
            val start = (width - (group.size * cardWidth + (group.size - 1) * 8)) / 2
            val height = group.maxOf { it.height }
            group.forEachIndexed { index, card ->
                val x = start + index * (cardWidth + 8)
                // Equal row heights keep paired outlines symmetric when a neighbouring value wraps.
                panels += card.panels.mapIndexed { panelIndex, panel -> panel.copy(
                    x = panel.x + x, y = panel.y + y,
                    height = if (panelIndex == 0) panel.height + height - card.height else panel.height,
                ) }
                text += card.text.map { it.copy(x = it.x + x, y = it.y + y) }
            }
            y += height
        }
        if (y > 194) return null
        val output = mutableListOf(panelBackground(panels, width, Key.key(spec.skin.fontName), spec.skin.base, 32, 7))
        for (row in 1 until y) {
            var cursor = 0
            output += join(buildList {
                text.filter { it.y == row }.sortedBy { it.x }.forEach { placed ->
                    add(gap(placed.x - cursor)); add(placed.text); cursor = placed.x + placed.width
                }
                add(gap(width - cursor))
            })
        }
        return PaperDialogBody(lines(output).font(font), width + 10)
    }

    private fun factsFallback(groups: List<FactsGroup>, width: Int) = PaperDialogBody(lines(groups.flatMap { group ->
        listOf(group.title.colorIfAbsent(filledTextColor)).filterNot { it == Component.empty().color(filledTextColor) } +
            group.facts.map { (label, value) -> join(listOf(label.colorIfAbsent(filledTextColor),
                Component.text(" · ", filledTextColor), value.colorIfAbsent(filledTextColor))) }
    }).font(font), width)

    /** One native baseline owns all opaque pixels, including every card, before any foreground. */
    private fun panelBackground(
        panels: List<PanelRect>, width: Int, panelFont: Key, bank: Int, stride: Int, rowKinds: Int,
    ): Component {
        val positive = PixelSpacing(panelFont, 0xFA220)
        val negative = PixelSpacing(panelFont, 0xFA200)
        val background = Component.text()
        var cursor = 0
        fun move(amount: Int) {
            background.append(if (amount >= 0) positive.padding(amount) else negative.padding(-amount))
            cursor += amount
        }
        fun tile(x: Int, point: Int) {
            move(x - cursor)
            background.append(Component.text(String(Character.toChars(point))).font(panelFont).decorations(undecorated))
            cursor = x + 17
        }
        panels.forEach { panel ->
            for (row in 0 until panel.height) {
                val base = bank + (panel.y + row) * stride +
                    when (row) { 0 -> 0; panel.height - 1 -> rowKinds * 2; else -> rowKinds } +
                    if (panel.capsule) 4 else 0
                tile(panel.x, base)
                val right = panel.x + panel.width - 16
                for (x in panel.x + 16 until right step 16) tile(minOf(x, right - 16), base + 1)
                tile(right, base + 2)
                panel.dividers.forEach { tile(panel.x + it, base + 3) }
            }
        }
        move(width - cursor)
        val painted = background.build().font(panelFont)
            .color(NamedTextColor.WHITE).decorations(undecorated).shadowColor(ShadowColor.none()).compact() as TextComponent
        // NBT StringTag uses writeUTF: each SMP glyph costs 6 bytes, with a 65535-byte limit per string.
        // Keep chunks below that boundary and never compact them back into one string.
        val content = painted.content()
        val chunks = mutableListOf<Component>()
        var start = 0
        while (start < content.length) {
            val end = content.offsetByCodePoints(start, minOf(8192, content.codePointCount(start, content.length)))
            chunks += Component.text(content.substring(start, end))
            start = end
        }
        return if (chunks.size <= 1) painted else painted.content("").children(chunks)
    }

    /**
     * One text block inside a single pack frame. [width] includes the native
     * dialog widget's 4px padding on both sides, like [body]. Text is wrapped
     * with the loaded pack metrics and retains its Adventure styles. Components
     * the metric adapter cannot measure, or widths too narrow for a frame, are
     * returned unchanged as a readable unframed body.
     */
    fun framedBody(
        content: Component,
        frame: Frame = Frame.EPIC,
        width: Int = 320,
    ): PaperDialogBody {
        require(width in 9..1024) { "Dialog frame width must be in 9..1024 GUI pixels" }
        val innerWidth = width - 8
        val tileCount = (innerWidth - MIN_OUTER_INSET * 2 - 1) / TILE
        val borderWidth = tileCount * TILE + 1
        val textWidth = borderWidth - TEXT_START * 2
        if (tileCount < 3 || textWidth < 1) return PaperDialogBody(content, width)
        val contentLines = cellLines(content, textWidth) ?: return PaperDialogBody(content, width)
        if (contentLines.size > 192) return PaperDialogBody(content, width)

        val outsideWidth = innerWidth - borderWidth
        val outerLeft = outsideWidth / 2
        val outerRight = outsideWidth - outerLeft
        fun outside(part: Component) = join(listOf(gap(outerLeft), part, gap(outerRight)))
        fun border(start: Int, horizontal: Int, end: Int) = outside(
            glued(frame, listOf(start) + List(tileCount - 2) { horizontal } + end)
        )
        fun contentLine(line: Component) = outside(join(listOf(
            glyph(frame.base + 4), gap(TEXT_START - frame.leftAdvance), line,
            glyph(frame.base + 6), gap(TEXT_START - frame.rightAdvance),
        )))

        val renderedLines = buildList {
            add(border(0, 1, 3))
            contentLines.forEach { add(contentLine(it)) }
            add(border(11, 12, 14))
        }
        return PaperDialogBody(lines(renderedLines), width)
    }

    /** Width includes the native widget's 4px padding on both sides. */
    fun render(
        rows: List<Pair<Component, Component>>,
        headers: Pair<Component, Component>? = null,
        frame: Frame = Frame.EPIC,
        width: Int = 400,
        columns: Columns = Columns.AUTO,
    ): Result = render(rows, headers, frame, width, columns, Spec())

    fun render(
        rows: List<Pair<Component, Component>>,
        headers: Pair<Component, Component>? = null,
        frame: Frame = Frame.EPIC,
        width: Int = 400,
        columns: Columns = Columns.AUTO,
        spec: Spec,
    ): Result {
        require(width in 9..1024) { "Dialog table width must be in 9..1024 GUI pixels" }
        val values = rows.map { (label, value) ->
            label.colorIfAbsent(labelColor) to value.colorIfAbsent(valueColor)
        }
        val heading = headers?.let { (label, value) ->
            label.colorIfAbsent(headerColor).decorate(TextDecoration.BOLD) to
                value.colorIfAbsent(headerColor).decorate(TextDecoration.BOLD)
        }
        val allRows = listOfNotNull(heading) + values
        fun fallback() = Result.Unframed(lines(allRows.map { (label, value) ->
            join(listOf(label, Component.text(": ", labelColor), value))
        }))
        // Whole tiles only: no scaled textures, fractional spacing or clipped corners.
        val repeats = (width - 8 - MIN_OUTER_INSET * 2 - 1) / TILE - 3
        if (repeats < 8 || allRows.isEmpty() || allRows.size > 64) return fallback()
        val padding = TEXT_START - frame.leftAdvance
        fun widths(left: Int) = (TILE * (left + 1) - TEXT_START) to
            (TILE * (repeats - left + 1) - frame.innerAdvance - padding)

        data class Measured(val left: Int, val cells: List<Pair<List<Component>, List<Component>>>) {
            val height = cells.sumOf { (a, b) -> maxOf(a.size, b.size) }
        }
        fun measure(left: Int): Measured? {
            val (leftWidth, rightWidth) = widths(left)
            val measured = allRows.map { (a, b) ->
                val first = cellLines(a, leftWidth) ?: return null
                val second = cellLines(b, rightWidth) ?: return null
                first to second
            }
            return Measured(left, measured)
        }
        val chosen = when (columns) {
            Columns.AUTO -> (4..repeats - 4).asSequence().mapNotNull(::measure)
                .minWithOrNull(compareBy<Measured> { it.height }.thenBy { abs(it.left - repeats * 0.45) })
            Columns.BALANCED -> measure(repeats / 2)
            Columns.LABEL_WIDE -> measure((repeats * 0.75).toInt().coerceIn(4, repeats - 4))
            Columns.VALUE_WIDE -> measure((repeats / 3).coerceIn(4, repeats - 4))
        } ?: return fallback()
        if (chosen.height > 192) return fallback()
        val left = chosen.left
        val right = repeats - left
        val (leftWidth, rightWidth) = widths(left)
        val borderWidth = TILE * (repeats + 3) + 1
        val outerLeft = (width - 8 - borderWidth) / 2
        val outerRight = width - 8 - borderWidth - outerLeft
        fun outside(content: Component) = join(listOf(gap(outerLeft), content, gap(outerRight)))
        fun border(start: Int, line: Int, middle: Int, end: Int) = outside(
            glued(frame, listOf(start) + List(left) { line } + middle + List(right) { line } + end)
                .append(gap(if (end == 10) 10 - frame.rightJointAdvance else 0))
        )
        fun content(a: Component, b: Component) = outside(join(listOf(
            glyph(frame.base + 4), gap(padding), a,
            glyph(frame.base + 5), gap(padding), b,
            glyph(frame.base + 6), gap(10 - frame.rightAdvance),
        )))
        val output = mutableListOf(border(0, 1, 2, 3))
        chosen.cells.forEachIndexed { index, (a, b) ->
            repeat(maxOf(a.size, b.size)) { row ->
                output += content(a.getOrElse(row) { gap(leftWidth) }, b.getOrElse(row) { gap(rightWidth) })
            }
            if (index < chosen.cells.lastIndex && (spec.rowSeparators || (heading != null && index == 0))) {
                output += border(7, 8, 9, 10)
            }
        }
        output += border(11, 12, 13, 14)
        return Result.Framed(lines(output), leftWidth to rightWidth)
    }

    private fun weightedLengths(total: Int, weights: List<Int>, minimum: Int): List<Int> {
        val totalWeight = weights.sumOf { it.toLong() }
        val available = total - weights.size * minimum
        var previous = 0
        var weight = 0L
        return weights.map { part ->
            weight += part
            val cumulative = (available * weight / totalWeight).toInt()
            (minimum + cumulative - previous).also { previous = cumulative }
        }
    }

    private fun cellLines(text: Component, width: Int, alignment: TextAlignment = TextAlignment.LEFT): List<Component>? {
        val result = DialogTextLayout.layout(text, alignment, width + 8) as? TextLayoutResult.Aligned
            ?: return null
        val rows = mutableListOf(Component.text())
        fun split(node: Component, inherited: Style) {
            val style = node.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
            (node as TextComponent).content().split('\n').forEachIndexed { index, part ->
                if (index > 0) rows += Component.text()
                if (part.isNotEmpty()) rows.last().append(Component.text(part).style(style))
            }
            node.children().forEach { split(it, style) }
        }
        split(result.component, Style.empty())
        return rows.map { it.build() }
    }

    private fun glyph(point: Int) = Component.text(String(Character.toChars(point)))
        .font(font).color(NamedTextColor.WHITE).decorations(undecorated)
    private fun gap(width: Int) = DialogTextLayout.spacing.padding(width)
    private fun join(parts: List<Component>): Component = Component.empty().children(parts)
        .decoration(TextDecoration.ITALIC, false)
    private fun lines(parts: List<Component>) = join(parts.flatMapIndexed { index, part ->
        if (index == 0) listOf(part) else listOf(Component.newline(), part)
    })
    private fun glued(frame: Frame, offsets: List<Int>) = join(offsets.flatMapIndexed { index, offset ->
        val part = glyph(frame.base + offset)
        if (index == 0) listOf(part) else listOf(glyph(0xF0F11), part)
    })
}
