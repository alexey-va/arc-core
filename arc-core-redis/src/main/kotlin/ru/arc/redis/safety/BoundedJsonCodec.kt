package ru.arc.redis.safety

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal

/** Explicit root-object contract for one closed wire type. */
data class JsonObjectContract(
    val allowedFields: Set<String>,
    val requiredFields: Set<String> = allowedFields,
) {
    init {
        require(allowedFields.isNotEmpty()) { "A JSON object contract must allow at least one field" }
        require(allowedFields.size <= 256) { "A JSON object contract may define at most 256 fields" }
        require(requiredFields.all(allowedFields::contains)) { "Required JSON fields must also be allowed" }
        allowedFields.forEach { field ->
            require(field.matches(FIELD_NAME)) { "Unsafe JSON field name in object contract" }
        }
    }

    internal fun validate(value: JsonElement) {
        require(value.isJsonObject) { "Wire JSON root must be an object" }
        val fields = value.asJsonObject.keySet()
        require(fields.all(allowedFields::contains)) { "Wire JSON contains an unknown root field" }
        require(fields.containsAll(requiredFields)) { "Wire JSON is missing a required root field" }
    }

    private companion object {
        val FIELD_NAME = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
    }
}

data class JsonResourceBounds(
    val maxCharacters: Int,
    val maxDepth: Int = 16,
    val maxContainerEntries: Int = 10_000,
    val maxTotalNodes: Int = 100_000,
    val maxStringCharacters: Int = maxCharacters,
) {
    init {
        require(maxCharacters in 2..16_000_000) { "JSON character limit must be between 2 and 16000000" }
        require(maxDepth in 1..64) { "JSON depth limit must be between 1 and 64" }
        require(maxContainerEntries in 1..100_000) { "JSON container limit must be between 1 and 100000" }
        require(maxTotalNodes in 1..1_000_000) { "JSON node limit must be between 1 and 1000000" }
        require(maxStringCharacters in 1..maxCharacters) { "JSON string limit must fit inside the payload limit" }
    }
}

/**
 * Strict, bounded JSON codec for one closed wire class.
 *
 * Parsing rejects trailing input, lenient JSON, unknown root fields, excessive
 * nesting/container growth and oversized strings before domain construction.
 * [validate] remains mandatory because JSON shape cannot express domain rules.
 */
class BoundedJsonCodec<T : Any>(
    private val gson: Gson,
    private val type: Class<T>,
    private val rootContract: JsonObjectContract,
    private val bounds: JsonResourceBounds,
    private val validate: (T) -> Unit,
) {
    fun encode(value: T): String {
        validate(value)
        val tree = gson.toJsonTree(value)
        validateTree(tree)
        rootContract.validate(tree)
        val encoded = gson.toJson(tree)
        require(encoded.length <= bounds.maxCharacters) { "Encoded wire JSON exceeds its configured size limit" }
        return encoded
    }

    fun decode(raw: String): T {
        require(raw.length in 2..bounds.maxCharacters) { "Wire JSON has an invalid size" }
        val reader = JsonReader(StringReader(raw)).apply { strictness = Strictness.STRICT }
        val tree = try {
            readBoundedTree(reader, depth = 1, nodeCount = NodeCount()).also {
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Wire JSON contains trailing input" }
            }
        } catch (failure: IOException) {
            throw IllegalArgumentException("Wire JSON is malformed", failure)
        }
        require(tree !is JsonNull) { "Wire JSON must not be null" }
        rootContract.validate(tree)
        val decoded = requireNotNull(gson.fromJson(tree, type)) { "Wire JSON decoded to null" }
        validate(decoded)
        return decoded
    }

    private fun readBoundedTree(reader: JsonReader, depth: Int, nodeCount: NodeCount): JsonElement {
        require(depth <= bounds.maxDepth) { "Wire JSON exceeds its configured depth limit" }
        nodeCount.value++
        require(nodeCount.value <= bounds.maxTotalNodes) { "Wire JSON exceeds its configured node limit" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().also { result ->
                reader.beginObject()
                var entries = 0
                while (reader.hasNext()) {
                    entries++
                    require(entries <= bounds.maxContainerEntries) { "Wire JSON object has too many entries" }
                    val key = reader.nextName()
                    require(key.length <= 64 && key.none(Char::isISOControl)) { "Wire JSON contains an unsafe field name" }
                    require(!result.has(key)) { "Wire JSON contains a duplicate object field" }
                    result.add(key, readBoundedTree(reader, depth + 1, nodeCount))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().also { result ->
                reader.beginArray()
                var entries = 0
                while (reader.hasNext()) {
                    entries++
                    require(entries <= bounds.maxContainerEntries) { "Wire JSON array has too many entries" }
                    result.add(readBoundedTree(reader, depth + 1, nodeCount))
                }
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString().also { value ->
                require(value.length <= bounds.maxStringCharacters) { "Wire JSON string exceeds its configured limit" }
            })
            JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> JsonNull.INSTANCE.also { reader.nextNull() }
            else -> throw IllegalArgumentException("Wire JSON contains an unexpected token")
        }
    }

    private fun validateTree(root: JsonElement) {
        var nodes = 0
        fun visit(element: JsonElement, depth: Int) {
            require(depth <= bounds.maxDepth) { "Wire JSON exceeds its configured depth limit" }
            nodes++
            require(nodes <= bounds.maxTotalNodes) { "Wire JSON exceeds its configured node limit" }
            when {
                element.isJsonObject -> {
                    val objectValue: JsonObject = element.asJsonObject
                    require(objectValue.size() <= bounds.maxContainerEntries) { "Wire JSON object has too many entries" }
                    objectValue.entrySet().forEach { (key, child) ->
                        require(key.length <= 64 && key.none(Char::isISOControl)) { "Wire JSON contains an unsafe field name" }
                        visit(child, depth + 1)
                    }
                }
                element.isJsonArray -> {
                    val array = element.asJsonArray
                    require(array.size() <= bounds.maxContainerEntries) { "Wire JSON array has too many entries" }
                    array.forEach { visit(it, depth + 1) }
                }
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                    require(element.asString.length <= bounds.maxStringCharacters) { "Wire JSON string exceeds its configured limit" }
                }
            }
        }
        visit(root, 1)
    }

    private class NodeCount(var value: Int = 0)
}
