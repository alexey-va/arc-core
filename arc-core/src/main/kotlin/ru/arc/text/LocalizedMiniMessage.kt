package ru.arc.text

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import ru.arc.config.Config
import java.util.Locale

/** Minimal source contract for localized scalar and list messages. */
interface LocaleCatalog {
    fun scalar(path: String): String?

    fun lines(path: String): List<String>?
}

class ConfigLocaleCatalog(private val config: Config) : LocaleCatalog {
    override fun scalar(path: String): String? = config.stringOrNull(path)

    override fun lines(path: String): List<String>? = config.stringListOrNull(path)
}

data class LocaleRequirements(
    val scalarPaths: Set<String> = emptySet(),
    val listPaths: Set<String> = emptySet(),
)

/**
 * Agent-friendly localized MiniMessage renderer with explicit selection,
 * fallback, key validation and component-only placeholders.
 *
 * Untrusted values must be passed through [literal]; callers cannot inject raw
 * strings into the MiniMessage input through the placeholder API.
 */
class LocalizedMiniMessage(
    catalogs: Map<String, LocaleCatalog>,
    private val defaultLocale: () -> String,
    private val prefixPath: String = "prefix",
    private val missingMessage: (String) -> String = { it },
    private val miniMessage: MiniMessage = MiniMessage.miniMessage(),
) {
    private val catalogs = catalogs.mapKeys { (locale, _) -> normalize(locale) }

    init {
        require(this.catalogs.isNotEmpty()) { "At least one locale catalog is required" }
        require(this.catalogs.keys.none(String::isBlank)) { "Locale catalog keys must not be blank" }
        require(prefixPath.isNotBlank()) { "Locale prefix path must not be blank" }
    }

    fun render(
        path: String,
        localeTag: String? = null,
        values: Map<String, Component> = emptyMap(),
    ): Component = deserialize(rawScalar(path, localeTag), localeTag, values)

    fun renderLines(
        path: String,
        localeTag: String? = null,
        values: Map<String, Component> = emptyMap(),
    ): List<Component> {
        val selected = select(localeTag)
        val fallback = fallback()
        val raw = selected.lines(path)?.takeIf { it.isNotEmpty() }
            ?: fallback.lines(path)?.takeIf { it.isNotEmpty() }
            ?: emptyList()
        return raw.map { deserialize(it, localeTag, values) }
    }

    fun validate(requirements: LocaleRequirements) {
        catalogs.forEach { (locale, catalog) ->
            requirements.scalarPaths.forEach { path ->
                require(catalog.scalar(path)?.isNotBlank() == true) { "Locale $locale is missing scalar message $path" }
            }
            requirements.listPaths.forEach { path ->
                require(catalog.lines(path)?.takeIf { it.isNotEmpty() }?.all(String::isNotBlank) == true) {
                    "Locale $locale is missing list message $path"
                }
            }
        }
    }

    fun literal(value: Any?): Component = Component.text(value?.toString().orEmpty())

    private fun rawScalar(path: String, localeTag: String?): String {
        require(path.isNotBlank()) { "Locale message path must not be blank" }
        return select(localeTag).scalar(path)?.takeIf(String::isNotBlank)
            ?: fallback().scalar(path)?.takeIf(String::isNotBlank)
            ?: missingMessage(path)
    }

    private fun deserialize(raw: String, localeTag: String?, values: Map<String, Component>): Component {
        val prefixRaw = select(localeTag).scalar(prefixPath)?.takeIf(String::isNotBlank)
            ?: fallback().scalar(prefixPath)?.takeIf(String::isNotBlank)
            ?: ""
        val builder = TagResolver.builder()
            .resolver(Placeholder.component("prefix", miniMessage.deserialize(prefixRaw)))
        values.forEach { (name, component) ->
            require(name.matches(PLACEHOLDER_NAME)) { "Unsafe MiniMessage placeholder name: $name" }
            require(name != "prefix") { "The prefix placeholder is owned by LocalizedMiniMessage" }
            builder.resolver(Placeholder.component(name, component))
        }
        return miniMessage.deserialize(raw, builder.build())
    }

    private fun select(localeTag: String?): LocaleCatalog {
        val normalized = normalize(localeTag.orEmpty())
        return catalogs[normalized]
            ?: catalogs[normalized.substringBefore('-')]
            ?: fallback()
    }

    private fun fallback(): LocaleCatalog {
        val configured = normalize(defaultLocale())
        return catalogs[configured]
            ?: catalogs[configured.substringBefore('-')]
            ?: error("Default locale '$configured' has no catalog")
    }

    private fun normalize(value: String): String =
        Locale.forLanguageTag(value.replace('_', '-')).toLanguageTag().lowercase().takeUnless { it == "und" }.orEmpty()

    private companion object {
        val PLACEHOLDER_NAME = Regex("[a-z0-9_-]{1,64}")
    }
}
