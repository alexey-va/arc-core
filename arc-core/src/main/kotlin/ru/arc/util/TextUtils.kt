package ru.arc.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.regex.Pattern

/** Adventure / MiniMessage helpers without platform dependencies. */
object TextUtils {
    private val miniMessage = MiniMessage.miniMessage()

    private val miniMessageTagPattern = Pattern.compile("<[a-zA-Z_]+>")

    private val mmToLegacyMap =
        mapOf(
            "<red>" to "&c",
            "<green>" to "&a",
            "<yellow>" to "&e",
            "<blue>" to "&9",
            "<gray>" to "&7",
            "<gold>" to "&6",
            "<white>" to "&f",
            "<black>" to "&0",
            "<dark_red>" to "&4",
            "<dark_green>" to "&2",
            "<dark_blue>" to "&1",
            "<dark_aqua>" to "&3",
            "<dark_purple>" to "&5",
            "<dark_gray>" to "&8",
            "<bold>" to "&l",
            "<italic>" to "&o",
            "<underline>" to "&n",
            "<strikethrough>" to "&m",
            "<obfuscated>" to "&k",
            "<reset>" to "&r",
        )

    /** Escape user-supplied text so it is not parsed as MiniMessage tags. */
    @JvmStatic
    fun escapeMM(s: String): String = s.replace("\\", "\\\\").replace("<", "\\<")

    @JvmStatic
    fun mm(text: String): Component = miniMessage.deserialize(text)

    @JvmStatic
    fun mm(text: String, strip: Boolean, vararg replacers: String): Component {
        var result = text
        var i = 0
        while (i < replacers.size) {
            if (i + 1 >= replacers.size) break
            result = result.replace(replacers[i], replacers[i + 1])
            i += 2
        }
        return mm(result, strip)
    }

    @JvmStatic
    fun mm(text: String, resolver: TagResolver): Component =
        miniMessage.deserialize(text, resolver)

    @JvmStatic
    fun mm(text: String, strip: Boolean): Component {
        val component = miniMessage.deserialize(text)
        return if (strip) strip(component) ?: component else component
    }

    @JvmStatic
    fun legacy(message: String): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(message)

    @JvmStatic
    fun plain(component: Component): String =
        PlainTextComponentSerializer.plainText().serialize(component)

    @JvmStatic
    fun plain(minimessage: String): String = plain(mm(minimessage))

    @JvmStatic
    fun strip(component: Component?): Component? {
        if (component == null) return null
        return component.decoration(TextDecoration.ITALIC, false)
    }

    @JvmStatic
    fun text(serializedMessage: String): Component = Component.text(serializedMessage)

    @JvmStatic
    fun centerInLore(s: String, length: Int): String {
        if (length <= s.length) return s
        val padding = length - s.length
        val leftPadding = padding / 2
        val rightPadding = padding - leftPadding
        return " ".repeat(leftPadding) + s + " ".repeat(rightPadding)
    }

    @JvmStatic
    fun toLegacy(miniMessageString: String, vararg tagReplacers: String): String {
        val builder = TagResolver.builder()
        var i = 0
        while (i < tagReplacers.size) {
            if (i + 1 >= tagReplacers.size) break
            builder.resolver(
                TagResolver.resolver(
                    tagReplacers[i],
                    Tag.inserting(mm(tagReplacers[i + 1], strip = true)),
                ),
            )
            i += 2
        }
        val resolver = builder.build()
        return LegacyComponentSerializer.legacyAmpersand()
            .serialize(strip(miniMessage.deserialize(miniMessageString, resolver)) ?: Component.empty())
    }

    @JvmStatic
    fun mmToLegacy(message: String): String {
        var result = message
        for ((key, value) in mmToLegacyMap) {
            result = result.replace(key, value)
        }
        return result
    }

    @JvmStatic
    fun toMM(component: Component): String = miniMessage.serialize(component)

