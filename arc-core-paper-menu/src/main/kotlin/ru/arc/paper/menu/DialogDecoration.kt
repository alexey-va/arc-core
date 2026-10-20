package ru.arc.paper.menu

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import ru.arc.text.PixelSpacing
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult

/**
 * Opt-in composition of pack bitmap pieces around native dialog text. The caller
 * supplies its font, measured advances and positive/negative binary space glyphs;
 * this helper neither installs a pack nor changes any existing dialog renderer.
 * Pieces have a one-pixel trailing advance and their fill is safe to overlap.
 */
class DialogDecoration(font: Key, positiveSpace: Int, negativeSpace: Int) {
    data class Glyph(val codePoint: Int, val advance: Int) {
        init {
            require(Character.isValidCodePoint(codePoint) && codePoint !in 0xD800..0xDFFF)
            require(advance in 2..256)
        }
    }

    data class ButtonFrame(val left: Glyph, val fill: Glyph, val right: Glyph)
    data class Corners(val topLeft: Glyph, val topRight: Glyph, val bottomLeft: Glyph, val bottomRight: Glyph)
    data class Divider(val left: Glyph, val fill: Glyph, val right: Glyph)

    private val font = font
    private val positive = PixelSpacing(font, positiveSpace)
    private val negative = PixelSpacing(font, negativeSpace)
    private val undecorated = TextDecoration.values().associateWith { TextDecoration.State.FALSE }

    /** Keeps the label centered on the whole widget, independently of a 16px-inset icon. */
    fun buttonLabel(label: Component, width: Int, frame: ButtonFrame, icon: Glyph? = null, iconOnRight: Boolean = false): Component {
        require(width in 64..1024)
        val advance = width - 4
        if (advance > 1016) return label
        val reserved = if (icon == null) null else singleLine(label, width - 64)
        val text = reserved ?: singleLine(label, advance) ?: return label
        val shownIcon = icon.takeIf { reserved != null }
        val output = Row()
        output.at(-1, glyph(frame.left), frame.left.advance)
        val right = advance - (frame.right.advance - 1)
        fill(output, frame.fill, frame.left.advance - 2, right)
        output.at(right, glyph(frame.right), frame.right.advance)
        output.at(if (reserved != null) 30 else 0, text, if (reserved != null) width - 64 else advance)
        if (shownIcon != null) {
            val x = if (iconOnRight) width - 18 - (shownIcon.advance - 1) else 14
            output.at(x, glyph(shownIcon), shownIcon.advance)
        }
        return output.finish(advance)
    }

    /** Open corners surround a short, wrapped native text block; unsupported text remains readable. */
    fun openCorners(text: Component, width: Int, corners: Corners): PaperDialogBody {
        require(width in 64..1024)
        val inner = width - 8
        val content = laidOutLines(text, inner - 32) ?: return PaperDialogBody(text, width)
        fun edge(left: Glyph, right: Glyph): Component = Row().apply {
            at(0, glyph(left), left.advance)
            at(inner - right.advance, glyph(right), right.advance)
        }.finish(inner)
        val rows = listOf(edge(corners.topLeft, corners.topRight)) + content.map {
            Component.empty().append(positive.padding(16)).append(it).append(positive.padding(16))
        } + edge(corners.bottomLeft, corners.bottomRight)
        return PaperDialogBody(lines(rows), width)
    }

    /** A single thin rule with explicit mirrored terminal pieces, not another box. */
    fun divider(width: Int, pieces: Divider): Component {
        require(width in 64..1024)
        val inner = width - 8
        return Row().apply {
            at(0, glyph(pieces.left), pieces.left.advance)
            val right = inner - pieces.right.advance
            fill(this, pieces.fill, pieces.left.advance - 1, right)
            at(right, glyph(pieces.right), pieces.right.advance)
        }.finish(inner)
    }

    private fun fill(row: Row, piece: Glyph, start: Int, end: Int) {
        val step = piece.advance - 1
        require(end - start >= step) { "Bitmap caps leave no room for their repeat piece" }
        var x = start
        while (x + step <= end) {
            row.at(x, glyph(piece), piece.advance)
            x += step
        }
        if (x < end) row.at(end - step, glyph(piece), piece.advance)
    }

    private fun singleLine(text: Component, width: Int): Component? {
        if (width < 1) return null
        val result = DialogTextLayout.layout(text, TextAlignment.CENTER, width + 8) as? TextLayoutResult.Aligned
        return result?.takeIf { it.lineCount == 1 }?.component
    }

    private fun laidOutLines(text: Component, width: Int): List<Component>? {
        val result = DialogTextLayout.layout(text, TextAlignment.LEFT, width + 8) as? TextLayoutResult.Aligned ?: return null
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

    private fun glyph(value: Glyph) = Component.text(String(Character.toChars(value.codePoint)))
        .font(font).color(NamedTextColor.WHITE).decorations(undecorated)

    private fun lines(rows: List<Component>) = Component.empty().children(rows.flatMapIndexed { index, row ->
        if (index == 0) listOf(row) else listOf(Component.newline(), row)
    })

    private inner class Row {
        private val output = Component.text()
        private var cursor = 0
        fun at(x: Int, content: Component, advance: Int) {
            move(x - cursor)
            output.append(content)
            cursor = x + advance
        }
        private fun move(amount: Int) {
            output.append(if (amount >= 0) positive.padding(amount) else negative.padding(-amount))
            cursor += amount
        }
        fun finish(width: Int): Component {
            move(width - cursor)
            return output.build()
        }
    }
}
