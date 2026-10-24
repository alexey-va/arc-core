package ru.arc.paper.menu

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
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
    enum class Marker { BULLET, PENDING, COMPLETE }
    data class ListEntry(val marker: Marker, val text: Component)
    enum class StepState { COMPLETE, CURRENT, PENDING }
    data class Step(val state: StepState, val text: Component)
    enum class ProgressColor(internal val base: Int) { MINT(0xF9138), BLUE(0xF913C), VIOLET(0xF9140), AMBER(0xF9144) }
    enum class HeadingStyle { PLAIN, DIAMOND }
    enum class StateTone(internal val color: TextColor) {
        NEUTRAL(TextColor.color(0xE8DFD2)), INFO(TextColor.color(0x86DCF1)),
        SUCCESS(TextColor.color(0xA5E3B4)), ATTENTION(TextColor.color(0xF4D87A)),
    }
    enum class FieldIcon(internal val point: Int, internal val fontName: String) {
        PLAYER(0x10A000, "arc:dialog_field_icons"), RANK(0x10A001, "arc:dialog_field_icons"),
        HOMES(0x10A002, "arc:dialog_field_icons"), LANDS(0x10A003, "arc:dialog_field_icons"),
        WORLD(0x10A004, "arc:dialog_field_icons"), COORDINATES(0x10A005, "arc:dialog_field_icons"),
        CHAT(0x10A006, "arc:dialog_field_icons"), ONLINE(0x10A007, "arc:dialog_field_icons"),
        BALANCE(0x10A008, "arc:dialog_field_icons"), TOKENS(0x10A009, "arc:dialog_field_icons"),
    }

    companion object {
        /** Explicit opt-in to the published library; callers retain their unavailable-pack fallback. */
        @JvmStatic fun library(): DialogDecoration = DialogDecoration(Key.key("arc:dialog_ui"), 0xF9F00, 0xF9F10)

        /** The bitmap stays white in a 12px slot; the caller's native label and meaning are preserved. */
        @JvmStatic fun fieldLabel(icon: FieldIcon, label: Component): Component {
            val glyph = Component.text(String(Character.toChars(icon.point))).font(Key.key(icon.fontName))
                .color(NamedTextColor.WHITE)
                .decorations(TextDecoration.values().associateWith { TextDecoration.State.FALSE })
            val advance = DialogTextLayout.naturalWidth(glyph) ?: return label
            if (advance > 12) return label
            return Component.empty().append(glyph).append(DialogTextLayout.spacing.padding(12 - advance)).append(label)
        }

        /** A state is explicit text, never a color-only signal or inferred status. */
        @JvmStatic fun stateBadge(label: Component, tone: StateTone): Component = Component.empty()
            .append(Component.text("[ ", tone.color)).append(label.colorIfAbsent(tone.color))
            .append(Component.text(" ]", tone.color))
    }
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
    private val prose = TextColor.color(0xE8DFD2)

    /** Caller-owned states, hanging indents and exactly one empty line between complete items. */
    fun listBody(entries: List<ListEntry>, width: Int): PaperDialogBody {
        require(width in 9..1024)
        fun plain(entry: ListEntry) = Component.text(when (entry.marker) {
            Marker.BULLET -> "• "; Marker.PENDING -> "○ "; Marker.COMPLETE -> "✓ "
        }, prose).append(entry.text.colorIfAbsent(prose))
        fun fallback() = PaperDialogBody(lines(entries.flatMapIndexed { index, entry ->
            if (index == 0) listOf(plain(entry)) else listOf(Component.empty(), plain(entry))
        }), width)
        if (entries.size > 64 || width < 32) return fallback()
        val rows = mutableListOf<Component>()
        entries.forEachIndexed { entryIndex, entry ->
            val text = laidOutLines(entry.text.colorIfAbsent(prose), width - 24) ?: return fallback()
            if (entryIndex > 0) rows += DialogTextLayout.spacing.padding(width - 8)
            text.forEachIndexed { index, line ->
                val marker = when {
                    index > 0 -> DialogTextLayout.spacing.padding(16)
                    entry.marker == Marker.BULLET -> {
                        val dot = Component.text("•", prose)
                        dot.append(DialogTextLayout.spacing.padding(16 - (DialogTextLayout.naturalWidth(dot) ?: return fallback())))
                    }
                    else -> libraryGlyph(if (entry.marker == Marker.COMPLETE) 0xF910C else 0xF910D)
                        .append(DialogTextLayout.spacing.padding(6))
                }
                rows += Component.empty().append(marker).append(line)
            }
        }
        return if (rows.size > 192) fallback() else PaperDialogBody(lines(rows), width)
    }

    /** Ordered, caller-owned states; real connector glyphs continue through wrapped intermediate steps. */
    fun stepsBody(steps: List<Step>, width: Int): PaperDialogBody {
        require(width in 9..1024)
        fun fallback() = PaperDialogBody(lines(steps.map { step -> Component.text(when (step.state) {
            StepState.COMPLETE -> "✓ "; StepState.CURRENT -> "▶ "; StepState.PENDING -> "○ "
        }, prose).append(step.text.colorIfAbsent(prose)) }), width)
        if (steps.size > 32 || width < 40) return fallback()
        val output = mutableListOf<Component>()
        // Node stems and rail ink both lie at cell x=4; their origins must match.
        fun rail() = libraryGlyph(0xF910B).append(DialogTextLayout.spacing.padding(14))
        steps.forEachIndexed { index, step ->
            val text = laidOutLines(step.text.colorIfAbsent(prose), width - 28) ?: return fallback()
            text.forEachIndexed { row, line ->
                val marker = if (row == 0) libraryGlyph(when (step.state) {
                    StepState.COMPLETE -> 0xF9108; StepState.CURRENT -> 0xF9109; StepState.PENDING -> 0xF910A
                }).append(DialogTextLayout.spacing.padding(7))
                else if (index < steps.lastIndex) rail() else DialogTextLayout.spacing.padding(20)
                output += Component.empty().append(marker).append(line)
            }
            if (index < steps.lastIndex) output += rail().append(DialogTextLayout.spacing.padding(width - 28))
        }
        return if (output.size > 192) fallback() else PaperDialogBody(lines(output), width)
    }

    /** Label/value share the tight bar's left/right edges. Unknown progress retains supplied text only. */
    fun progressBody(
        label: Component, current: Double?, total: Double?, value: Component,
        width: Int, segments: Int, color: ProgressColor,
    ): PaperDialogBody {
        require(width in 9..1024 && segments in 1..32)
        require(current == null || current.isFinite() && current >= 0)
        require(total == null || total.isFinite() && total > 0)
        val caption = Component.empty().append(label.colorIfAbsent(prose)).append(Component.text(" · ", prose))
            .append(value.colorIfAbsent(prose))
        fun fallback() = PaperDialogBody(caption, width)
        if (current == null || total == null || width < 32) return fallback()
        val count = minOf(segments, (width - 8) / 18)
        if (count < 1) return fallback()
        val barWidth = count * 18
        val start = (width - 8 - barWidth) / 2
        val valueText = value.colorIfAbsent(prose)
        val valueWidth = DialogTextLayout.naturalWidth(valueText) ?: return fallback()
        val labelWidth = barWidth - valueWidth - 8
        if (labelWidth < 1 || laidOutLines(valueText, maxOf(1, valueWidth))?.size != 1) return fallback()
        val labelLines = laidOutLines(label.colorIfAbsent(prose), labelWidth) ?: return fallback()
        if (labelLines.size >= 192) return fallback()
        val text = labelLines.mapIndexed { index, line -> Row().apply {
            at(start, line, labelWidth)
            if (index == 0) at(start + barWidth - valueWidth, valueText, valueWidth)
        }.finish(width - 8) }
        val filled = ((current / total).coerceIn(0.0, 1.0) * count).toInt()
        val bar = Row().apply {
            repeat(count) { index ->
                val point = color.base + if (index < filled) 0 else 2
                val x = start + index * 18
                at(x, libraryGlyph(point), 10)
                at(x + 9, libraryGlyph(point + 1), 9)
            }
        }.finish(width - 8)
        return PaperDialogBody(lines(text + bar), width)
    }

    /** Measured title between mirrored complete caps, with 12px outer inset and text gaps. */
    fun headingBody(title: Component, width: Int): PaperDialogBody = headingBody(title, width, HeadingStyle.PLAIN)

    /** DIAMOND reuses the native gold line/diamond vocabulary; the two-argument default stays plain. */
    fun headingBody(title: Component, width: Int, style: HeadingStyle): PaperDialogBody {
        require(width in 9..1024)
        val label = title.colorIfAbsent(prose)
        fun fallback() = PaperDialogBody(label, width)
        val titleWidth = DialogTextLayout.naturalWidth(label) ?: return fallback()
        val remaining = width - 8 - 48 - titleWidth
        if (remaining < (if (style == HeadingStyle.DIAMOND) 64 else 48) ||
            laidOutLines(label, maxOf(1, titleWidth))?.size != 1) return fallback()
        val leftWidth = remaining / 2
        val rightWidth = remaining - leftWidth
        val row = Row()
        fun arm(x: Int, size: Int) {
            val right = x + size - 12
            row.at(x, libraryGlyph(0xF9104), 13)
            var position = x + 12
            while (position + 12 <= right) {
                row.at(position, libraryGlyph(0xF9106), 13)
                position += 12
            }
            // A native one-pixel unit fills the exact remainder without clipping either cap.
            while (position < right) {
                row.at(position, libraryGlyph(0xF9107), 2)
                position++
            }
            row.at(right, libraryGlyph(0xF9105), 13)
        }
        fun diamondArm(x: Int, size: Int, mirrored: Boolean) {
            fun ornament(text: String) = Component.text(text, TextColor.color(0xD5B58C))
                .font(Key.key("minecraft:default")).decorations(undecorated).shadowColor(ShadowColor.none())
            val line = ornament("─")
            val diamond = ornament("◆")
            val lineAdvance = requireNotNull(DialogTextLayout.naturalWidth(line))
            val diamondAdvance = requireNotNull(DialogTextLayout.naturalWidth(diamond))
            // Both pinned native glyphs have one trailing advance pixel beyond their visible ink.
            val lineInk = lineAdvance - 1
            val diamondInk = diamondAdvance - 1
            val middle = x + (size - diamondInk + if (mirrored) 1 else 0) / 2
            fun rule(start: Int, end: Int) {
                var position = start
                while (position + lineInk <= end) {
                    row.at(position, line, lineAdvance)
                    position += lineInk
                }
                if (position < end) row.at(end - lineInk, line, lineAdvance)
            }
            rule(x, middle - 3)
            row.at(middle, diamond, diamondAdvance)
            rule(middle + diamondInk + 3, x + size)
        }
        if (style == HeadingStyle.DIAMOND) diamondArm(12, leftWidth, false) else arm(12, leftWidth)
        row.at(24 + leftWidth, label, titleWidth)
        if (style == HeadingStyle.DIAMOND) diamondArm(36 + leftWidth + titleWidth, rightWidth, true)
        else arm(36 + leftWidth + titleWidth, rightWidth)
        return PaperDialogBody(row.finish(width - 8), width)
    }

    private fun libraryGlyph(point: Int) = glyph(Glyph(point, if (point == 0xF910B) 6 else 13))
        .shadowColor(ShadowColor.none())

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