    @JvmStatic
    fun formatAmount(amount: Double): String {
        val symbols =
            DecimalFormatSymbols().apply {
                groupingSeparator = ','
            }
        if (kotlin.math.abs(amount) < 0.0001) return "0"
        if (kotlin.math.abs(amount) < 1) {
            return DecimalFormat("#,##0.###", symbols).format(amount)
        }
        if (kotlin.math.abs(amount) < 10) {
            return DecimalFormat("#,##0.##", symbols).format(amount)
        }
        if (kotlin.math.abs(amount) < 1000) {
            return DecimalFormat("#,##0.#", symbols).format(amount)
        }
        if (kotlin.math.abs(amount) < 100_000) {
            return DecimalFormat("#,##0.##K", symbols).format(amount / 1000.0)
        }
        if (kotlin.math.abs(amount) < 1_000_000) {
            return DecimalFormat("#,##0.#K", symbols).format(amount / 1000)
        }
        return DecimalFormat("#,##0.#M", symbols).format(amount / 1_000_000)
    }

    @JvmStatic
    fun formatAmount(amount: Double, precision: Int): String {
        val symbols =
            DecimalFormatSymbols().apply {
                groupingSeparator = ','
                decimalSeparator = '.'
            }

        if (kotlin.math.abs(amount) < Math.pow(10.0, -precision.toDouble())) {
            return "0"
        }

        val orderOfMagnitude = Math.floor(Math.log10(kotlin.math.abs(amount))).toInt()
        val digitsBeforeDecimal = Math.max(1, Math.min(precision + 1, orderOfMagnitude + 1))
        val digitsAfterDecimal = Math.max(0, precision + 1 - digitsBeforeDecimal)

        val patternBuilder = StringBuilder("#,##0")
        if (digitsAfterDecimal > 0) {
            patternBuilder.append('.')
            patternBuilder.append("#".repeat(digitsAfterDecimal))
        }

        val decimalFormat = DecimalFormat(patternBuilder.toString(), symbols)
        if (orderOfMagnitude in 3..5 && precision < 3) {
            return decimalFormat.format(amount / 1000.0) + "K"
        }
        if (orderOfMagnitude >= 6 && precision < 6) {
            return decimalFormat.format(amount / 1_000_000.0) + "M"
        }

        return decimalFormat.format(amount)
    }

    @JvmStatic
    fun splitLoreString(input: String?, maxLength: Int, nSpaces: Int): List<String> {
        if (input == null) return emptyList()
        require(maxLength > 0) { "maxLength must be positive" }
        require(nSpaces >= 0) { "nSpaces cannot be negative" }
        val result = mutableListOf<String>()
        var currentLine = StringBuilder()
        var currentFormat = ""

        val indent = " ".repeat(nSpaces)
        val words = input.split(" ")

        for (word in words) {
            val separatorLength = if (isNonEmptyWithoutTags(currentLine)) 1 else 0
            if (currentLine.isNotEmpty() && currentLine.length + separatorLength + word.length > maxLength) {
                result.add(currentLine.toString())
                currentLine = StringBuilder(currentFormat + indent)
            }

            if (isNonEmptyWithoutTags(currentLine)) {
                currentLine.append(" ")
            }

            currentLine.append(word)

            val matcher = miniMessageTagPattern.matcher(word)
            while (matcher.find()) {
                currentFormat = matcher.group()
            }
        }

        if (currentLine.isNotEmpty()) {
            result.add(currentLine.toString())
        }

        return result
    }

    private fun isNonEmptyWithoutTags(line: StringBuilder): Boolean {
        val lineWithoutTags = line.toString().replace(miniMessageTagPattern.pattern().toRegex(), "")
        return lineWithoutTags.trim().isNotEmpty()
    }

    @JvmStatic
    fun join(names: Set<Component>, separator: String): Component {
        var result = Component.empty()
        val iterator = names.iterator()
        while (iterator.hasNext()) {
            val name = iterator.next()
            result = result.append(name)
            if (iterator.hasNext()) {
                result = result.append(mm(separator))
            }
        }
        return result
    }
}
