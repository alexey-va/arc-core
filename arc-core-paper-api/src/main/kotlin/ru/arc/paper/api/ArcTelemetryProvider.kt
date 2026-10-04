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

    /**
     * Records one domain activity using stable identifiers and scalar domain attributes.
     *
     * `source` and `event` must be stable, non-blank identifiers. `subject` and
     * `operationId` are optional stable identifiers; use `null` when absent. Attribute
     * values may describe identifiers, enums, counts, durations, or booleans. Do not
     * pass chat, raw command arguments, display text, or arbitrary payloads.
     *
     * Implementations must snapshot the attributes before returning and enqueue the
     * observation without blocking. `true` means only that the bounded asynchronous
     * capture queue accepted it; it does not guarantee SQL persistence or fsync. Any
     * asynchronous writer must not call back into consumers or access Bukkit off-thread.
     */
    fun recordActivity(
        playerId: UUID,
        source: String,
        event: String,
        subject: String?,
        operationId: String?,
        attributes: Map<String, String>,
    ): Boolean = false

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
