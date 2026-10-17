package ru.arc.paper.scene

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketDisplayAudienceGroup
import java.util.UUID

/** A shared gate for a scene's packet visuals and native controls. */
sealed class SceneAudience(val group: PacketDisplayAudienceGroup?) {
    data class Public(private val gate: PacketDisplayAudienceGroup? = null) : SceneAudience(gate)
    data class Private(val viewerId: UUID, private val gate: PacketDisplayAudienceGroup? = null) : SceneAudience(gate)
    internal fun allows(player: Player): Boolean =
        (this !is Private || player.uniqueId == viewerId) && group?.allows(player) != false
}

/** Idle refresh admission remains tied to authored state even when every rendered part is hidden. */
class SceneRefreshRegion(anchor: Location, val audience: SceneAudience, val rangeBlocks: Double) {
    private val anchor = ScenePose(anchor).location()
    init { require(rangeBlocks.isFinite() && rangeBlocks > 0.0) }
    internal fun observes(viewer: ru.arc.paper.display.PacketDisplayViewer): Boolean {
        if (anchor.world?.uid != viewer.worldId || !audience.allows(viewer.player)) return false
        val dx = anchor.x - viewer.x; val dy = anchor.y - viewer.y; val dz = anchor.z - viewer.z
        return dx * dx + dy * dy + dz * dz <= rangeBlocks * rangeBlocks
    }
}

/** Copies every mutable Bukkit/JOML value at the main-thread boundary. */
class ScenePose(location: Location, transformation: Transformation) {
    private val at = location.clone()
    private val transform = copyTransform(transformation)
    constructor(location: Location) : this(location, Transformation(Vector3f(), Quaternionf(), Vector3f(1f), Quaternionf()))
    init {
        requireNotNull(at.world)
        require(listOf(at.x, at.y, at.z, at.yaw.toDouble(), at.pitch.toDouble()).all(Double::isFinite))
        require(listOf(transform.translation.x, transform.translation.y, transform.translation.z,
            transform.scale.x, transform.scale.y, transform.scale.z,
            transform.leftRotation.x, transform.leftRotation.y, transform.leftRotation.z, transform.leftRotation.w,
            transform.rightRotation.x, transform.rightRotation.y, transform.rightRotation.z, transform.rightRotation.w).all(Float::isFinite))
    }
    fun location(): Location = at.clone()
    fun transformation(): Transformation = copyTransform(transform)
    override fun equals(other: Any?): Boolean = other is ScenePose && at == other.at && transform == other.transform
    override fun hashCode(): Int = 31 * at.hashCode() + transform.hashCode()
    private companion object {
        fun copyTransform(t: Transformation) = Transformation(Vector3f(t.translation), Quaternionf(t.leftRotation), Vector3f(t.scale), Quaternionf(t.rightRotation))
    }
}

data class SceneDisplayStyle(
    val billboard: Display.Billboard = Display.Billboard.FIXED,
    val brightness: Display.Brightness? = null,
    val interpolationDelay: Int = 0,
    val interpolationDuration: Int = 0,
    val viewRange: Float = 1f,
    val shadowRadius: Float = 0f,
    val shadowStrength: Float = 0f,
    val displayWidth: Float = 0f,
    val displayHeight: Float = 0f,
    val glowing: Boolean = false,
    val glowColor: Color? = null,
) {
    init {
        require(interpolationDuration in 0..59)
        require(listOf(viewRange, shadowRadius, shadowStrength, displayWidth, displayHeight).all { it.isFinite() && it >= 0f })
    }
}

data class SceneTextStyle(
    val lineWidth: Int = 200,
    val background: Color? = Color.fromARGB(0x40000000),
    val opacity: Byte = 255.toByte(),
    val shadowed: Boolean = false,
    val seeThrough: Boolean = false,
    val defaultBackground: Boolean = false,
    val alignment: TextDisplay.TextAlignment = TextDisplay.TextAlignment.CENTER,
) { init { require(lineWidth > 0) } }

/** Flat keyed parts; geometry/permission/content policy remains with the owning feature. */
sealed class ScenePart<K : Any>(val key: K, val pose: ScenePose, val style: SceneDisplayStyle, val visible: Boolean) {
    class Text<K : Any>(key: K, pose: ScenePose, style: SceneDisplayStyle, val text: Component,
        val textStyle: SceneTextStyle, visible: Boolean = true) : ScenePart<K>(key, pose, style, visible)
    class Item<K : Any>(key: K, pose: ScenePose, style: SceneDisplayStyle, item: ItemStack,
        val itemTransform: ItemDisplay.ItemDisplayTransform, visible: Boolean = true) : ScenePart<K>(key, pose, style, visible) {
        private val stored = item.clone()
        fun item(): ItemStack = stored.clone()
        internal fun sameContent(other: Item<*>): Boolean = stored == other.stored
    }
    class Block<K : Any>(key: K, pose: ScenePose, style: SceneDisplayStyle, block: BlockData,
        visible: Boolean = true) : ScenePart<K>(key, pose, style, visible) {
        private val stored = block.clone()
        fun block(): BlockData = stored.clone()
        internal fun sameContent(other: Block<*>): Boolean = stored == other.stored
    }
}

/** One synchronized movement decision, applied to every moving part. Empty/hidden frames retain keys. */
class SceneFrame<K : Any>(parts: List<ScenePart<K>>, val audience: SceneAudience, val teleportTicks: Int = 0) {
    val parts = parts.toList()
    init {
        require(teleportTicks in 0..59)
        require(parts.size <= 256) { "A spatial scene supports at most 256 parts" }
        require(parts.map { it.key }.toSet().size == parts.size) { "Scene part keys must be unique" }
    }
}
