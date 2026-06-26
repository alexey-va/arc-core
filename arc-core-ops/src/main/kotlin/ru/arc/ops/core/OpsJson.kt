package ru.arc.ops.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder

object OpsJson {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun toJson(value: Any?): String = gson.toJson(value)

    fun ok(data: Map<String, Any?> = emptyMap()): String =
        toJson(linkedMapOf("ok" to true) + data)

    fun error(
        status: Int,
        message: String,
        extra: Map<String, Any?> = emptyMap(),
    ): Pair<Int, String> {
        val body = linkedMapOf<String, Any?>("ok" to false, "error" to message, "status" to status)
        body.putAll(extra)
        return status to toJson(body)
    }

    fun notSupported(capability: String, platform: String): Pair<Int, String> =
        error(
            501,
            "not_supported",
            mapOf("capability" to capability, "platform" to platform),
        )

    fun parseCommand(body: String): String? {
        if (body.isBlank()) return null
        @Suppress("UNCHECKED_CAST")
        val map = gson.fromJson(body, Map::class.java) as? Map<String, Any?> ?: return null
        return map["command"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
