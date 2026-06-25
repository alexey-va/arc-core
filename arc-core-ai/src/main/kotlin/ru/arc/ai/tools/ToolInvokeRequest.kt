package ru.arc.ai.tools

import com.google.gson.JsonElement
import java.util.UUID

data class ToolInvokeRequest(
    @JvmField val id: UUID,
    @JvmField val tool: String,
    @JvmField val payload: JsonElement,
    @JvmField val routing: ToolRoutingDto,
    @JvmField val targetServers: List<String>? = null,
    @JvmField val timeoutMs: Long = 30_000,
)

data class ToolRoutingDto(
    val type: String,
    val playerName: String? = null,
    val serverName: String? = null,
) {
    companion object {
        fun from(routing: ToolRouting): ToolRoutingDto =
            when (routing) {
                ToolRouting.Broadcast -> ToolRoutingDto("broadcast")
                is ToolRouting.ByPlayer -> ToolRoutingDto("by_player", playerName = routing.playerName)
                is ToolRouting.TargetServer -> ToolRoutingDto("target_server", serverName = routing.serverName)
            }

        fun toRouting(dto: ToolRoutingDto): ToolRouting =
            when (dto.type.lowercase()) {
                "by_player" -> ToolRouting.ByPlayer(dto.playerName ?: "")
                "target_server" -> ToolRouting.TargetServer(dto.serverName ?: "")
                else -> ToolRouting.Broadcast
            }
    }
}

data class ToolInvokeResponse(
    @JvmField val id: UUID,
    @JvmField val serverName: String,
    @JvmField val result: String? = null,
    @JvmField val error: String? = null,
)

class ToolInvokeResult {
    val serverResults: MutableMap<String, String> = LinkedHashMap()
    val errors: MutableMap<String, String> = LinkedHashMap()

    fun merge(response: ToolInvokeResponse) {
        if (response.error != null) {
            errors[response.serverName] = response.error
        } else if (response.result != null) {
            serverResults[response.serverName] = response.result
        }
    }
}

fun interface ToolExecutor {
    fun execute(payload: JsonElement): JsonElement
}
