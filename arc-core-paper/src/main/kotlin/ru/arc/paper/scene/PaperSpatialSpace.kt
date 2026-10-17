package ru.arc.paper.scene

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.joml.Vector3d
import ru.arc.paper.display.DisplayOrientedVolume
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/** Shared primary-thread, non-loading boundary for personal and public spatial surfaces. */
class PaperSpatialSpace {
    fun known(world: World, bounds: BoundingBox, viewer: Player? = null): Boolean {
        check(Bukkit.isPrimaryThread())
        if (viewer != null && viewer.world.uid != world.uid) return false
        if (bounds.minY < world.minHeight || bounds.maxY >= world.maxHeight) return false
        val minX = floor(bounds.minX + EPSILON).toInt() shr 4
        val maxX = floor(bounds.maxX - EPSILON).toInt() shr 4
        val minZ = floor(bounds.minZ + EPSILON).toInt() shr 4
        val maxZ = floor(bounds.maxZ - EPSILON).toInt() shr 4
        for (x in minX..max(minX, maxX)) for (z in minZ..max(minZ, maxZ)) {
            if (!world.isChunkLoaded(x, z) || (viewer != null && !viewer.isChunkSent(Chunk.getChunkKey(x, z)))) return false
        }
        return true
    }

    fun clear(world: World, volume: DisplayOrientedVolume, viewer: Player? = null): Boolean =
        clear(world, volume.bounds, viewer, volume::overlaps)

    /** A conservative swept envelope; callers must include rotation, not only endpoint translation. */
    fun clearBounds(world: World, bounds: BoundingBox, viewer: Player? = null): Boolean =
        clear(world, bounds, viewer, bounds::overlaps)

    private fun clear(world: World, bounds: BoundingBox, viewer: Player?, overlaps: (BoundingBox) -> Boolean): Boolean {
        if (!known(world, bounds, viewer)) return false
        val x0 = floor(bounds.minX + EPSILON).toInt(); val x1 = max(x0, ceil(bounds.maxX - EPSILON).toInt() - 1)
        val y0 = floor(bounds.minY + EPSILON).toInt(); val y1 = max(y0, ceil(bounds.maxY - EPSILON).toInt() - 1)
        val z0 = floor(bounds.minZ + EPSILON).toInt(); val z1 = max(z0, ceil(bounds.maxZ - EPSILON).toInt() - 1)
        for (x in x0..x1) for (y in y0..y1) for (z in z0..z1) {
            if (world.getBlockAt(x, y, z).collisionShape.boundingBoxes.any { overlaps(it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble())) }) return false
        }
        return true
    }

    fun rayClear(player: Player, from: Vector, to: Vector, endInset: Double = 0.0,
        blocksRay: (Block) -> Boolean = { true }): Boolean {
        require(endInset.isFinite() && endInset >= 0.0)
        val delta = to.clone().subtract(from)
        val distance = delta.length()
        if (!distance.isFinite() || distance <= EPSILON) return false
        val bounds = BoundingBox.of(from, to).expand(EPSILON)
        if (!known(player.world, bounds, player)) return false
        return player.world.rayTraceBlocks(from.toLocation(player.world), delta.multiply(1.0 / distance),
            max(0.0, distance - endInset), FluidCollisionMode.NEVER, true, blocksRay) == null
    }

    fun visible(player: Player, from: Vector, samples: List<Vector>, endInset: Double = 0.025,
        blocksRay: (Block) -> Boolean = { true }): Boolean = samples.isNotEmpty() && samples.all {
        rayClear(player, from, it, endInset, blocksRay)
    }

    /** Deterministic world collision escape. Never derives a public scene's anchor from a visitor. */
    fun place(surface: AnchoredSurface, previous: Location? = null, viewer: Player? = null): Location? {
        val authored = surface.location()
        val world = checkNotNull(authored.world)
        val angle = Math.toRadians(authored.yaw.toDouble())
        val outward = Vector3d(-sin(angle), 0.0, cos(angle))
        val right = Vector3d(cos(angle), 0.0, sin(angle))
        fun delta(out: Double, up: Double, side: Double) = Vector3d(outward).mul(out).add(Vector3d(right).mul(side)).add(0.0, up, 0.0)
        fun safe(offset: Vector3d, clearance: Double) = surface.footprints.all { clear(world, it.translated(offset).expanded(clearance), viewer) }
        val zero = Vector3d()
        if (safe(zero, if (previous != null && previous != authored) surface.returnClearance else 0.0)) return authored
        if (previous != null && previous.world?.uid == world.uid && previous.yaw == authored.yaw && previous.pitch == authored.pitch) {
            val shift = Vector3d(previous.x - authored.x, previous.y - authored.y, previous.z - authored.z)
            if (shift.dot(outward) in 0.0..surface.maxOutward && shift.y in 0.0..surface.maxUp &&
                kotlin.math.abs(shift.dot(right)) <= surface.maxSide && safe(shift, 0.0)) return previous.clone()
        }
        val outwardSteps = steps(surface.maxOutward, surface.step)
        val upSteps = steps(surface.maxUp, surface.step)
        val sideSteps = steps(surface.maxSide, surface.step).flatMap { if (it == 0.0) listOf(0.0) else listOf(it, -it) }
        for (out in outwardSteps) for (up in upSteps) for (side in sideSteps) {
            val shift = delta(out, up, side)
            if (safe(shift, 0.0)) return authored.clone().add(shift.x, shift.y, shift.z)
        }
        return null
    }
    private fun steps(maximum: Double, step: Double): List<Double> =
        (0..floor(maximum / step).toInt()).map { it * step }.let { if (it.last() < maximum) it + maximum else it }
    private companion object { const val EPSILON = 1e-5 }
}

/** Visual volumes are explicit and independent of native hitboxes and display culling dimensions. */
class AnchoredSurface(location: Location, footprints: List<DisplayOrientedVolume>,
    val maxOutward: Double = 1.0, val maxUp: Double = 0.5, val maxSide: Double = 0.5,
    val step: Double = 0.25, val returnClearance: Double = 0.08) {
    private val at = ScenePose(location).location()
    val footprints = footprints.toList()
    init {
        require(footprints.isNotEmpty() && footprints.size <= 32)
        require(listOf(maxOutward, maxUp, maxSide, returnClearance).all { it.isFinite() && it in 0.0..2.0 })
        require(step.isFinite() && step in 0.1..1.0)
        require((ceil(maxOutward / step) + 1) * (ceil(maxUp / step) + 1) * (2 * ceil(maxSide / step) + 1) <= 512)
    }
    fun location(): Location = at.clone()
}
