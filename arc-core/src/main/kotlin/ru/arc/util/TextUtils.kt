package ru.arc.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/** Adventure / MiniMessage helpers without platform dependencies. */
object TextUtils {
    private val miniMessage = MiniMessage.miniMessage()

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
}
