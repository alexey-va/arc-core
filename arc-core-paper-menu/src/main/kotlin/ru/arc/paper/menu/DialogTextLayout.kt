package ru.arc.paper.menu

import com.google.gson.JsonParser
import io.papermc.paper.registry.data.dialog.body.DialogBody
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import ru.arc.text.ComponentTextLayout
import ru.arc.text.GlyphWidths
import ru.arc.text.PixelSpacing
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult

/** Server-pack font snapshot adapter; the reusable layout algorithm lives in arc-core. */
object DialogTextLayout {
    val spacing = PixelSpacing(Key.key("minecraft:default"), 0xF0F01)
    private val widths by lazy {
        val fonts = requireNotNull(javaClass.getResourceAsStream("/fonts/dialog-font-metrics.json"))
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("fonts") }
        val tables = fonts.entrySet().associate { (font, value) ->
            font to listOf("normal", "bold").map { weight ->
                value.asJsonObject.getAsJsonObject(weight).entrySet().mapNotNull { (point, advance) ->
                    val number = advance.asDouble
                    if (!number.isFinite() || number < 0 || number != number.toInt().toDouble()) null
                    else point.removePrefix("U+").toInt(16) to number.toInt()
                }.toMap()
            }
        }
        GlyphWidths { font, point, bold ->
            tables[font.asString()]?.get(if (bold) 1 else 0)?.get(point)
        }
    }
    private val engine by lazy { ComponentTextLayout(widths, spacing) }

    fun glyphWidth(character: Char): Int = requireNotNull(
        widths.width(Key.key("minecraft:default"), character.code, false)
    ) { "Unmeasured gallery glyph: $character" }

    /** Width includes Minecraft 1.21.11's 4px padding on each side. */
    fun layout(text: Component, alignment: TextAlignment, width: Int = 400): TextLayoutResult {
        require(width in 9..1024) { "Aligned dialog body needs 8px padding and 1..1016px content" }
        return engine.layout(text, width - 8, alignment)
    }

    /** Preserve readable native text when the component cannot be measured. */
    fun body(text: Component, alignment: TextAlignment, width: Int = 400): DialogBody {
        val result = layout(text, alignment, width)
        return DialogBody.plainMessage((result as? TextLayoutResult.Aligned)?.component ?: text, width)
    }

    /** ARC Core dialog-model counterpart used by sibling plugins. */
    fun modelBody(text: Component, alignment: TextAlignment = TextAlignment.LEFT, width: Int = 400): PaperDialogBody {
        val result = layout(text, alignment, width)
        return PaperDialogBody((result as? TextLayoutResult.Aligned)?.component ?: text, width)
    }
}
