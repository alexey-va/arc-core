package ru.arc.paper.display

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.sqrt

/** Immutable server-eye snapshot. It does not describe the client's FOV or third-person camera. */
internal data class PacketDisplayView(
    val x: Double, val y: Double, val z: Double,
    val directionX: Double, val directionY: Double, val directionZ: Double,
    val afk: Boolean = false,
) {
    /** A rear cone only; a generous sphere keeps large, rotated and billboard parts at the edge active. */
    fun behind(frame: PacketDisplayFrame, suspended: Boolean): Boolean {
        if (frame.attachmentVehicleId != null) return false
        val radius = frame.rearCullRadius() ?: return false
        val dx = frame.x - x
        val dy = frame.y - y
        val dz = frame.z - z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        val directionLength = sqrt(directionX * directionX + directionY * directionY + directionZ * directionZ)
        if (!distance.isFinite() || !directionLength.isFinite() || directionLength < 0.001 || distance <= radius + 0.75) return false
        val cosine = ((dx * directionX + dy * directionY + dz * directionZ) / (distance * directionLength))
            .coerceIn(-1.0, 1.0)
        val nearestAngle = acos(cosine) - asin((radius / distance).coerceIn(0.0, 1.0))
        return nearestAngle > Math.toRadians(if (suspended) 115.0 else 125.0)
    }
}

private fun PacketDisplayFrame.rearCullRadius(): Double? {
    val transform = metadata.transform
    val width = metadata.displayWidth.toDouble()
    val height = metadata.displayHeight.toDouble()
    // Arbitrary text/fonts need authored bounds. Ordinary items get a padded model envelope;
    // oversized custom resource-pack meshes must enlarge their authored display bounds.
    if (metadata.content is PacketDisplayContent.Text && (width <= 0.0 || height <= 0.0)) return null
    val sx = abs(transform.scale.x.toDouble())
    val sy = abs(transform.scale.y.toDouble())
    val sz = abs(transform.scale.z.toDouble())
    val translation = transform.translation
    val offset = sqrt(translation.x.toDouble() * translation.x + translation.y.toDouble() * translation.y +
        translation.z.toDouble() * translation.z)
    // The block's anchor is a corner. The authored native bounds extend upward from the anchor.
    val scale = max(sx, max(sy, sz))
    val geometry = when (metadata.content) {
        // rightRotation precedes scale: it can align the unit cube's diagonal with the largest axis.
        is PacketDisplayContent.Block -> sqrt(3.0) * scale
        is PacketDisplayContent.Item -> 1.5 * scale
        is PacketDisplayContent.Text -> 0.0
    }
    val authored = sqrt(width * width / 2.0 + height * height) * max(1.0, scale)
    val radius = offset + max(geometry, authored) + max(0.0, metadata.shadowRadius.toDouble())
    return radius.takeIf { it.isFinite() && sx.isFinite() && sy.isFinite() && sz.isFinite() && width.isFinite() && height.isFinite() }
}
