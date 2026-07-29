package ru.arc.redis

/** Finite operation set used by the optional metrics sink. */
enum class RedisOperation(val metricTag: String) {
    PUBLISH("publish"),
    SAVE_MAP("save_map"),
    SAVE_MAP_ENTRIES("save_map_entries"),
    LOAD_MAP("load_map"),
    LOAD_MAP_ENTRIES("load_map_entries"),
    HEALTH_CHECK("health_check"),
}

enum class RedisOperationResult(val metricTag: String) {
    SUCCESS("success"),
    FAILURE("failure"),
    UNAVAILABLE("unavailable"),
}

enum class RedisReconnectPath(val metricTag: String) {
    PUBLISH("publish"),
    SUBSCRIPTION("subscription"),
}

enum class RedisReconnectResult(val metricTag: String) {
    ATTEMPT("attempt"),
    SUCCESS("success"),
    FAILURE("failure"),
}

/** Optional, non-blocking observer. Metrics failures must never affect Redis. */
interface RedisTelemetrySink {
    fun onOperation(
        operation: RedisOperation,
        result: RedisOperationResult,
        durationNanos: Long,
    )

    fun onReconnect(
        path: RedisReconnectPath,
        result: RedisReconnectResult,
    )
}

object NoOpRedisTelemetrySink : RedisTelemetrySink {
    override fun onOperation(
        operation: RedisOperation,
        result: RedisOperationResult,
        durationNanos: Long,
    ) = Unit

    override fun onReconnect(
        path: RedisReconnectPath,
        result: RedisReconnectResult,
    ) = Unit
}
