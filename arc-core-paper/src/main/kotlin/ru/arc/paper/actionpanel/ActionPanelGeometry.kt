package ru.arc.paper.actionpanel

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import org.bukkit.util.BoundingBox
import org.joml.Vector3d
import ru.arc.paper.display.DisplayOrientedVolume

data class ActionPanelAnchor(val point: ActionPanelPoint, val yaw: Float) {
    init { require(yaw.isFinite()) }
}

data class ActionPanelBounds(
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double,
) {
    init {
        require(listOf(minX, minY, minZ, maxX, maxY, maxZ).all(Double::isFinite))
        require(minX <= maxX && minY <= maxY && minZ <= maxZ)
    }
    fun overlaps(other: ActionPanelBounds): Boolean = minX < other.maxX && maxX > other.minX &&
        minY < other.maxY && maxY > other.minY && minZ < other.maxZ && maxZ > other.minZ
}

/** Upright oriented volume; also useful for mixed ItemDisplay/TextDisplay assemblies. */
data class ActionPanelVolume(
    val center: ActionPanelPoint,
    val yaw: Float,
    val width: Double,
    val height: Double,
    val depth: Double,
) {
    init {
        require(yaw.isFinite())
        require(listOf(width, height, depth).all { it.isFinite() && it > 0.0 })
    }
}

data class ActionPanelPlane<A : Any>(
    val key: A,
    val center: ActionPanelPoint,
    val yaw: Float,
    val width: Double,
    val height: Double,
    val enabled: Boolean,
) {
    init {
        require(yaw.isFinite())
        require(width.isFinite() && width > 0.0 && height.isFinite() && height > 0.0)
    }
}

data class ActionPanelRayHit<A : Any>(val key: A, val point: ActionPanelPoint, val distance: Double)
data class ActionPanelShift(val side: Double, val up: Double) {
    init { require(side.isFinite() && up.isFinite()) }
}

/**
 * Pure geometry shared by action panels and specialised hologram assemblies. Rectangles describe
 * the actual rendered footprint, never an unscaled entity origin. These helpers do not read worlds,
 * infer permissions, create displays or decide whether a camera-following scene should move.
 */
object ActionPanelGeometry {
    const val SURFACE_CLEARANCE: Double = 0.04
    private const val EPSILON = 1e-8

    @JvmStatic fun distance(first: ActionPanelPoint, second: ActionPanelPoint): Double =
        sqrt((first.x - second.x).squared() + (first.y - second.y).squared() + (first.z - second.z).squared())

    @JvmStatic fun localToWorld(anchor: ActionPanelAnchor, local: ActionPanelPoint): ActionPanelPoint {
        val radians = Math.toRadians(anchor.yaw.toDouble())
        return ActionPanelPoint(anchor.point.x + cos(radians) * local.x + sin(radians) * local.z,
            anchor.point.y + local.y, anchor.point.z + sin(radians) * local.x - cos(radians) * local.z)
    }

    /** A disabled nearest visible plane blocks fall-through to a farther enabled action. */
    @JvmStatic fun <A : Any> nearestHit(
        origin: ActionPanelPoint,
        direction: ActionPanelPoint,
        candidates: Iterable<ActionPanelPlane<A>>,
        maxDistance: Double,
        blockDistance: Double?,
    ): ActionPanelRayHit<A>? {
        if (!maxDistance.isFinite() || maxDistance <= 0.0 ||
            (blockDistance != null && (!blockDistance.isFinite() || blockDistance < 0.0))) return null
        val length = sqrt(direction.x.squared() + direction.y.squared() + direction.z.squared())
        if (!length.isFinite() || length <= EPSILON) return null
        val rayX = direction.x / length
        val rayY = direction.y / length
        val rayZ = direction.z / length
        var nearest: Pair<ActionPanelPlane<A>, ActionPanelRayHit<A>>? = null
        candidates.forEach { plane ->
            val radians = Math.toRadians(plane.yaw.toDouble())
            val rightX = cos(radians)
            val rightZ = sin(radians)
            val denominator = rayX * -rightZ + rayZ * rightX
            if (abs(denominator) <= EPSILON) return@forEach
            val distance = ((plane.center.x - origin.x) * -rightZ + (plane.center.z - origin.z) * rightX) / denominator
            if (distance < 0.0 || distance > maxDistance ||
                (blockDistance != null && blockDistance <= distance + 1e-4) ||
                (nearest != null && distance >= nearest.second.distance)) return@forEach
            val hit = ActionPanelPoint(origin.x + rayX * distance, origin.y + rayY * distance, origin.z + rayZ * distance)
            val offsetX = (hit.x - plane.center.x) * rightX + (hit.z - plane.center.z) * rightZ
            if (abs(offsetX) > plane.width / 2.0 + EPSILON || abs(hit.y - plane.center.y) > plane.height / 2.0 + EPSILON) return@forEach
            nearest = plane to ActionPanelRayHit(plane.key, hit, distance)
        }
        return nearest?.takeIf { it.first.enabled }?.second
    }

