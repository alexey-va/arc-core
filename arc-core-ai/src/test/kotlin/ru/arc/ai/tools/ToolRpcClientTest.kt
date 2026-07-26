package ru.arc.ai.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.ai.config.TestLlmModuleConfig
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations
import ru.arc.redis.ServerIdentity
import java.util.UUID
import java.util.concurrent.TimeUnit

class ToolRpcClientTest : FreeSpec({
    "ToolRpc" - {
        "should round-trip invoke request json" {
            val gson = Gson()
            val id = UUID.randomUUID()
            val original =
                ToolInvokeRequest(
                    id = id,
                    tool = ToolNames.GET_PLAYER_INFO,
                    payload = JsonObject().apply { add("player", JsonPrimitive("Steve")) },
                    routing = ToolRoutingDto.from(ToolRouting.TargetServer("spawn")),
                    targetServers = listOf("spawn"),
                )
            val parsed = gson.fromJson(gson.toJson(original), ToolInvokeRequest::class.java)
            parsed.id shouldBe id
            parsed.tool shouldBe ToolNames.GET_PLAYER_INFO
            parsed.targetServers shouldBe listOf("spawn")
        }

        "should merge response payload" {
            val result = ToolInvokeResult()
            result.merge(ToolInvokeResponse(UUID.randomUUID(), "spawn", "\"ok\""))
            result.serverResults["spawn"] shouldBe "\"ok\""
        }

        "should complete an unanswered call at its configured timeout" {
            val redis = InMemoryRedis(ServerIdentity { "proxy" })
            val client =
                ToolRpcClient(
                    redis = redis,
                    config = TestLlmModuleConfig(toolDefaultTimeoutMs = 25),
                    expectedResponses = 1,
                )
            client.start()

            val result =
                client.invoke(
                    tool = ToolNames.GET_PLAYER_INFO,
                    payload = JsonObject(),
                    routing = ToolRouting.TargetServer("missing"),
                    atLeastOneResponse = false,
                ).get(1, TimeUnit.SECONDS)

            result.serverResults shouldBe emptyMap()
            client.close()
        }

        "close should complete pending calls and reject new ones" {
            val redis = InMemoryRedis(ServerIdentity { "proxy" })
            val client =
                ToolRpcClient(
                    redis = redis,
                    config = TestLlmModuleConfig(toolDefaultTimeoutMs = 10_000),
                    expectedResponses = 1,
                )
            client.start()
            val pending =
                client.invoke(
                    tool = ToolNames.GET_PLAYER_INFO,
                    payload = JsonObject(),
                    routing = ToolRouting.TargetServer("missing"),
                    atLeastOneResponse = false,
                )

            client.close()

            pending.get(1, TimeUnit.SECONDS).serverResults shouldBe emptyMap()
            client.hasActiveTimeoutScheduler() shouldBe false
            client.invoke(
                tool = ToolNames.GET_PLAYER_INFO,
                payload = JsonObject(),
                routing = ToolRouting.TargetServer("missing"),
            ).isCompletedExceptionally shouldBe true
        }

        "invoke before start should fail immediately" {
            val client =
                ToolRpcClient(
                    redis = InMemoryRedis(ServerIdentity { "proxy" }),
                    config = TestLlmModuleConfig(),
                )

            client.invoke(
                tool = ToolNames.GET_PLAYER_INFO,
                payload = JsonObject(),
                routing = ToolRouting.Broadcast,
            ).isCompletedExceptionally shouldBe true
            client.close()
        }

        "completed call should cancel its timeout task" {
            val redis = InMemoryRedis(ServerIdentity { "proxy" })
            val config = TestLlmModuleConfig(toolDefaultTimeoutMs = 10_000)
            val server =
                ToolRpcServer(
                    localServerName = "spawn",
                    redis = redis,
                    config = config,
                    executors = mapOf("echo" to ToolExecutor { JsonPrimitive("ok") }),
                )
            val client =
                ToolRpcClient(
                    redis = redis,
                    config = config,
                    expectedResponses = 1,
                )
            server.start()
            client.start()

            client.invoke(
                tool = "echo",
                payload = JsonObject(),
                routing = ToolRouting.TargetServer("spawn"),
            ).get(1, TimeUnit.SECONDS).serverResults["spawn"] shouldBe "\"ok\""

            client.close()
            server.close()
            client.hasActiveTimeoutScheduler() shouldBe false
        }

        "failed channel registration should allow start retry" {
            val redis = mockk<RedisOperations>(relaxed = true)
            var registrations = 0
            every { redis.registerChannelUnique(any(), any()) } answers {
                registrations++
                if (registrations == 1) throw IllegalStateException("registration failed")
            }
            val client = ToolRpcClient(redis, TestLlmModuleConfig())

            shouldThrow<IllegalStateException> { client.start() }
            client.start()

            verify(exactly = 2) { redis.registerChannelUnique(any(), client) }
            client.close()
        }
    }
})
