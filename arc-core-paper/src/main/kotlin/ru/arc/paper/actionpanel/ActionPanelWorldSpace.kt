package ru.arc.paper.actionpanel

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.joml.Vector3d
import ru.arc.paper.display.DisplayOrientedVolume
import ru.arc.paper.scene.PaperSpatialSpace
import kotlin.math.cos
import kotlin.math.sin

/** Personal-panel policy over the shared exact, non-loading spatial boundary. */
internal class ActionPanelWorldSpace(private val space: PaperSpatialSpace = PaperSpatialSpace()) {
    fun safe(player: Player, eye: ActionPanelPoint, anchor: ActionPanelAnchor, rectangles: List<ActionPanelRect>,
        reach: Double, clearance: Double, lineOfSight: Boolean, blocksRay: (Block) -> Boolean): Boolean {
        if (rectangles.isEmpty() || !ActionPanelGeometry.allReachable(eye, anchor, rectangles, reach)) return false
        val volume = ActionPanelGeometry.volume(anchor, rectangles, ActionPanelGeometry.SURFACE_CLEARANCE + clearance)
        val angle = Math.toRadians(volume.yaw.toDouble())
        val oriented = DisplayOrientedVolume(Vector3d(volume.center.x, volume.center.y, volume.center.z),
            Vector3d(cos(angle), 0.0, sin(angle)), Vector3d(0.0, 1.0, 0.0), Vector3d(sin(angle), 0.0, -cos(angle)),
            Vector3d(volume.width / 2.0, volume.height / 2.0, volume.depth / 2.0))
        if (!space.clear(player.world, oriented, player)) return false
        return !lineOfSight || space.visible(player, eye.vector(), rectangles.flatMap { ActionPanelGeometry.corners(anchor, it) }
            .distinct().map { it.vector() }, 0.025, blocksRay)
    }
    fun clearSweep(player: Player, bounds: ActionPanelBounds): Boolean = space.clearBounds(player.world,
        BoundingBox(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ), player)
    fun rayClear(player: Player, from: ActionPanelPoint, to: ActionPanelPoint, blocksRay: (Block) -> Boolean): Boolean =
        space.rayClear(player, from.vector(), to.vector(), 0.0, blocksRay)
    private fun ActionPanelPoint.vector() = Vector(x, y, z)
}
