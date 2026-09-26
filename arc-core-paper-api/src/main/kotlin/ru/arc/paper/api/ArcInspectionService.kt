package ru.arc.paper.api

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * One candidate source for the shared per-viewer inspection card.
 *
 * Resolution runs synchronously on Paper's primary thread. Return `null` when
 * this source does not own the viewer's current target; lower-priority sources
 * are then considered. Return a frame with both components empty when the
 * viewer is looking at an occupied target that must suppress lower sources but
 * has no visible label.
 */
fun interface ArcInspectionProvider {
    fun resolve(player: Player): ArcInspectionFrame?
}

/** Content for the two presentation modes supported by the shared inspector. */
data class ArcInspectionFrame(
    val hologram: Component,
    val bossbar: Component,
    val hologramAnchor: InspectionHologramAnchor?,
) {
    // Retain the original JVM constructor, including Kotlin's default-argument
    // bridge, for independently loaded providers compiled against older core.
    constructor(hologram: Component, bossbar: Component = hologram) : this(hologram, bossbar, null)

    /** Preserve the original copy bridge while retaining an existing anchor. */
    fun copy(hologram: Component = this.hologram, bossbar: Component = this.bossbar): ArcInspectionFrame =
        ArcInspectionFrame(hologram, bossbar, hologramAnchor)

    /** Both empty components mean "occupied, but intentionally show no card". */
    val suppressesLowerSources: Boolean
        get() = hologram == Component.empty() && bossbar == Component.empty()
}

/**
 * Immutable world position of a hologram's bottom edge. Providers resolve this
 * on the primary thread; no entity or mutable Location is retained. Anchored
 * cards stay upright, retain the viewer's scale, and ignore viewer-relative
 * layout offsets. A frame anchored in another world is hidden.
 */
data class InspectionHologramAnchor(val worldId: UUID, val x: Double, val y: Double, val z: Double) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Inspection anchor coordinates must be finite" }
    }
}

enum class InspectionViewMode {
    HOLOGRAM,
    BOSSBAR,
    OFF,
}

/** Per-viewer layout retained by the ARC host's existing item-info preferences. */
data class InspectionViewPreferences(
    val mode: InspectionViewMode = InspectionViewMode.HOLOGRAM,
    val scale: Float = 0.90f,
    val verticalOffset: Double = 0.50,
    val horizontalOffset: Double = 0.0,
) {
    init {
        require(scale in MIN_SCALE..MAX_SCALE && scale.isFinite()) {
            "Inspection scale must be finite and between $MIN_SCALE and $MAX_SCALE"
        }
        require(verticalOffset in MIN_VERTICAL_OFFSET..MAX_VERTICAL_OFFSET && verticalOffset.isFinite()) {
            "Inspection vertical offset must be finite and between $MIN_VERTICAL_OFFSET and $MAX_VERTICAL_OFFSET"
        }
        require(horizontalOffset in MIN_HORIZONTAL_OFFSET..MAX_HORIZONTAL_OFFSET && horizontalOffset.isFinite()) {
            "Inspection horizontal offset must be finite and between $MIN_HORIZONTAL_OFFSET and $MAX_HORIZONTAL_OFFSET"
        }
    }

    private companion object {
        const val MIN_SCALE = 0.50f
        const val MAX_SCALE = 2.00f
        const val MIN_VERTICAL_OFFSET = -1.50
        const val MAX_VERTICAL_OFFSET = 1.50
        const val MIN_HORIZONTAL_OFFSET = -2.00
        const val MAX_HORIZONTAL_OFFSET = 2.00
    }
}

/**
 * Shared source registry for a server's single inspection-card renderer.
 *
 * Register and close sources on Paper's primary thread. IDs are unique per
 * owner. Higher priorities resolve first; equal priorities preserve
 * registration order. Closing a handle removes that source and immediately
 * resolves the next source for viewers it owned.
 */
interface ArcInspectionService {
    fun register(
        owner: Plugin,
        id: String,
        priority: Int,
        provider: ArcInspectionProvider,
    ): AutoCloseable
}
