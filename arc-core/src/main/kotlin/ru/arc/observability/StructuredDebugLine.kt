package ru.arc.observability

/**
 * Stable, single-line `key=value` formatter for operator and agent readback.
 * Values are bounded and escaped; the formatter never serializes objects or
 * payloads beyond their explicit [Any.toString] representation.
 */
class StructuredDebugLine(
    prefix: String,
    private val maxTokenCharacters: Int = 64,
    private val maxValueCharacters: Int = 240,
    private val maxFields: Int = 32,
) {
    val prefix: String = prefix.also {
        require(it.matches(Regex("[A-Z][A-Z0-9_]{1,63}"))) {
            "Debug prefix must be an uppercase stable token"
        }
    }

    init {
        require(maxTokenCharacters in 1..128) { "Debug token limit must be between 1 and 128" }
        require(maxValueCharacters in 1..4_096) { "Debug value limit must be between 1 and 4096" }
        require(maxFields in 1..128) { "Debug field limit must be between 1 and 128" }
    }

    fun line(vararg fields: Pair<String, Any?>): String = line(fields.asList())

    fun line(fields: Iterable<Pair<String, Any?>>): String = buildString {
        append(prefix)
        val observedTokens = mutableSetOf<String>()
        var fieldCount = 0
        fields.forEach { (key, value) ->
            fieldCount++
            require(fieldCount <= maxFields) { "Debug line contains too many fields" }
            val normalizedKey = token(key)
            require(observedTokens.add(normalizedKey)) { "Debug line contains duplicate normalized field '$normalizedKey'" }
            append(' ').append(normalizedKey).append('=').append(encoded(value))
        }
    }

    fun token(value: String): String =
        value.lowercase()
            .replace(TOKEN_INVALID, "_")
            .trim('_')
            .take(maxTokenCharacters)
            .ifEmpty { "field" }

    fun encoded(value: Any?): String {
        val raw = value?.toString().orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\u0000', ' ')
            .take(maxValueCharacters)
        if (raw.isEmpty()) return "-"
        if (raw.all(::isSafeUnquoted)) return raw
        return "\"${raw.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

    private fun isSafeUnquoted(character: Char): Boolean =
        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character in SAFE_PUNCTUATION

    private companion object {
        val TOKEN_INVALID = Regex("[^a-z0-9_.-]")
        val SAFE_PUNCTUATION = setOf('_', '.', ':', '/', '@', '+', ',', '-')
    }
}
