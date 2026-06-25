package ru.arc.ai.tools

import com.google.gson.Gson
import com.google.gson.JsonElement
import org.slf4j.LoggerFactory
import ru.arc.ai.config.LlmModuleConfig
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class ToolRpcClient(
    private val redis: RedisOperations,
    private val config: LlmModuleConfig,
    private val playerResolver: PlayerServerResolver? = null,
    private val expectedResponses: Int = 2,
) : ChannelListener {

    private val gson = Gson()
    private val pending = ConcurrentHashMap<UUID, PendingCall>()

    data class PendingCall(
        val future: CompletableFuture<ToolInvokeResult>,
        val result: ToolInvokeResult,
        val atLeastOne: Boolean,
        val expectedCount: Int,
        val timestamp: Long,
    )

    fun start() {
        redis.registerChannelUnique(config.toolResultChannel, this)
    }

    override fun consume(channel: String, message: String, originServer: String) {
        val response = gson.fromJson(message, ToolInvokeResponse::class.java) ?: return
        val pendingCall = pending[response.id] ?: return
        pendingCall.result.merge(response)

        val done =
            when {
                pendingCall.atLeastOne && pendingCall.result.serverResults.isNotEmpty() -> true
                pendingCall.result.serverResults.size + pendingCall.result.errors.size >= pendingCall.expectedCount -> true
                else -> false
            }

        if (done) {
            pendingCall.future.complete(pendingCall.result)
            pending.remove(response.id)
        }
    }

    fun invoke(
        tool: String,
        payload: JsonElement,
        routing: ToolRouting,
        atLeastOneResponse: Boolean = true,
    ): CompletableFuture<ToolInvokeResult> {
        trimTimedOut()
        val id = UUID.randomUUID()
        val targetServers = resolveTargets(routing)
        val expectedCount =
            when {
                atLeastOneResponse -> 1
                targetServers != null -> targetServers.size.coerceAtLeast(1)
                else -> expectedResponses
            }

        val request =
            ToolInvokeRequest(
                id = id,
                tool = tool,
                payload = payload,
                routing = ToolRoutingDto.from(routing),
                targetServers = targetServers,
                timeoutMs = config.toolDefaultTimeoutMs,
            )

        val future = CompletableFuture<ToolInvokeResult>()
        pending[id] =
            PendingCall(
                future = future,
                result = ToolInvokeResult(),
                atLeastOne = atLeastOneResponse,
                expectedCount = expectedCount,
                timestamp = System.currentTimeMillis(),
            )

        redis.publish(config.toolInvokeChannel, gson.toJson(request))
        return future
    }

    private fun resolveTargets(routing: ToolRouting): List<String>? =
        when (routing) {
            ToolRouting.Broadcast -> null
            is ToolRouting.TargetServer -> listOf(routing.serverName)
            is ToolRouting.ByPlayer -> {
                val server = playerResolver?.serverForPlayer(routing.playerName)
                if (server.isNullOrBlank()) listOf("__none__") else listOf(server)
            }
        }

    private fun trimTimedOut() {
        val now = System.currentTimeMillis()
        pending.entries.removeIf { (_, call) ->
            val expired = now - call.timestamp > config.toolDefaultTimeoutMs
            if (expired && !call.future.isDone) {
                call.future.complete(call.result)
            }
            expired || call.future.isDone
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ToolRpcClient::class.java)

        @JvmField
        var instance: ToolRpcClient? = null
    }
}
