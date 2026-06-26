package ru.arc.xaction

import java.util.UUID

/**
 * Platform-agnostic filter for cross-server message delivery.
 * Paper-specific evaluation lives in ARC (`XConditionExt.matches`).
 */
data class XCondition(
    val playerName: String? = null,
    val playerUuid: UUID? = null,
    val permission: String? = null,
    val serverName: String? = null,
    val placeholders: Map<String, String>? = null,
) {
    companion object {
        @JvmStatic
        fun ofPermission(permission: String) = XCondition(permission = permission)

        @JvmStatic
        fun ofServerName(serverName: String) = XCondition(serverName = serverName)

        @JvmStatic
        fun ofPlayerName(playerName: String) = XCondition(playerName = playerName)

        @JvmStatic
        fun ofPlayerUuid(playerUuid: UUID) = XCondition(playerUuid = playerUuid)
    }
}
