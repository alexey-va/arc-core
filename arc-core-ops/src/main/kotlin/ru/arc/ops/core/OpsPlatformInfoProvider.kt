package ru.arc.ops.core

/** Platform metadata for GET /ops/info. */
data class OpsPlatformInfo(
    val platform: String,
    val serverName: String,
    val version: String,
)

fun interface OpsPlatformInfoProvider {
    fun info(): OpsPlatformInfo
}
