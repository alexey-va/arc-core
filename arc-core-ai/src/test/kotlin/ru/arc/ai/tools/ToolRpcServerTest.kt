package ru.arc.ai.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.config.TestLlmModuleConfig
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import java.util.UUID

class ToolRpcServerTest : FreeSpec({
    "ToolRpcServer lifecycle" - {
        val config = TestLlmModuleConfig()

        "start and close should be idempotent and unregister listener" {
            val redis = InMemoryRedis(ServerIdentity { "spawn" })
            val server = toolServer(redis, config)

            server.start()
            server.start()
            redis.listenerCount(config.toolInvokeChannel) shouldBe 1

            server.close()
            server.close()
            redis.listenerCount(config.toolInvokeChannel) shouldBe 0
        }

        "closed server should not process stale deliveries" {
            val redis = InMemoryRedis(ServerIdentity { "spawn" })
            val server = toolServer(redis, config)
            server.start()
            server.close()

            server.consume(config.toolInvokeChannel, requestJson(targets = null), "proxy")

            redis.getPublishedMessages().filter { it.channel == config.toolResultChannel } shouldBe emptyList()
        }
    }

    "ToolRpcServer targeting" - {
        val config = TestLlmModuleConfig()

        "should normalize target server names" {
            val redis = InMemoryRedis(ServerIdentity { "spawn" })
            val server = toolServer(redis, config)
            server.start()

            server.consume(
                config.toolInvokeChannel,
                requestJson(targets = listOf("  SPAWN  ")),
                "proxy",
            )

            redis.getPublishedMessages().count { it.channel == config.toolResultChannel } shouldBe 1
            server.close()
        }

        "should ignore explicit no-target sentinel" {
            val redis = InMemoryRedis(ServerIdentity { "spawn" })
            val server = toolServer(redis, config)
            server.start()

            server.consume(
                config.toolInvokeChannel,
                requestJson(targets = listOf("__none__")),
                "proxy",
            )

            redis.getPublishedMessages().none { it.channel == config.toolResultChannel } shouldBe true
            server.close()
        }
    }
})

private fun toolServer(redis: InMemoryRedis, config: TestLlmModuleConfig): ToolRpcServer =
    ToolRpcServer(
        localServerName = "spawn",
        redis = redis,
        config = config,
        executors = mapOf("echo" to ToolExecutor { JsonPrimitive("ok") }),
    )

private fun requestJson(targets: List<String>?): String =
    Gson().toJson(
        ToolInvokeRequest(
            id = UUID.randomUUID(),
            tool = "echo",
            payload = JsonObject(),
            routing = ToolRoutingDto.from(ToolRouting.Broadcast),
            targetServers = targets,
        ),
    )