    @JvmStatic fun <A : Any> nearestTarget(
        origin: ActionPanelPoint, direction: ActionPanelPoint, candidates: Iterable<ActionPanelPlane<A>>,
        maxDistance: Double, blockDistance: Double?,
    ): A? = nearestHit(origin, direction, candidates, maxDistance, blockDistance)?.key

    @JvmStatic fun corners(anchor: ActionPanelAnchor, rect: ActionPanelRect): List<ActionPanelPoint> =
        listOf(-1.0, 1.0).flatMap { x -> listOf(-1.0, 1.0).map { y ->
            localToWorld(anchor, ActionPanelPoint(rect.center.x + x * rect.width / 2.0,
                rect.center.y + y * rect.height / 2.0, rect.center.z))
        } }

    @JvmStatic fun allReachable(
        eye: ActionPanelPoint, anchor: ActionPanelAnchor, rects: List<ActionPanelRect>, reach: Double,
    ): Boolean = reach.isFinite() && reach > 0.0 && rects.all { rect ->
        corners(anchor, rect).all { distance(eye, it) <= reach + 1e-6 }
    }

    /** Shared placement formula for runtime and source-derived previews; no obstacle assumptions. */
    @JvmStatic fun followingAnchor(
        eye: ActionPanelPoint, yaw: Float, rectangles: List<ActionPanelRect>, distance: Double,
        sideOffset: Double, heightOffset: Double,
    ): ActionPanelAnchor {
        require(rectangles.isNotEmpty())
        require(yaw.isFinite() && listOf(distance, sideOffset, heightOffset).all(Double::isFinite))
        require(distance >= 0.0)
        val centerY = (rectangles.minOf { it.center.y - it.height / 2.0 } +
            rectangles.maxOf { it.center.y + it.height / 2.0 }) / 2.0
        val angle = Math.toRadians(yaw.toDouble())
        return ActionPanelAnchor(ActionPanelPoint(eye.x + sin(angle) * distance + cos(angle) * sideOffset,
            eye.y - centerY + heightOffset, eye.z - cos(angle) * distance + sin(angle) * sideOffset), yaw)
    }

    @JvmStatic fun volume(anchor: ActionPanelAnchor, rects: List<ActionPanelRect>, padding: Double): ActionPanelVolume {
        require(rects.isNotEmpty())
        require(padding.isFinite() && padding >= 0.0)
        val minX = rects.minOf { it.center.x - it.width / 2.0 }
        val maxX = rects.maxOf { it.center.x + it.width / 2.0 }
        val minY = rects.minOf { it.center.y - it.height / 2.0 }
        val maxY = rects.maxOf { it.center.y + it.height / 2.0 }
        val minZ = rects.minOf { it.center.z }
        val maxZ = rects.maxOf { it.center.z }
        return ActionPanelVolume(localToWorld(anchor, ActionPanelPoint((minX + maxX) / 2.0,
            (minY + maxY) / 2.0, (minZ + maxZ) / 2.0)), anchor.yaw,
            maxX - minX + 2 * padding, maxY - minY + 2 * padding, maxZ - minZ + 2 * max(padding, 0.001))
    }

    @JvmStatic fun bounds(volume: ActionPanelVolume): ActionPanelBounds {
        val radians = Math.toRadians(volume.yaw.toDouble())
        val x = abs(cos(radians)) * volume.width / 2.0 + abs(sin(radians)) * volume.depth / 2.0
        val z = abs(sin(radians)) * volume.width / 2.0 + abs(cos(radians)) * volume.depth / 2.0
        val c = volume.center
        return ActionPanelBounds(c.x - x, c.y - volume.height / 2.0, c.z - z,
            c.x + x, c.y + volume.height / 2.0, c.z + z)
    }

