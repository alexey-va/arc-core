package ru.arc.paper.menu

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult
import kotlin.math.abs

/**
 * RusCrafting pack adapter for two-column native dialog tables (gallery T13–T18).
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

    private fun cellLines(text: Component, width: Int): List<Component>? {
        val result = DialogTextLayout.layout(text, TextAlignment.LEFT, width + 8) as? TextLayoutResult.Aligned
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
