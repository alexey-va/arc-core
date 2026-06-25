package ru.arc.ai.tools

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import org.slf4j.LoggerFactory
import ru.arc.ai.config.LlmModuleConfig
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations

class ToolRpcServer(
    private val localServerName: String,
    private val redis: RedisOperations,
    private val config: LlmModuleConfig,
    private val executors: Map<String, ToolExecutor>,
) : ChannelListener {

    private val gson = Gson()
    private val log = LoggerFactory.getLogger(ToolRpcServer::class.java)

    fun start() {
        redis.registerChannelUnique(config.toolInvokeChannel, this)
    }

    override fun consume(channel: String, message: String, originServer: String) {
        val request =
            runCatching { gson.fromJson(message, ToolInvokeRequest::class.java) }.getOrNull() ?: return
        if (!shouldHandle(request)) return

        val executor = executors[request.tool.lowercase()]
        if (executor == null) {
            publishError(request, "Unknown tool: ${request.tool}")
            return
        }

        val result =
            runCatching { executor.execute(request.payload) }
                .fold(
                    onSuccess = { value ->
                        ToolInvokeResponse(
                            id = request.id,
                            serverName = localServerName,
                            result = gson.toJson(value),
                        )
                    },
                    onFailure = { e ->
                        log.error("Tool {} failed on {}", request.tool, localServerName, e)
                        ToolInvokeResponse(
                            id = request.id,
                            serverName = localServerName,
                            error = e.message ?: e.javaClass.simpleName,
                        )
                    },
                )

        redis.publish(config.toolResultChannel, gson.toJson(result))
    }

    private fun shouldHandle(request: ToolInvokeRequest): Boolean {
        val targets = request.targetServers?.toSet()
        if (targets == null) return true
        if (targets.contains("__none__")) return false
        return targets.contains(localServerName)
    }

    private fun publishError(request: ToolInvokeRequest, error: String) {
        val response =
            ToolInvokeResponse(
                id = request.id,
                serverName = localServerName,
                error = error,
            )
        redis.publish(config.toolResultChannel, gson.toJson(response))
    }
}

fun jsonString(value: String): JsonElement = JsonPrimitive(value)
