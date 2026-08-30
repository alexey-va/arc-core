package ru.arc.paper.nameplate

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

/** Bounded rendering and visibility policy for one player nameplate runtime. */
data class PaperNameplateOptions(
    val reconcilePeriodTicks: Long = 4L,
    val maxDistance: Double = 32.0,
    val lineWidth: Int = 200,
    val viewRange: Float = 0.5F,
    val scale: Float = 1.0F,
    val verticalOffset: Float = 0.0F,
    val shadowed: Boolean = true,
    val backgroundColor: Color = Color.fromARGB(0, 0, 0, 0),
    val hideInvisibleTargets: Boolean = true,
    val hideSpectatorTargets: Boolean = true,
    val requireLineOfSight: Boolean = true,
) {
    init {
        require(reconcilePeriodTicks in 1L..20L) {
            "Nameplate reconcile period must be between 1 and 20 ticks"
        }
        require(maxDistance in 1.0..128.0 && maxDistance.isFinite()) {
            "Nameplate distance must be finite and between 1 and 128 blocks"
        }
        require(lineWidth in 1..1_024) { "Nameplate line width must be between 1 and 1024" }
        require(viewRange in 0.1F..4.0F && viewRange.isFinite()) {
            "Nameplate view range must be finite and between 0.1 and 4.0"
        }
        require(scale in 0.25F..2.0F && scale.isFinite()) {
            "Nameplate scale must be finite and between 0.25 and 2.0"
        }
        require(verticalOffset in -2.0F..4.0F && verticalOffset.isFinite()) {
            "Nameplate vertical offset must be finite and between -2.0 and 4.0"
        }
    }
}

/** One rendered platform handle owned by [PaperPlayerNameplates]. */
interface PaperNameplateDisplay : AutoCloseable {
    val targetId: UUID

    fun isAttachedTo(target: Player): Boolean

    fun update(content: Component)

    fun show(viewer: Player)

    fun hide(viewer: Player)

    override fun close()
}

/** Exact Paper entity boundary used by [PaperPlayerNameplates]. */
fun interface PaperNameplateDisplayFactory {
    fun create(
        target: Player,
        content: Component,
    ): PaperNameplateDisplay
}

/**
 * Production TextDisplay implementation for a styled player nameplate.
 *
 * The display is non-persistent, hidden by default, depth-tested and mounted as
 * a player passenger. Per-viewer delivery is delegated to Paper's
 * `showEntity`/`hideEntity` ownership model.
 */
class NativePaperNameplateDisplayFactory(
    private val plugin: Plugin,
    private val options: PaperNameplateOptions = PaperNameplateOptions(),
) : PaperNameplateDisplayFactory {
    override fun create(
        target: Player,
        content: Component,
    ): PaperNameplateDisplay {
        val display = target.world.spawn(target.location, TextDisplay::class.java) { entity ->
            entity.text(content)
            entity.billboard = Display.Billboard.CENTER
            entity.alignment = TextDisplay.TextAlignment.CENTER
            entity.lineWidth = options.lineWidth
            entity.viewRange = options.viewRange
            entity.transformation = Transformation(
                Vector3f(0.0F, options.verticalOffset, 0.0F),
                Quaternionf(),
                Vector3f(options.scale, options.scale, options.scale),
                Quaternionf(),
            )
            entity.isShadowed = options.shadowed
            entity.isSeeThrough = false
            entity.isDefaultBackground = false
            entity.backgroundColor = options.backgroundColor
            entity.isVisibleByDefault = false
            entity.isPersistent = false
            entity.setGravity(false)
            entity.isInvulnerable = true
            entity.isSilent = true
        }
        if (!target.addPassenger(display)) {
            display.remove()
            error("Paper rejected the nameplate passenger attachment")
        }
        return NativePaperNameplateDisplay(plugin, target.uniqueId, display)
    }
}

private class NativePaperNameplateDisplay(
    private val plugin: Plugin,
    override val targetId: UUID,
    private val display: TextDisplay,
) : PaperNameplateDisplay {
    override fun isAttachedTo(target: Player): Boolean =
        display.isValid && display.vehicle?.uniqueId == target.uniqueId

    override fun update(content: Component) {
        display.text(content)
    }

    override fun show(viewer: Player) {
        viewer.showEntity(plugin, display)
    }

    override fun hide(viewer: Player) {
        viewer.hideEntity(plugin, display)
    }

    override fun close() {
        display.remove()
    }
}
