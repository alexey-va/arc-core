package ru.arc.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.StreamDataWriter
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.api.lowlevel.Serialize
import org.snakeyaml.engine.v2.comments.CommentLine
import org.snakeyaml.engine.v2.comments.CommentType
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.common.ScalarStyle
import org.snakeyaml.engine.v2.emitter.Emitter
import org.snakeyaml.engine.v2.exceptions.Mark
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.NodeTuple
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import org.snakeyaml.engine.v2.nodes.Tag
import org.slf4j.LoggerFactory
import ru.arc.util.TextUtils
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private val configLog = LoggerFactory.getLogger(Config::class.java)

/**
 * Configuration system using SnakeYAML Engine v2 with full comment preservation.
 *
 * Drop-in replacement for the old SnakeYAML v1 based Config with the same API surface.
 * Comments in YAML files survive save/reload cycles.
 *
 * Designed to mirror ru.arccore.Config so that when ARCCore becomes a dependency
 * the migration reduces to removing this file and updating imports.
 * ARC-specific Bukkit accessors (material, particle, sound, etc.) sit below
 * the ARCCore-compatible API and will eventually move to extension functions.
 */
@Suppress("UNCHECKED_CAST")
open class Config(
    private val folder: Path,
    private val filePath: String,
) {
    val dataFolder: File get() = folder.toFile()

    // ── SnakeYAML Engine v2 internals ──────────────────────────────────────

    private var rootNode: MappingNode

    /** Per-path comment registry, flushed to nodes on [save]. */
    private val comments = ConcurrentHashMap<String, String>()

    private val nodeLock = ReentrantReadWriteLock()

    @Volatile
    var version: Int = 0
        private set

    private val loadSettings =
        LoadSettings
            .builder()
            .setParseComments(true)
            .build()
    private val dumpSettings =
        DumpSettings
            .builder()
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .setIndent(2)
            .setDumpComments(true)
            .build()

    init {
        Files.createDirectories(folder)
        // Copy the bundled default resource to disk if missing — same as old behaviour.
        // In production ARC.createDefaultConfigs() has already done this; in tests this
        // ensures the bundled YAML is present in the temp directory before first load.
        copyDefaultConfig(filePath, folder, false)
        rootNode = loadNode()
    }

    // ── Comment API ────────────────────────────────────────────────────────

    fun setComment(
        path: String,
        comment: String,
    ) {
        comments[path] = comment
    }

    // ── Setters ────────────────────────────────────────────────────────────

    fun setInt(
        path: String,
        value: Int,
        comment: String? = null,
    ) {
        comment?.let { setComment(path, it) }
        setValue(path, value)
    }

    fun setBoolean(
        path: String,
        value: Boolean,
        comment: String? = null,
    ) {
        comment?.let { setComment(path, it) }
        setValue(path, value)
    }

    fun setString(
        path: String,
        value: String,
        comment: String? = null,
    ) {
        comment?.let { setComment(path, it) }
        setValue(path, value)
    }

    fun setStringList(
        path: String,
        value: List<String>,
        comment: String? = null,
    ) {
        comment?.let { setComment(path, it) }
        setValue(path, value)
    }

    fun setDouble(
        path: String,
        value: Double,
        comment: String? = null,
    ) {
        comment?.let { setComment(path, it) }
        setValue(path, value)
    }

    fun setLong(
        path: String,
        value: Long,
        comment: String? = null,
    ) {
        comment?.let { setComment(path, it) }
        setValue(path, value)
    }

    /**
     * Replaces one config subtree with a YAML-safe scalar, map, or list.
     *
     * Use this for already validated structured content. Unsupported object
     * types are rejected instead of being silently stringified.
     */
    fun setStructured(
        path: String,
        value: Any,
    ) {
        require(path.isNotBlank() && path.split('.').none { it.isBlank() }) {
            "Config path must contain non-empty dot-separated keys"
        }
        validateStructuredValue(value, path)
        setValue(path, value)
    }

    // ── Scalar accessors ───────────────────────────────────────────────────

    @JvmOverloads
    fun int(
        path: String,
        default: Int = 0,
        comment: String? = null,
    ): Int {
        comment?.let { setComment(path, it) }
        val value = getValue(path)
        if (value == null) {
            setValue(path, default)
            return default
        }
        return when (value) {
            is Number -> {
                value.toInt()
            }

            is String -> {
                value.toIntOrNull() ?: run {
                    configLog.warn("Could not parse int from '{}' ({}), using default", path, value)
                    default
                }
            }

            else -> {
                default
            }
        }
    }

    @JvmOverloads
    fun long(
        path: String,
        default: Long = 0L,
        comment: String? = null,
    ): Long {
        comment?.let { setComment(path, it) }
        val value = getValue(path)
        if (value == null) {
            setValue(path, default)
            return default
        }
        return when (value) {
            is Number -> {
                value.toLong()
            }

            is String -> {
                value.toLongOrNull() ?: run {
                    configLog.warn("Could not parse long from '{}' ({}), using default", path, value)
                    default
                }
            }

            else -> {
                default
            }
        }
    }

    @JvmOverloads
    fun double(
        path: String,
        default: Double = 0.0,
        comment: String? = null,
    ): Double {
        comment?.let { setComment(path, it) }
        val value = getValue(path)
        if (value == null) {
            setValue(path, default)
            return default
        }
        return when (value) {
            is Number -> {
                value.toDouble()
            }

            is String -> {
                value.toDoubleOrNull() ?: run {
                    configLog.warn("Could not parse double from '{}' ({}), using default", path, value)
                    default
                }
            }

            else -> {
                default
            }
        }
    }

    @JvmOverloads
    fun boolean(
        path: String,
        default: Boolean = false,
        comment: String? = null,
    ): Boolean {
        comment?.let { setComment(path, it) }
        val value = getValue(path)
        if (value == null) {
            setValue(path, default)
            return default
        }
        return when (value) {
            is Boolean -> {
                value
            }

            is Number -> {
                value.toInt() == 1
            }

            is String -> {
                when (value.trim().lowercase()) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> default
                }
            }

            else -> {
                default
            }
        }
    }

    @JvmOverloads
    fun string(
        path: String,
        default: String = "",
        comment: String? = null,
    ): String {
        comment?.let { setComment(path, it) }
        val value = getValue(path)
        if (value == null) {
            setValue(path, default)
            return default
        }
        return value.toString()
    }

    // ── Nullable accessors ─────────────────────────────────────────────────

    fun intOrNull(path: String): Int? =
        getValue(path)?.let {
            when (it) {
                is Number -> it.toInt()
                is String -> it.toIntOrNull()
                else -> null
            }
        }

    fun longOrNull(path: String): Long? =
        getValue(path)?.let {
            when (it) {
                is Number -> it.toLong()
                is String -> it.toLongOrNull()
                else -> null
            }
        }

    fun doubleOrNull(path: String): Double? =
        getValue(path)?.let {
            when (it) {
                is Number -> it.toDouble()
                is String -> it.toDoubleOrNull()
                else -> null
            }
        }

    fun booleanOrNull(path: String): Boolean? =
        getValue(path)?.let {
            when (it) {
                is Boolean -> {
                    it
                }

                is Number -> {
                    it.toInt() == 1
                }

                is String -> {
                    when (it.trim().lowercase()) {
                        "true", "1", "yes" -> true
                        "false", "0", "no" -> false
                        else -> null
                    }
                }

                else -> {
                    null
                }
            }
        }

    fun stringOrNull(path: String): String? = getValue(path)?.toString()

    // ── List accessors ─────────────────────────────────────────────────────

    @JvmOverloads
    fun stringList(
        path: String,
        default: List<String> = emptyList(),
    ): List<String> {
        val value =
            getValue(path) ?: run {
                setValue(path, default)
                return default
            }
        return when (value) {
            is List<*> -> value.mapNotNull { it?.toString() }
            else -> listOf(value.toString())
        }
    }

    fun stringListOrNull(path: String): List<String>? {
        val value = getValue(path) ?: return null
        return when (value) {
            is List<*> -> value.mapNotNull { it?.toString() }
            else -> listOf(value.toString())
        }
    }

    fun stringSet(path: String): Set<String> = stringList(path).toSet()

    fun <T> list(path: String): MutableList<T> {
        val value =
            getValue(path) ?: run {
                setValue(path, emptyList<Any>())
                return mutableListOf()
            }
        return (value as List<T>).toMutableList()
    }

    fun <T> list(
        path: String,
        default: List<T>,
    ): MutableList<T> {
        val value =
            getValue(path) ?: run {
                setValue(path, default)
                return default.toMutableList()
            }
        return (value as List<T>).toMutableList()
    }

    // ── Map / structural accessors ─────────────────────────────────────────

    fun <T> map(path: String): Map<String, T> {
        val value =
            getValue(path) ?: run {
                setValue(path, emptyMap<String, T>())
                return emptyMap()
            }
        return value as Map<String, T>
    }

    fun <T> map(
        path: String,
        default: Map<String, T>,
    ): Map<String, T> {
        val value = getValue(path) ?: return default
        if (value is Map<*, *> && value.isEmpty()) return value as Map<String, T>
        return value as? Map<String, T> ?: default
    }

    fun keys(path: String): Set<String> {
        val value = getValue(path) ?: return emptySet()
        return when (value) {
            is Map<*, *> -> value.keys.mapNotNull { it?.toString() }.toSet()
            else -> emptySet()
        }
    }

    fun exists(path: String): Boolean = getValue(path) != null

    fun section(prefix: String) = ConfigSection(this, prefix)

    // ── Duration ───────────────────────────────────────────────────────────

    fun durationOrNull(path: String): Duration? {
        val value = stringOrNull(path) ?: return null
        return parseDuration(value)
    }

    fun duration(
        path: String,
        default: Duration,
    ): Duration = durationOrNull(path) ?: default

    fun durationMillis(
        path: String,
        default: Long,
    ): Long = durationOrNull(path)?.toMillis() ?: default

    fun durationSeconds(
        path: String,
        default: Long,
    ): Long = durationOrNull(path)?.toSeconds() ?: default

    fun durationTicks(
        path: String,
        default: Long,
    ): Long = durationOrNull(path)?.toMillis()?.div(50) ?: default

    // ── Color ──────────────────────────────────────────────────────────────

    fun colorOrNull(path: String): TextColor? {
        val hex = stringOrNull(path)?.removePrefix("#") ?: return null
        return runCatching { TextColor.fromHexString("#$hex") }.getOrNull()
    }

    fun color(
        path: String,
        default: TextColor,
    ): TextColor = colorOrNull(path) ?: default

    // ── Enum ───────────────────────────────────────────────────────────────

    inline fun <reified E : Enum<E>> enumOrNull(path: String): E? {
        val name = stringOrNull(path) ?: return null
        return runCatching { enumValueOf<E>(name.uppercase()) }.getOrNull()
    }

    inline fun <reified E : Enum<E>> enum(
        path: String,
        default: E,
    ): E = enumOrNull<E>(path) ?: default

    inline fun <reified E : Enum<E>> enumSet(
        path: String,
        default: Set<E> = emptySet(),
    ): Set<E> {
        val list = stringListOrNull(path) ?: return default
        return list
            .mapNotNull { runCatching { enumValueOf<E>(it.uppercase()) }.getOrNull() }
            .toSet()
            .ifEmpty { default }
    }

    // ── Component (Adventure / MiniMessage) ───────────────────────────────

    /**
     * Get a Component using the TagResolver DSL — always requires a default.
     * Auto-injects the default and a tag comment when the key is missing.
     */
    fun component(
        path: String,
        default: String,
        tags: TagResolverBuilder.() -> Unit = {},
    ): Component {
        val builder = TagResolverBuilder().apply(tags)
        val value = stringOrNull(path)
        if (value == null) {
            val tagNames = builder.getTagNames()
            if (tagNames.isNotEmpty()) setComment(path, formatAvailableTagsComment(tagNames)!!)
            setValue(path, default)
            return MiniMessage.miniMessage().deserialize(default, builder.build())
        }
        return MiniMessage.miniMessage().deserialize(value, builder.build())
    }

    fun componentOrNull(
        path: String,
        tags: TagResolverBuilder.() -> Unit = {},
    ): Component? {
        val value = stringOrNull(path) ?: return null
        val resolver = TagResolverBuilder().apply(tags).build()
        return MiniMessage.miniMessage().deserialize(value, resolver)
    }

    fun componentList(
        path: String,
        tags: TagResolverBuilder.() -> Unit = {},
    ): List<Component> {
        val resolver = TagResolverBuilder().apply(tags).build()
        val list =
            stringListOrNull(path) ?: run {
                setValue(path, emptyList<String>())
                return emptyList()
            }
        return list.map { MiniMessage.miniMessage().deserialize(it, resolver) }
    }

    fun componentList(
        path: String,
        default: List<String>,
        tags: TagResolverBuilder.() -> Unit = {},
    ): List<Component> {
        val builder = TagResolverBuilder().apply(tags)
        val value = stringListOrNull(path)
        if (value == null) {
            val tagNames = builder.getTagNames()
            if (tagNames.isNotEmpty()) setComment(path, formatAvailableTagsComment(tagNames)!!)
            setValue(path, default)
            return default.map { MiniMessage.miniMessage().deserialize(it, builder.build()) }
        }
        return value.map { MiniMessage.miniMessage().deserialize(it, builder.build()) }
    }

    fun componentList(
        path: String,
        tagResolver: TagResolver,
    ): List<Component> {
        val list =
            stringListOrNull(path) ?: run {
                setValue(path, emptyList<String>())
                return emptyList()
            }
        return list.map { TextUtils.strip(TextUtils.mm(it, tagResolver))!! }
    }

    /**
     * Legacy component accessor with TagResolver — no default required (falls back to path key).
     * Prefer [component] with an explicit default for new code.
     */
    fun component(
        path: String,
        tagResolver: TagResolver,
    ): Component {
        val value =
            stringOrNull(path) ?: run {
                setValue(path, path)
                return TextUtils.strip(TextUtils.mm(path))!!
            }
        return TextUtils.strip(TextUtils.mm(value, tagResolver))!!
    }

    // ── Property delegates (hot-reload) ────────────────────────────────────

    fun intProp(
        path: String,
        default: Int = 0,
    ) = ConfigProperty { int(path, default) }

    fun longProp(
        path: String,
        default: Long = 0L,
    ) = ConfigProperty { long(path, default) }

    fun doubleProp(
        path: String,
        default: Double = 0.0,
    ) = ConfigProperty { double(path, default) }

    fun booleanProp(
        path: String,
        default: Boolean = false,
    ) = ConfigProperty { boolean(path, default) }

    fun stringProp(
        path: String,
        default: String = "",
    ) = ConfigProperty { string(path, default) }

    fun stringListProp(
        path: String,
        default: List<String> = emptyList(),
    ) = ConfigProperty { stringList(path, default) }

    fun durationProp(
        path: String,
        default: Duration,
    ) = ConfigProperty { duration(path, default) }

    fun colorProp(
        path: String,
        default: TextColor,
    ) = ConfigProperty { color(path, default) }

    inline fun <reified E : Enum<E>> enumProp(
        path: String,
        default: E,
    ) = ConfigProperty { enum(path, default) }

    fun componentProp(
        path: String,
        default: String,
        tags: TagResolverBuilder.() -> Unit = {},
    ) = ConfigProperty { component(path, default, tags) }

    fun intPropOrNull(path: String) = ConfigProperty { intOrNull(path) }

    fun longPropOrNull(path: String) = ConfigProperty { longOrNull(path) }

    fun doublePropOrNull(path: String) = ConfigProperty { doubleOrNull(path) }

    fun booleanPropOrNull(path: String) = ConfigProperty { booleanOrNull(path) }

    fun stringPropOrNull(path: String) = ConfigProperty { stringOrNull(path) }

    fun colorPropOrNull(path: String) = ConfigProperty { colorOrNull(path) }

    inline fun <reified E : Enum<E>> enumPropOrNull(path: String) = ConfigProperty { enumOrNull<E>(path) }

    fun componentPropOrNull(
        path: String,
        tags: TagResolverBuilder.() -> Unit = {},
    ) = ConfigProperty { componentOrNull(path, tags) }

    // ── Cached values ──────────────────────────────────────────────────────

    fun <T> cached(parser: Config.() -> T): CachedConfigValue<T> = CachedConfigValue(this, parser)

    // ── Validation helpers ─────────────────────────────────────────────────

    fun intInRange(
        path: String,
        default: Int,
        range: IntRange,
    ): Int = int(path, default).coerceIn(range)

    fun doubleInRange(
        path: String,
        default: Double,
        min: Double,
        max: Double,
    ): Double = double(path, default).coerceIn(min, max)

    fun stringMatching(
        path: String,
        default: String,
        pattern: Regex,
    ): String {
        val value = string(path, default)
        return if (pattern.matches(value)) value else default
    }

    // ── Mutable list helper ────────────────────────────────────────────────

    fun addToList(
        path: String,
        value: Any,
    ) {
        val current = list<Any>(path)
        current.add(value)
        setValue(path, current)
        save()
    }

    // ── Legacy name aliases (keep existing callers compiling) ──────────────

    @JvmOverloads
    fun integer(
        path: String,
        default: Int = 0,
    ): Int = int(path, default)

    @JvmOverloads
    fun bool(
        path: String,
        default: Boolean = false,
    ): Boolean = boolean(path, default)

    @JvmOverloads
    fun real(
        path: String,
        default: Double = 0.0,
    ): Double = double(path, default)

    @JvmOverloads
    fun longValue(
        path: String,
        default: Long = 0L,
    ): Long = long(path, default)

    // ── Public injectDeepKey (for test compatibility) ──────────────────────

    fun injectDeepKey(
        path: String,
        value: Any,
    ) {
        configLog.debug("Injecting key: {} with value: {}", path, value)
        setValue(path, value)
        save()
    }

    // ── Load / Save / Reload ───────────────────────────────────────────────

    open fun reload(): Unit =
        nodeLock.write {
            rootNode = loadNode()
            version++
        }

    /** Removes a key from the YAML tree (no-op if missing). */
    open fun removeKey(path: String) {
        nodeLock.write {
            removeKeyFromNode(rootNode, path.split("."))
        }
    }

    open fun save() {
        try {
            saveStrict()
        } catch (e: Exception) {
            configLog.error("Could not save config file: {}", filePath, e)
        }
    }

    /**
     * Atomically persists the current tree and propagates failures to callers.
     */
    fun saveStrict() {
        val yaml = serializeYaml()
        Files.createDirectories(folder)
        val target = folder.resolve(filePath)
        val temp = Files.createTempFile(folder, ".${target.fileName}-", ".tmp")
        try {
            Files.writeString(temp, yaml)
            try {
                Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Load (or reload) from disk. Called from [reload] and init. */
    fun load() = reload()

    // ── Internal node-tree helpers ─────────────────────────────────────────

    private fun loadNode(): MappingNode {
        return try {
            val file = folder.resolve(filePath)
            if (!Files.exists(file)) return createMappingNode(mutableListOf())
            val content = file.toFile().readText()
            if (content.isBlank()) return createMappingNode(mutableListOf())
            val parseContent = prepareYamlContentForParsing(content)
            if (parseContent != content) {
                configLog.debug("Sanitized YAML before parse: {} ({} -> {} chars)", filePath, content.length, parseContent.length)
            }
            val node =
                Compose(loadSettings)
                    .composeReader(SnakeYamlEngineStringReader(parseContent))
                    .orElse(null)
            if (node is MappingNode) node else createMappingNode(mutableListOf())
        } catch (e: Exception) {
            configLog.error("Could not load config: {}", filePath, e)
            createMappingNode(mutableListOf())
        }
    }

    private fun getValue(keyPath: String): Any? =
        nodeLock.read {
            val parts = keyPath.split(".")
            var current: Node? = rootNode
            for (part in parts) {
                current =
                    (current as? MappingNode)
                        ?.value
                        ?.find { (it.keyNode as? ScalarNode)?.value == part }
                        ?.valueNode
                if (current == null) return@read null
            }
            when (val node = current) {
                is ScalarNode -> parseScalarValue(node)
                is MappingNode -> convertNodeToMap(node)
                is SequenceNode -> convertNodeToList(node)
                else -> null
            }
        }

    private fun setValue(
        keyPath: String,
        value: Any,
    ) = nodeLock.write {
        val parts = keyPath.split(".")
        var current = rootNode
        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            val existing = current.value.find { (it.keyNode as? ScalarNode)?.value == part }
            current =
                if (existing != null && existing.valueNode is MappingNode) {
                    existing.valueNode as MappingNode
                } else {
                    val newMapping = createMappingNode(mutableListOf())
                    val newTuples = current.value.filter { (it.keyNode as? ScalarNode)?.value != part }.toMutableList()
                    newTuples.add(NodeTuple(createScalarNode(part), newMapping))
                    current.value.clear()
                    current.value.addAll(newTuples)
                    newMapping
                }
        }
        val finalKey = parts.last()
        val newTuples = current.value.filter { (it.keyNode as? ScalarNode)?.value != finalKey }.toMutableList()
        newTuples.add(NodeTuple(createScalarNode(finalKey), createNodeForValue(value)))
        current.value.clear()
        current.value.addAll(newTuples)
    }

    private fun serializeYaml(): String =
        nodeLock.write {
            applyComments(rootNode, "")
            val writer = StringWriter()
            val streamWriter =
                object : StreamDataWriter {
                    override fun write(str: String) = writer.write(str)

                    override fun write(
                        str: String,
                        off: Int,
                        len: Int,
                    ) = writer.write(str, off, len)

                    override fun flush() = writer.flush()
                }
            val emitter = Emitter(dumpSettings, streamWriter)
            val serialize = Serialize(dumpSettings)
            for (event in serialize.serializeOne(rootNode)) emitter.emit(event)
            writer.toString()
        }

    private fun validateStructuredValue(
        value: Any,
        path: String,
    ) {
        when (value) {
            is String, is Int, is Long, is Double, is Float, is Boolean -> Unit
            is Map<*, *> ->
                value.forEach { (key, nested) ->
                    require(key is String && key.isNotBlank()) {
                        "Config map at $path must use non-empty string keys"
                    }
                    require(nested != null) { "Config value at $path.$key must not be null" }
                    validateStructuredValue(nested, "$path.$key")
                }
            is List<*> ->
                value.forEachIndexed { index, nested ->
                    require(nested != null) { "Config value at $path[$index] must not be null" }
                    validateStructuredValue(nested, "$path[$index]")
                }
            else -> throw IllegalArgumentException(
                "Unsupported config value at $path: ${value::class.qualifiedName}",
            )
        }
    }

    private fun removeKeyFromNode(
        root: MappingNode,
        parts: List<String>,
    ) {
        if (parts.isEmpty()) return
        var current = root
        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            val existing = current.value.find { (it.keyNode as? ScalarNode)?.value == part } ?: return
            val next = existing.valueNode as? MappingNode ?: return
            current = next
        }
        val finalKey = parts.last()
        val newTuples = current.value.filter { (it.keyNode as? ScalarNode)?.value != finalKey }
        current.value.clear()
        current.value.addAll(newTuples)
    }

    private fun applyComments(
        node: MappingNode,
        prefix: String,
    ) {
        for (tuple in node.value) {
            val keyNode = tuple.keyNode as? ScalarNode ?: continue
            val fullPath = if (prefix.isEmpty()) keyNode.value else "$prefix.${keyNode.value}"
            comments[fullPath]?.let { comment ->
                keyNode.blockComments =
                    listOf(
                        CommentLine(Optional.empty<Mark>(), Optional.empty<Mark>(), " $comment", CommentType.BLOCK),
                    )
            }
            if (tuple.valueNode is MappingNode) applyComments(tuple.valueNode as MappingNode, fullPath)
        }
    }

    private fun createScalarNode(value: String) = ScalarNode(Tag.STR, value, ScalarStyle.PLAIN)

    private fun createMappingNode(tuples: MutableList<NodeTuple>) = MappingNode(Tag.MAP, tuples, FlowStyle.BLOCK)

    private fun createNodeForValue(value: Any): Node =
        when (value) {
            is String -> {
                ScalarNode(Tag.STR, value, ScalarStyle.PLAIN)
            }

            is Int -> {
                ScalarNode(Tag.INT, value.toString(), ScalarStyle.PLAIN)
            }

            is Long -> {
                ScalarNode(Tag.INT, value.toString(), ScalarStyle.PLAIN)
            }

            is Double -> {
                ScalarNode(Tag.FLOAT, value.toString(), ScalarStyle.PLAIN)
            }

            is Float -> {
                ScalarNode(Tag.FLOAT, value.toString(), ScalarStyle.PLAIN)
            }

            is Boolean -> {
                ScalarNode(Tag.BOOL, value.toString(), ScalarStyle.PLAIN)
            }

            is Map<*, *> -> {
                createMappingNode(
                    value.entries
                        .mapNotNull { (k, v) ->
                            if (k != null && v != null) {
                                NodeTuple(
                                    createScalarNode(k.toString()),
                                    createNodeForValue(v),
                                )
                            } else {
                                null
                            }
                        }.toMutableList(),
                )
            }

            is List<*> -> {
                SequenceNode(
                    Tag.SEQ,
                    value.mapNotNull { if (it != null) createNodeForValue(it) else null },
                    FlowStyle.BLOCK,
                )
            }

            else -> {
                ScalarNode(Tag.STR, value.toString(), ScalarStyle.PLAIN)
            }
        }

    private fun parseScalarValue(node: ScalarNode): Any? =
        when (node.tag) {
            Tag.INT -> {
                node.value.toLongOrNull() ?: node.value.toIntOrNull() ?: node.value
            }

            Tag.FLOAT -> {
                node.value.toDoubleOrNull() ?: node.value
            }

            Tag.BOOL -> {
                val strict = node.value.toBooleanStrictOrNull()
                strict ?: (node.value.lowercase() == "yes")
            }

            else -> node.value
        }

    private fun convertNodeToMap(node: MappingNode): Map<String, Any?> =
        buildMap {
            for (tuple in node.value) {
                val key = (tuple.keyNode as? ScalarNode)?.value ?: continue
                put(
                    key,
                    when (val v = tuple.valueNode) {
                        is ScalarNode -> parseScalarValue(v)
                        is MappingNode -> convertNodeToMap(v)
                        is SequenceNode -> convertNodeToList(v)
                        else -> null
                    },
                )
            }
        }

    private fun convertNodeToList(node: SequenceNode): List<Any?> =
        node.value.map { v ->
            when (v) {
                is ScalarNode -> parseScalarValue(v)
                is MappingNode -> convertNodeToMap(v)
                is SequenceNode -> convertNodeToList(v)
                else -> null
            }
        }

    // For compatibility with old code that accessed config.map directly
    val map: Map<String, Any?> get() = nodeLock.read { convertNodeToMap(rootNode) }

    // ── Companion ──────────────────────────────────────────────────────────

    companion object {
        private val DURATION_PATTERN = Regex("""(\d+)\s*(ms|s|sec|m|min|h|hour|d|day)?""", RegexOption.IGNORE_CASE)

        fun parseDuration(value: String): Duration? {
            var total = Duration.ZERO
            var found = false
            DURATION_PATTERN.findAll(value.trim()).forEach { match ->
                found = true
                val amount = match.groupValues[1].toLongOrNull() ?: return@forEach
                total =
                    total.plus(
                        when (match.groupValues[2].lowercase()) {
                            "", "ms" -> Duration.ofMillis(amount)
                            "s", "sec" -> Duration.ofSeconds(amount)
                            "m", "min" -> Duration.ofMinutes(amount)
                            "h", "hour" -> Duration.ofHours(amount)
                            "d", "day" -> Duration.ofDays(amount)
                            else -> Duration.ofMillis(amount)
                        },
                    )
            }
            return if (found) total else null
        }

        /**
         * Copy a bundled resource from the classpath to [folder] if it doesn't already exist.
         * Kept for call sites that relied on the old Config.init auto-copy behavior.
         */
        fun copyDefaultConfig(
            resource: String,
            folder: Path,
            replace: Boolean,
        ) {
            try {
                val path = folder.resolve(resource)
                if (Files.exists(path) && !replace) return
                openBundledResource(resource).use { stream ->
                    Files.createDirectories(path.parent)
                    if (stream == null) {
                        if (!Files.exists(path)) Files.createFile(path)
                    } else {
                        Files.copy(stream, path, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            } catch (e: Exception) {
                configLog.error("Could not copy default config: {}", resource)
            }
        }

        /** Classpath lookup: arc-core, arc-core-logging, arc-core-redis (canonical module YAML). */
        private fun openBundledResource(resource: String): java.io.InputStream? {
            val loaders =
                sequenceOf(
                    Config::class.java.classLoader,
                    Thread.currentThread().contextClassLoader,
                    runCatching { Class.forName("ru.arc.logging.LokiLogging").classLoader }.getOrNull(),
                    runCatching { Class.forName("ru.arc.ai.config.LlmModuleConfig").classLoader }.getOrNull(),
                    runCatching { Class.forName("ru.arc.ARC").classLoader }.getOrNull(),
                ).filterNotNull().distinct()
            for (loader in loaders) {
                loader.getResourceAsStream(resource)?.let { return it }
            }
            return null
        }
    }
}

/**
 * Prepares YAML text for SnakeYAML Engine parsing.
 *
 * - Normalizes MiniMessage hex shorthand `<#RRGGBB>` → `<color:#RRGGBB>` (parser bug after UTF-8 text).
 * - Replaces unpaired UTF-16 surrogates from truncated input with spaces.
 */
internal fun prepareYamlContentForParsing(content: String): String {
    if (content.isEmpty()) return content
    var normalized =
        MINIMESSAGE_HEX_SHORTHAND.replace(content) { match ->
            "<color:#${match.groupValues[1]}>"
        }
    normalized = sanitizeUnpairedSurrogates(normalized)
    if (Character.isHighSurrogate(normalized.last())) {
        normalized += ' '
    }
    return normalized
}

/**
 * SnakeYAML Engine 3.0.1 allocates a buffer one character larger than its configured window, then
 * calls `Reader.read(char[])`. If that fills the extra slot with a high surrogate, the engine tries
 * to append its low surrogate past the end of the array. Limit bulk reads by one character so the
 * engine's reserved slot remains available; one-character continuation reads are left untouched.
 */
private class SnakeYamlEngineStringReader(
    content: String,
) : StringReader(content) {
    override fun read(
        buffer: CharArray,
        offset: Int,
        length: Int,
    ): Int = super.read(buffer, offset, if (length > 1) length - 1 else length)
}

/** Strips lone UTF-16 surrogates that break SnakeYAML Engine on some large files. */
fun sanitizeUnpairedSurrogates(content: String): String {
    if (content.isEmpty()) return content
    val out = StringBuilder(content.length)
    var i = 0
    while (i < content.length) {
        val ch = content[i]
        when {
            Character.isHighSurrogate(ch) -> {
                if (i + 1 < content.length && Character.isLowSurrogate(content[i + 1])) {
                    out.append(ch).append(content[i + 1])
                    i += 2
                } else {
                    out.append(' ')
                    i++
                }
            }

            Character.isLowSurrogate(ch) -> {
                out.append(' ')
                i++
            }

            else -> {
                out.append(ch)
                i++
            }
        }
    }
    return out.toString()
}

private val MINIMESSAGE_HEX_SHORTHAND = Regex("""<#([0-9A-Fa-f]{6})>""")

// ── CachedConfigValue ──────────────────────────────────────────────────────────

/**
 * Caches a parsed config value and re-parses only when [Config.version] increments (on reload).
 */
class CachedConfigValue<T>(
    private val config: Config,
    private val parser: Config.() -> T,
) {
    @Volatile
    private var cachedValue: T? = null

    @Volatile
    private var cachedVersion: Int = -1

    fun get(): T {
        val cv = config.version
        if (cachedVersion == cv && cachedValue != null) return cachedValue!!
        return synchronized(this) {
            if (cachedVersion == cv && cachedValue != null) {
                cachedValue!!
            } else {
                config.parser().also {
                    cachedValue = it
                    cachedVersion = cv
                }
            }
        }
    }

    fun invalidate() {
        cachedVersion = -1
    }
}
