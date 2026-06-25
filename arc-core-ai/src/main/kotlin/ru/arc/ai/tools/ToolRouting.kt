package ru.arc.ai.tools

import com.google.gson.JsonElement

sealed interface ToolRouting {
    data object Broadcast : ToolRouting

    data class TargetServer(val serverName: String) : ToolRouting

    data class ByPlayer(val playerName: String) : ToolRouting
}

fun interface PlayerServerResolver {
    fun serverForPlayer(playerName: String): String?
}
