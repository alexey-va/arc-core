package ru.arc.metrics.core

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import ru.arc.redis.NoOpRedisTelemetrySink
import ru.arc.redis.RedisManager
import ru.arc.redis.RedisOperation
import ru.arc.redis.RedisOperationResult
import ru.arc.redis.RedisReconnectPath
import ru.arc.redis.RedisReconnectResult
import ru.arc.redis.RedisTelemetrySink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Event-driven Redis metrics. Operation/path/result labels are finite enums,
 * so this adds useful latency and failure signals without unbounded series.
 */
class RedisMetricsBinder(
    private val manager: RedisManager,
    private val registry: MeterRegistry,
) : RedisTelemetrySink,
    AutoCloseable {
    private data class OperationKey(
        val operation: RedisOperation,
        val result: RedisOperationResult,
    )

    private data class ReconnectKey(
        val path: RedisReconnectPath,
        val result: RedisReconnectResult,
    )

    private val operationCounters = ConcurrentHashMap<OperationKey, Counter>()
    private val operationTimers = ConcurrentHashMap<OperationKey, Timer>()
    private val reconnectCounters = ConcurrentHashMap<ReconnectKey, Counter>()

    init {
        manager.installTelemetry(this)
    }

    override fun onOperation(
        operation: RedisOperation,
        result: RedisOperationResult,
        durationNanos: Long,
    ) {
        val key = OperationKey(operation, result)
        operationCounters.computeIfAbsent(key, ::operationCounter).increment()
        operationTimers
            .computeIfAbsent(key, ::operationTimer)
            .record(durationNanos.coerceAtLeast(0L), TimeUnit.NANOSECONDS)
    }

    override fun onReconnect(
        path: RedisReconnectPath,
        result: RedisReconnectResult,
    ) {
        val key = ReconnectKey(path, result)
        reconnectCounters.computeIfAbsent(key, ::reconnectCounter).increment()
    }

    override fun close() {
        manager.installTelemetry(NoOpRedisTelemetrySink)
    }

    private fun operationCounter(key: OperationKey): Counter =
        Counter
            .builder("arc_redis_operations")
            .description("Redis operations by bounded operation and result")
            .tag("operation", key.operation.metricTag)
            .tag("result", key.result.metricTag)
            .register(registry)

    private fun operationTimer(key: OperationKey): Timer =
        Timer
            .builder("arc_redis_operation_duration")
            .description("Redis operation latency")
            .tag("operation", key.operation.metricTag)
            .tag("result", key.result.metricTag)
            .register(registry)

    private fun reconnectCounter(key: ReconnectKey): Counter =
        Counter
            .builder("arc_redis_reconnects")
            .description("Redis reconnect activity")
            .tag("path", key.path.metricTag)
            .tag("result", key.result.metricTag)
            .register(registry)
}
