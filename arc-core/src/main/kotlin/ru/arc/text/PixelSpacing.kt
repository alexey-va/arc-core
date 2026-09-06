package ru.arc.text

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration

/**
 * Immutable pixel padding for any Adventure text surface. The selected font must
 * contain ten consecutive Unicode glyphs with advances 1,2,4,..512. No assets or
 * plugin API are installed/required by this class; consumers select their pack.
 *
 * RusCrafting's existing ItemsAdder pack uses minecraft:default, U+F0F01..U+F0F0A.
 * Returned components disable all decorations and contain no events. Append them
 * beside styled/interactive text under a neutral parent, not inside an event span.
 */
class PixelSpacing(private val font: Key, private val firstCodePoint: Int) {
    init {
        require(firstCodePoint in 0..(Character.MAX_CODE_POINT - 9) &&
            (firstCodePoint..firstCodePoint + 9).none { it in 0xD800..0xDFFF }) {
            "Padding requires ten consecutive Unicode scalar values"
        }
    }

    /** Exact advance in GUI pixels; large offsets must be laid out by the caller. */
    fun padding(width: Int): Component {
        require(width in 0..1023) { "Padding width must be in 0..1023 GUI pixels" }
        if (width == 0) return Component.empty()
        val text = buildString {
            for (bit in 9 downTo 0) if ((width and (1 shl bit)) != 0) appendCodePoint(firstCodePoint + bit)
        }
        return Component.text(text).font(font)
            .decorations(TextDecoration.values().associateWith { TextDecoration.State.FALSE })
    }
}