    /** Separating-axis check against an actual block collision box, not its coarse block cube. */
    @JvmStatic fun overlaps(volume: ActionPanelVolume, box: ActionPanelBounds): Boolean {
        val angle = Math.toRadians(volume.yaw.toDouble())
        return DisplayOrientedVolume(Vector3d(volume.center.x, volume.center.y, volume.center.z),
            Vector3d(cos(angle), 0.0, sin(angle)), Vector3d(0.0, 1.0, 0.0),
            Vector3d(sin(angle), 0.0, -cos(angle)), Vector3d(volume.width / 2.0, volume.height / 2.0, volume.depth / 2.0))
            .overlaps(BoundingBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ))
    }

    /** Conservative swept volume for safe interpolation; retain handles and snap if this is blocked. */
    @JvmStatic fun sweptBounds(
        from: ActionPanelAnchor, to: ActionPanelAnchor, rects: List<ActionPanelRect>, padding: Double,
    ): ActionPanelBounds {
        val first = bounds(volume(from, rects, padding))
        val last = bounds(volume(to, rects, padding))
        val radius = rects.maxOf { hypot(abs(it.center.x) + it.width / 2.0 + padding, abs(it.center.z) + padding) }
        return sweptBounds(first, last, radius, yawDelta(from.yaw, to.yaw))
    }

    /** Also accepts existing mixed-scene bounds and its radius about the actual authored pivot. */
    @JvmStatic fun sweptBounds(
        first: ActionPanelBounds, last: ActionPanelBounds, horizontalRadius: Double, yawDelta: Double,
    ): ActionPanelBounds {
        require(horizontalRadius.isFinite() && horizontalRadius >= 0.0)
        require(yawDelta.isFinite() && yawDelta in 0.0..180.0)
        val bow = horizontalRadius * (1.0 - cos(Math.toRadians(yawDelta) / 2.0))
        return ActionPanelBounds(min(first.minX, last.minX) - bow, min(first.minY, last.minY), min(first.minZ, last.minZ) - bow,
            max(first.maxX, last.maxX) + bow, max(first.maxY, last.maxY), max(first.maxZ, last.maxZ) + bow)
    }

    @JvmStatic fun yawDelta(first: Float, second: Float): Double =
        abs((((second.toDouble() - first.toDouble()) % 360.0 + 540.0) % 360.0) - 180.0)

    @JvmStatic fun gazeIntersects(
        eye: ActionPanelPoint, direction: ActionPanelPoint, anchor: ActionPanelAnchor,
        rects: List<ActionPanelRect>, marginDegrees: Double,
    ): Boolean {
        require(marginDegrees.isFinite() && marginDegrees in 0.0..<90.0)
        if (rects.isEmpty()) return false
        val volume = volume(anchor, rects, 0.0)
        val distance = distance(eye, volume.center)
        val padding = distance * tan(Math.toRadians(marginDegrees))
        val plane = ActionPanelPlane(Unit, volume.center, anchor.yaw, volume.width + 2 * padding,
            volume.height + 2 * padding, true)
        return nearestTarget(eye, direction, listOf(plane), distance + hypot(plane.width, plane.height), null) != null
    }

    /** Retain safe obstacle shifts and demand extra clearance before returning toward the centre. */
    @JvmStatic fun adaptiveShift(
        maximum: Double, previous: ActionPanelShift?, clear: (ActionPanelShift, Double) -> Boolean,
    ): ActionPanelShift? {
        require(maximum.isFinite() && maximum in 0.0..1.5)
        previous?.takeIf { abs(it.side) <= maximum && it.up in 0.0..maximum }?.takeIf { clear(it, 0.0) }?.let { old ->
            val length = hypot(old.side, old.up)
            if (length <= EPSILON) return old
            fun inward(step: Double): ActionPanelShift = if (length <= step) ActionPanelShift(0.0, 0.0)
                else ActionPanelShift(old.side * (length - step) / length, old.up * (length - step) / length)
            val next = inward(0.06)
            return if (clear(inward(0.18), 0.12) && clear(next, 0.0)) next else old
        }
        val center = ActionPanelShift(0.0, 0.0)
        if (clear(center, 0.0)) return center
        for (step in 1..ceil(maximum / 0.25).toInt()) {
            val amount = min(maximum, step * 0.25)
            for (candidate in listOf(ActionPanelShift(0.0, amount), ActionPanelShift(amount, 0.0), ActionPanelShift(-amount, 0.0))) {
                if (clear(candidate, 0.0)) return candidate
            }
        }
        return null
    }

    @JvmStatic fun stableDistance(maximum: Double, previous: Double?, clear: (Double) -> Boolean): Double? {
        require(maximum.isFinite() && maximum in 0.0..8.0)
        previous?.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(maximum)?.takeIf(clear)?.let { old ->
            val next = min(maximum, old + 0.06)
            return if (next > old && clear(min(maximum, old + 0.18)) && clear(next)) next else old
        }
        return maximum.takeIf(clear)
    }

    /** Bounded linear fallback also finds non-monotonic safe gaps missed by binary distance search. */
    @JvmStatic fun firstSafeDistance(maximum: Double, minimum: Double, clear: (Double) -> Boolean): Double? {
        require(maximum.isFinite() && minimum.isFinite() && minimum > 0.0 && maximum in minimum..8.0)
        var distance = maximum
        while (true) {
            if (clear(distance)) return distance
            if (distance <= minimum + EPSILON) return null
            distance = max(minimum, distance - 0.1)
        }
    }

    private fun Double.squared(): Double = this * this
}
