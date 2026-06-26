package ru.arc.redis.gson

import com.google.gson.Gson
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

class PolymorphismAdapter<T> : JsonSerializer<T>, JsonDeserializer<T> {

    private val gson = Gson()

    override fun deserialize(json: JsonElement, type: Type, context: JsonDeserializationContext): T {
        try {
            val typeClass = extract(type as Class<*>)
            val jsonType = typeClass.getDeclaredAnnotation(JsonType::class.java)
                ?: throw JsonParseException("No @JsonType on $typeClass")
            val property = json.asJsonObject.get(jsonType.property).asString
            val subType = jsonType.subtypes.firstOrNull { it.name == property }?.clazz?.java
                ?: throw IllegalArgumentException("No subtype for property '$property'")
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(json, subType) as T
        } catch (e: Exception) {
            throw JsonParseException("Failed deserialize json", e)
        }
    }

    override fun serialize(t: T, type: Type, context: JsonSerializationContext): JsonElement {
        val parentClass = extract(type as Class<*>)
        val jsonType = parentClass.getDeclaredAnnotation(JsonType::class.java)
            ?: return gson.toJsonTree(t)
        val property = jsonType.property
        for (subtype in jsonType.subtypes) {
            if (subtype.clazz.java.isInstance(t)) {
                val jsonObject = gson.toJsonTree(t).asJsonObject
                jsonObject.addProperty(property, subtype.name)
                return jsonObject
            }
        }
        return gson.toJsonTree(t)
    }

    private fun extract(type: Class<*>): Class<*> {
        var current = type
        while (!current.isAnnotationPresent(JsonType::class.java)) {
            current = current.superclass ?: break
        }
        return current
    }
}
