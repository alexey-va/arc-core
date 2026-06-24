package ru.arc.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import ru.arc.util.TextUtils
import java.time.Duration
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

// ── DSL marker ────────────────────────────────────────────────────────────────

@DslMarker
annotation class TagBuilderMarker

/** Block comment for editable MiniMessage placeholders (used by [Config.component] and item inject). */
fun formatAvailableTagsComment(tagNames: Collection<String>): String? {
    if (tagNames.isEmpty()) return null
    return "Available tags: " + tagNames.sorted().joinToString(", ") { "<$it>" }
}

/**
 * Builder for creating TagResolver instances with a fluent API.
 *
 * Example:
 * ```kotlin
 * config.component("path", "default") {
 *     tag("player", playerName)
 *     tag("score", player.score)
 * }
 * ```
 */
@TagBuilderMarker
class TagResolverBuilder {
    private val tags = mutableListOf<TagResolver>()
    private val tagNames = mutableListOf<String>()

    fun tag(
        name: String,
        value: String,
    ) {
        tagNames.add(name)
        tags.add(TagResolver.resolver(name, Tag.inserting(TextUtils.mm(value, true))))
    }

    fun tag(
        name: String,
        valueProvider: () -> String,
    ) {
        tagNames.add(name)
        tags.add(TagResolver.resolver(name, Tag.inserting(TextUtils.mm(valueProvider(), true))))
    }

    fun tag(
        name: String,
        component: Component,
    ) {
        tagNames.add(name)
        tags.add(TagResolver.resolver(name, Tag.inserting(component)))
    }

    fun tag(
        name: String,
        value: Number,
    ) {
        tagNames.add(name)
        tags.add(TagResolver.resolver(name, Tag.inserting(Component.text(value.toString()))))
    }

    fun getTagNames(): List<String> = tagNames.toList()

    fun build(): TagResolver = TagResolver.resolver(tags)
}

// ── ConfigProperty ────────────────────────────────────────────────────────────

/**
 * Property delegate that reads from config on each access, enabling hot-reload.
 */
class ConfigProperty<T>(
    private val getter: () -> T,
) : ReadOnlyProperty<Any?, T> {
    override fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T = getter()
}

// ── ConfigSection ─────────────────────────────────────────────────────────────

/**
 * Scoped view of a [Config] at a specific path prefix.
 *
 * Example:
 * ```kotlin
 * val redis = config.section("redis")
 * val host = redis.string("host", "localhost")  // reads "redis.host"
 * val port = redis.int("port", 6379)            // reads "redis.port"
 * ```
 */
class ConfigSection(
    val config: Config,
    private val prefix: String,
) {
    fun path(subPath: String) = "$prefix.$subPath"

    // ── Scalars ───────────────────────────────────────────────────────────

    fun int(
        subPath: String,
        default: Int = 0,
    ) = config.int(path(subPath), default)

    fun long(
        subPath: String,
        default: Long = 0L,
    ) = config.long(path(subPath), default)

    fun double(
        subPath: String,
        default: Double = 0.0,
    ) = config.double(path(subPath), default)

    fun boolean(
        subPath: String,
        default: Boolean = false,
    ) = config.boolean(path(subPath), default)

    fun string(
        subPath: String,
        default: String = "",
    ) = config.string(path(subPath), default)

    fun stringList(
        subPath: String,
        default: List<String> = emptyList(),
    ) = config.stringList(path(subPath), default)

    fun stringSet(subPath: String) = config.stringSet(path(subPath))

    fun intOrNull(subPath: String) = config.intOrNull(path(subPath))

    fun longOrNull(subPath: String) = config.longOrNull(path(subPath))

    fun doubleOrNull(subPath: String) = config.doubleOrNull(path(subPath))

    fun booleanOrNull(subPath: String) = config.booleanOrNull(path(subPath))

    fun stringOrNull(subPath: String) = config.stringOrNull(path(subPath))

    fun stringListOrNull(subPath: String) = config.stringListOrNull(path(subPath))

    // ── Time / Color / Enum ───────────────────────────────────────────────

    fun durationOrNull(subPath: String) = config.durationOrNull(path(subPath))

    fun duration(
        subPath: String,
        default: Duration,
    ) = config.duration(path(subPath), default)

    fun durationMillis(
        subPath: String,
        default: Long,
    ) = config.durationMillis(path(subPath), default)

    fun durationTicks(
        subPath: String,
        default: Long,
    ) = config.durationTicks(path(subPath), default)

    fun colorOrNull(subPath: String) = config.colorOrNull(path(subPath))

    fun color(
        subPath: String,
        default: TextColor,
    ) = config.color(path(subPath), default)

    inline fun <reified E : Enum<E>> enumOrNull(subPath: String) = config.enumOrNull<E>(path(subPath))

    inline fun <reified E : Enum<E>> enum(
        subPath: String,
        default: E,
    ) = config.enum(path(subPath), default)

    inline fun <reified E : Enum<E>> enumSet(
        subPath: String,
        default: Set<E> = emptySet(),
    ) = config.enumSet<E>(path(subPath), default)

    // ── Components ────────────────────────────────────────────────────────

    /** Primary component accessor — always requires a default. */
    fun component(
        subPath: String,
        default: String,
        tags: TagResolverBuilder.() -> Unit = {},
    ) = config.component(path(subPath), default, tags)

    /** Legacy resolver-based accessor. */
    fun component(
        subPath: String,
        tagResolver: TagResolver,
    ) = config.component(path(subPath), tagResolver)

    fun componentList(
        subPath: String,
        tags: TagResolverBuilder.() -> Unit = {},
    ) = config.componentList(path(subPath), tags)

    fun componentList(
        subPath: String,
        default: List<String>,
        tags: TagResolverBuilder.() -> Unit = {},
    ) = config.componentList(path(subPath), default, tags)

    fun componentList(
        subPath: String,
        tagResolver: TagResolver,
    ) = config.componentList(path(subPath), tagResolver)

    // ── Structural ────────────────────────────────────────────────────────

    fun keys(subPath: String): Set<String> = config.keys(path(subPath))

    fun <T> map(subPath: String): Map<String, T> = config.map(path(subPath))

    fun <T> map(
        subPath: String,
        default: Map<String, T>,
    ): Map<String, T> = config.map(path(subPath), default)

    fun section(subPath: String) = ConfigSection(config, path(subPath))

    fun exists(subPath: String) = config.exists(path(subPath))
}
