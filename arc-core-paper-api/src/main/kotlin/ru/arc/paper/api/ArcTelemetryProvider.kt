package ru.arc.paper.api

import java.util.UUID

/** Optional telemetry sink registered at runtime by the ARC plugin. */
interface ArcTelemetryProvider {
    fun record(
        playerId: UUID,
        source: String,
        feature: String? = null,
        outcome: String? = null,
        action: String? = null,
        operationId: String,
    ): Boolean = false

    fun recordEvent(playerId: UUID, source: String, event: String, operationId: String): Boolean = false
    fun recordJobWork(playerId: UUID, job: String): Boolean = false
    fun breakJobWork(playerId: UUID) = Unit
    fun markJobReward(playerId: UUID, job: String, amount: Double): String? = null
    fun markExternalReward(
        playerId: UUID,
        source: String,
        action: String,
        amount: Double,
        currency: String?,
        rewardId: String?,
    ): String? = null
    fun cancelAudit(playerId: UUID, token: String?) = Unit
    fun observeUi(payload: Map<String, Any>) = Unit
}
