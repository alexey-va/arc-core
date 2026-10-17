package ru.arc.paper.scene

import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import ru.arc.paper.display.DisplayOrientedVolume
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class SpatialSceneKey(val value: String) { init { require(value.isNotBlank()) } }

sealed interface SpatialSceneScope {
    data class Viewer(val playerId: UUID) : SpatialSceneScope
    data object World : SpatialSceneScope
}

enum class SpatialInputProfile {
    ENTITY_BOTH, ENTITY_RIGHT, EXTERNAL_RAY,
    /** Public/book gateways historically act at LOWEST and honor cancellation. */
    ENTITY_RIGHT_EARLY,
}

sealed interface SpatialReach {
    data class Fixed(val blocks: Double) : SpatialReach { init { require(blocks.isFinite() && blocks > 0.0) } }
    data object NativeInteraction : SpatialReach
    fun distance(player: Player): Double = when (this) {
        is Fixed -> blocks
        NativeInteraction -> player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE)?.value?.takeIf { it.isFinite() && it > 0.0 } ?: 3.0
    }
}

/** Actual rendered input plane; independent of native hitbox/culling dimensions. */
class SpatialHitPlane(center: Location, right: Vector, up: Vector, val width: Double, val height: Double,
    val frontFaceOnly: Boolean = false) {
    private val at = ScenePose(center).location()
    private val right = right.clone()
    private val up = up.clone()
    constructor(center: Location, width: Double, height: Double, frontFaceOnly: Boolean = false) : this(center,
        Vector(cos(Math.toRadians(center.yaw.toDouble())), 0.0, sin(Math.toRadians(center.yaw.toDouble()))),
        Vector(0, 1, 0), width, height, frontFaceOnly)
    init {
        require(width.isFinite() && width > 0 && height.isFinite() && height > 0)
        require(listOf(this.right.x, this.right.y, this.right.z, this.up.x, this.up.y, this.up.z).all(Double::isFinite))
        require(abs(this.right.lengthSquared() - 1) < 1e-5 && abs(this.up.lengthSquared() - 1) < 1e-5 && abs(this.right.dot(this.up)) < 1e-5)
    }
    fun center(): Location = at.clone()
    internal fun hit(origin: Vector, direction: Vector, reach: Double): Pair<Vector, Double>? {
        val normal = right.clone().crossProduct(up)
        val denominator = direction.dot(normal)
        if (abs(denominator) <= 1e-8 || (frontFaceOnly && denominator >= -1e-8)) return null
        val distance = at.toVector().subtract(origin).dot(normal) / denominator
        if (distance < 0 || distance > reach) return null
        val point = origin.clone().add(direction.clone().multiply(distance))
        val offset = point.clone().subtract(at.toVector())
        return if (abs(offset.dot(right)) <= width / 2 + 1e-8 && abs(offset.dot(up)) <= height / 2 + 1e-8) point to distance else null
    }
    override fun equals(other: Any?): Boolean = other is SpatialHitPlane && at == other.at && right == other.right && up == other.up &&
        width == other.width && height == other.height && frontFaceOnly == other.frontFaceOnly
    override fun hashCode(): Int = listOf(at, right, up, width, height, frontFaceOnly).hashCode()
}

class SpatialNativeTarget(bottom: Location, val width: Float, val height: Float) {
    private val at = ScenePose(bottom).location()
    init { require(width.isFinite() && width > 0f && height.isFinite() && height > 0f) }
    fun location(): Location = at.clone()
    override fun equals(other: Any?): Boolean = other is SpatialNativeTarget && at == other.at && width == other.width && height == other.height
    override fun hashCode(): Int = listOf(at, width, height).hashCode()
}

class SpatialControl<A : Any>(val key: String, val action: A, planes: List<SpatialHitPlane>,
    val nativeTarget: SpatialNativeTarget?, val visible: Boolean = true, val enabled: Boolean = true) {
    val planes = planes.toList()
    init { require(key.isNotBlank() && planes.isNotEmpty() && planes.size <= 8) }
}

class SpatialControlFrame<C : Any, A : Any>(val context: C, val audience: SceneAudience,
    val scope: SpatialSceneScope, controls: List<SpatialControl<A>>, val input: SpatialInputProfile,
    val reach: SpatialReach, val inputEnabled: Boolean = true,
    clearanceFootprints: List<DisplayOrientedVolume> = emptyList()) {
    val controls = controls.toList()
    val clearanceFootprints = clearanceFootprints.toList()
    init {
        require(controls.size <= 64)
        require(clearanceFootprints.size <= 32)
        require(controls.map { it.key }.toSet().size == controls.size)
        require(controls.flatMap { it.planes }.map { it.center().world!!.uid }.distinct().size <= 1)
    }
}

data class SpatialControlTarget<C : Any, A : Any>(val context: C, val action: A)

enum class SpatialInputReason {
    AIMED_GESTURE, LABEL_PLANE, LABEL_PLANE_REMAPPED, EVENT_CANCELLED, OFF_HAND, OWNER_MISMATCH,
    STALE_CONTEXT, HIDDEN_ACTION, INPUT_DISABLED, RAY_MISS, DUPLICATE,
}

/** Fresh state is captured on the primary thread for each input; activation still rechecks authority. */
abstract class SpatialControlController<C : Any, A : Any> {
    abstract fun current(player: Player, scene: SpatialSceneKey): SpatialControlFrame<C, A>?
    abstract fun activate(player: Player, scene: SpatialSceneKey, context: C, action: A)
    open fun blocksRay(player: Player, context: C, block: Block): Boolean = true
    open fun inputObserved(player: Player, scene: SpatialSceneKey, context: C, action: A?, accepted: Boolean, reason: SpatialInputReason) = Unit
    /** Shared input is already revoked. Clear feature visuals/state; the owner reports the failure once. */
    open fun inputFailed(player: Player, scene: SpatialSceneKey, context: C, failure: Exception) = Unit
}
