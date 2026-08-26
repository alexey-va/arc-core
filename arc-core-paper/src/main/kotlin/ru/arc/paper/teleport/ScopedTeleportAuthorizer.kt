package ru.arc.paper.teleport

import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class TeleportMatchTolerance(
    val coordinate: Double = 0.01,
    val angle: Float = 0.1f,
) {
    init {
        require(coordinate.isFinite() && coordinate >= 0.0) { "Teleport coordinate tolerance must be finite and non-negative" }
        require(angle.isFinite() && angle >= 0f) { "Teleport angle tolerance must be finite and non-negative" }
    }
}

/**
 * Authorizes one exact plugin-owned teleport only for the dynamic extent of
 * [authorize]. Nested authorization for the same player is rejected and
 * cleanup runs even when the teleport action throws.
 */
class ScopedTeleportAuthorizer(
    private val tolerance: TeleportMatchTolerance = TeleportMatchTolerance(),
) {
    private val expectedByPlayer = ConcurrentHashMap<UUID, ExpectedTeleport>()

    fun <T> authorize(playerId: UUID, destination: Location, action: () -> T): T {
        val expected = ExpectedTeleport.from(destination)
        check(expectedByPlayer.putIfAbsent(playerId, expected) == null) {
            "A teleport is already authorized for player $playerId"
        }
        return try {
            action()
        } finally {
            expectedByPlayer.remove(playerId, expected)
        }
    }

    fun isAuthorized(playerId: UUID, destination: Location?): Boolean =
        destination != null && expectedByPlayer[playerId]?.matches(destination, tolerance) == true

    internal fun activeAuthorizationCount(): Int = expectedByPlayer.size

    private data class ExpectedTeleport(
        val worldId: UUID,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
    ) {
        fun matches(location: Location, tolerance: TeleportMatchTolerance): Boolean {
            val world = location.world ?: return false
            return world.uid == worldId &&
                abs(location.x - x) <= tolerance.coordinate &&
                abs(location.y - y) <= tolerance.coordinate &&
                abs(location.z - z) <= tolerance.coordinate &&
                abs(location.yaw - yaw) <= tolerance.angle &&
                abs(location.pitch - pitch) <= tolerance.angle
        }

        companion object {
            fun from(location: Location): ExpectedTeleport {
                val world = requireNotNull(location.world) { "Teleport destination must have a world" }
                require(listOf(location.x, location.y, location.z).all(Double::isFinite)) {
                    "Teleport destination coordinates must be finite"
                }
                require(location.yaw.isFinite() && location.pitch.isFinite()) { "Teleport destination angles must be finite" }
                return ExpectedTeleport(world.uid, location.x, location.y, location.z, location.yaw, location.pitch)
            }
        }
    }
}
