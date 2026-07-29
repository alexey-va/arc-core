package ru.arc.metrics.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.mockk.mockk
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import redis.clients.jedis.JedisPooled
import ru.arc.redis.RedisConnection
import ru.arc.redis.RedisManager
import ru.arc.redis.RedisOperation
import ru.arc.redis.RedisOperationResult
import ru.arc.redis.RedisReconnectPath
import ru.arc.redis.RedisReconnectResult
import ru.arc.redis.ServerIdentity

class RedisMetricsBinderTest :
    FreeSpec({
        "records bounded operation latency failures and reconnect outcomes" {
            val pools =
                ArrayDeque(
                    listOf(
                        mockk<JedisPooled>(relaxed = true),
                        mockk<JedisPooled>(relaxed = true),
                    ),
                )
            val manager =
                RedisManager(
                    RedisConnection("localhost", 6379),
                    ServerIdentity { "test" },
                    poolFactory = { pools.removeFirst() },
                )
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val binder = RedisMetricsBinder(manager, registry)

            binder.onOperation(
                RedisOperation.LOAD_MAP,
                RedisOperationResult.SUCCESS,
                25_000_000,
            )
            binder.onOperation(
                RedisOperation.LOAD_MAP,
                RedisOperationResult.FAILURE,
                10_000_000,
            )
            binder.onReconnect(
                RedisReconnectPath.PUBLISH,
                RedisReconnectResult.ATTEMPT,
            )

            registry
                .get("arc_redis_operations")
                .tags("operation", "load_map", "result", "success")
                .counter()
                .count() shouldBeExactly 1.0
            registry
                .get("arc_redis_operation_duration")
                .tags("operation", "load_map", "result", "success")
                .timer()
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) shouldBeExactly 25.0
            registry
                .get("arc_redis_reconnects")
                .tags("path", "publish", "result", "attempt")
                .counter()
                .count() shouldBeExactly 1.0

            binder.close()
            manager.close()
        }
    })
