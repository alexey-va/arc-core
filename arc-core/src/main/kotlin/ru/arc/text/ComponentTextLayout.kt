package ru.arc.text

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration

enum class TextAlignment { LEFT, CENTER, RIGHT }

/** Exact GUI-pixel advances for the font loaded by the client; null means unmeasured. */
fun interface GlyphWidths {
    fun width(font: Key, codePoint: Int, bold: Boolean): Int?
}

sealed interface TextLayoutResult {
    data class Aligned(val component: Component, val lineCount: Int, val width: Int) : TextLayoutResult
    data class Unsupported(val reason: Reason) : TextLayoutResult
    enum class Reason { COMPONENT, GLYPH, DIRECTION, LIMIT, GLYPH_TOO_WIDE }
}

/**
 * Pure, thread-safe when [widths] is immutable. Wraps resolved text and pads every
 * line to [layout]'s content width, so even a centered renderer has stable edges.
 * Preserves effective styles/events on text; spacers never inherit decorations or
 * interaction. No player state, IO or client-font guessing. Unsupported content
 * produces a typed failure, never a partially rendered result.
 *
 * [spacing] selects existing pack glyphs; the engine creates no font resources.
 * Consumers subtract widget padding from their width and handle unsupported text
 * explicitly (for example, show its original centered component).
 */
class ComponentTextLayout(
    private val widths: GlyphWidths,
    private val spacing: PixelSpacing,
) {
    /** Binary-compatible 2.7.0 entry point; new consumers should select [PixelSpacing] explicitly. */
    constructor(widths: GlyphWidths, spacerFont: Key) : this(widths, PixelSpacing(spacerFont, 0xE000))

    private data class Glyph(val point: Int, val style: Style, val width: Int)

    fun layout(text: Component, width: Int, alignment: TextAlignment): TextLayoutResult {
        require(width in 1..1023) { "Content width must be in 1..1023 GUI pixels" }
        val glyphs = ArrayList<Glyph>()
        var nodes = 0
        var failure: TextLayoutResult.Reason? = null
        fun collect(component: Component, inherited: Style, depth: Int) {
            if (failure != null) return
            if (++nodes > 4096 || depth > 64) { failure = TextLayoutResult.Reason.LIMIT; return }
            if (component !is TextComponent) { failure = TextLayoutResult.Reason.COMPONENT; return }
            val style = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
            val font = style.font() ?: Key.key("minecraft:default")
            val bold = style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE
            val points = component.content().codePoints().iterator()
            while (points.hasNext()) {
                val point = points.nextInt()
                if (glyphs.size >= 8192) { failure = TextLayoutResult.Reason.LIMIT; return }
                if (point == '\n'.code) { glyphs += Glyph(point, style, 0); continue }
                if (Character.getDirectionality(point) in setOf(
                        Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                        Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
                        Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
                        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ISOLATE)) {
                    failure = TextLayoutResult.Reason.DIRECTION; return
                }
                val advance = widths.width(font, point, bold)
                if (advance == null || advance < 0) { failure = TextLayoutResult.Reason.GLYPH; return }
                if (advance > width) { failure = TextLayoutResult.Reason.GLYPH_TOO_WIDE; return }
                glyphs += Glyph(point, style, advance)
            }
            component.children().forEach { collect(it, style, depth + 1) }
        }
        collect(text, Style.empty(), 0)
        failure?.let { return TextLayoutResult.Unsupported(it) }

        val lines = ArrayList<List<Glyph>>()
        var line = ArrayList<Glyph>()
        var used = 0
        for (glyph in glyphs) {
            if (glyph.point == '\n'.code) {
                lines += line; line = ArrayList(); used = 0
            } else {
                if (used + glyph.width > width) {
                    if (glyph.point == ' '.code) {
                        lines += line.dropLastWhile { it.point == ' '.code }
                        line = ArrayList(); used = 0
                        if (lines.size >= 256) return TextLayoutResult.Unsupported(TextLayoutResult.Reason.LIMIT)
                        continue
                    }
                    val split = line.indexOfLast { it.point == ' '.code }
                    if (split >= 0) {
                        lines += line.take(split).dropLastWhile { it.point == ' '.code }
                        line = ArrayList(line.drop(split + 1))
                        used = line.sumOf { it.width }
                    } else {
                        lines += line; line = ArrayList(); used = 0
                    }
                    if (glyph.point == ' '.code && line.isEmpty()) continue
                }
                // A word after an earlier break can still exceed the remaining width.
                if (used + glyph.width > width) {
                    lines += line; line = ArrayList(); used = 0
                }
                line += glyph; used += glyph.width
            }
            if (lines.size >= 256) return TextLayoutResult.Unsupported(TextLayoutResult.Reason.LIMIT)
        }
        lines += line
        val output = Component.text()
        lines.forEachIndexed { index, content ->
            if (index > 0) output.append(Component.newline())
            val remainder = width - content.sumOf { it.width }
            val left = when (alignment) {
                TextAlignment.LEFT -> 0
                TextAlignment.CENTER -> remainder / 2
                TextAlignment.RIGHT -> remainder
            }
            output.append(spacing.padding(left))
            var runStyle: Style? = null
            val run = StringBuilder()
            fun flush() {
                if (run.isNotEmpty()) output.append(Component.text(run.toString()).style(runStyle!!))
                run.setLength(0)
            }
            content.forEach { glyph ->
                if (runStyle != glyph.style) { flush(); runStyle = glyph.style }
                run.appendCodePoint(glyph.point)
            }
            flush()
            output.append(spacing.padding(remainder - left))
        }
        return TextLayoutResult.Aligned(output.build(), lines.size, width)
    }
}
