package ru.arc.paper.display

import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.joml.Intersectiond
import org.joml.Vector3d
import kotlin.math.abs

/**
 * Copied arbitrary-basis display volume. Use the actual camera/model basis and mesh half-size.
 * A translated sweep preserves orientation; an orientation change also needs a rotational swept
 * envelope (or a conservative caller check) before interpolating. No world state is read here.
 */
class DisplayOrientedVolume(center: Vector3d, right: Vector3d, up: Vector3d, normal: Vector3d, halfSize: Vector3d) {
    private val center = Vector3d(center)
    private val right = Vector3d(right)
    private val up = Vector3d(up)
    private val normal = Vector3d(normal)
    private val halfSize = Vector3d(halfSize)
    private val envelope: BoundingBox
    init {
        require(listOf(center, right, up, normal, halfSize).all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        require(halfSize.x > 0.0 && halfSize.y > 0.0 && halfSize.z > 0.0)
        require(listOf(right, up, normal).all { abs(it.lengthSquared() - 1.0) <= 1e-5 }) { "Display basis must be normalized" }
        require(abs(right.dot(up)) <= 1e-5 && abs(right.dot(normal)) <= 1e-5 && abs(up.dot(normal)) <= 1e-5) {
            "Display basis must be orthogonal"
        }
        val x = abs(right.x) * halfSize.x + abs(up.x) * halfSize.y + abs(normal.x) * halfSize.z
        val y = abs(right.y) * halfSize.x + abs(up.y) * halfSize.y + abs(normal.y) * halfSize.z
        val z = abs(right.z) * halfSize.x + abs(up.z) * halfSize.y + abs(normal.z) * halfSize.z
        envelope = BoundingBox(center.x - x, center.y - y, center.z - z, center.x + x, center.y + y, center.z + z)
    }

    val bounds: BoundingBox get() = envelope.clone()

    fun translated(delta: Vector3d): DisplayOrientedVolume = DisplayOrientedVolume(
        Vector3d(center).add(delta), right, up, normal, halfSize,
    )

    fun expanded(clearance: Double): DisplayOrientedVolume {
        require(clearance.isFinite() && clearance >= 0.0)
        return DisplayOrientedVolume(center, right, up, normal, Vector3d(halfSize).add(clearance, clearance, clearance))
    }

    fun swept(delta: Vector3d): DisplayOrientedVolume = DisplayOrientedVolume(
        Vector3d(center).add(Vector3d(delta).mul(0.5)), right, up, normal,
        Vector3d(halfSize).add(abs(right.dot(delta)) / 2.0, abs(up.dot(delta)) / 2.0, abs(normal.dot(delta)) / 2.0),
    )

    fun overlaps(block: BoundingBox): Boolean = envelope.overlaps(block) && Intersectiond.testObOb(
        center, right, up, normal, halfSize,
        Vector3d(block.centerX, block.centerY, block.centerZ), X, Y, Z,
        Vector3d(block.widthX / 2.0, block.height / 2.0, block.widthZ / 2.0),
    )

    override fun equals(other: Any?): Boolean = other is DisplayOrientedVolume && center == other.center &&
        right == other.right && up == other.up && normal == other.normal && halfSize == other.halfSize
    override fun hashCode(): Int = listOf(center, right, up, normal, halfSize).hashCode()

    private companion object {
        val X = Vector3d(1.0, 0.0, 0.0)
        val Y = Vector3d(0.0, 1.0, 0.0)
        val Z = Vector3d(0.0, 0.0, 1.0)
    }
}

/** Pure helpers for camera cards, item grids and distance-compensated targeting proxies. */
object DisplayGeometry {
    /** Center followed by all four corners of the actual scaled rendered rectangle. */
    @JvmStatic fun rectangleSamples(
        center: Vector, right: Vector, up: Vector, halfWidth: Double, halfHeight: Double,
    ): List<Vector> {
        require(listOf(center, right, up).all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        require(halfWidth.isFinite() && halfWidth >= 0.0 && halfHeight.isFinite() && halfHeight >= 0.0)
        return listOf(center.clone()) + listOf(-1.0, 1.0).flatMap { x -> listOf(-1.0, 1.0).map { y ->
            center.clone().add(right.clone().multiply(x * halfWidth)).add(up.clone().multiply(y * halfHeight))
        } }
    }

    @JvmStatic fun clipDistance(desired: Double, obstruction: Double?, clearance: Double, minimum: Double): Double {
        require(desired.isFinite() && desired > 0.0 && clearance.isFinite() && clearance >= 0.0)
        require(minimum.isFinite() && minimum > 0.0)
        require(obstruction == null || (obstruction.isFinite() && obstruction >= 0.0))
        return if (obstruction == null) desired else (obstruction - clearance).coerceAtLeast(minimum).coerceAtMost(desired)
    }

    /** Keep apparent angular size when moving a proxy from actualDistance to displayDistance. */
    @JvmStatic fun distanceCompensatedScale(baseScale: Float, actualDistance: Double, displayDistance: Double): Float {
        return distanceCompensatedScale(baseScale.toDouble(), actualDistance, displayDistance)
    }

    @JvmStatic fun distanceCompensatedScale(baseScale: Double, actualDistance: Double, displayDistance: Double): Float {
        require(baseScale.isFinite() && baseScale >= 0.0)
        require(actualDistance.isFinite() && actualDistance > 0.0 && displayDistance.isFinite() && displayDistance >= 0.0)
        val result = baseScale * (displayDistance / actualDistance)
        require(result.isFinite() && result <= Float.MAX_VALUE)
        return result.toFloat()
    }
}
