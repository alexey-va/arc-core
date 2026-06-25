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
    }
})
