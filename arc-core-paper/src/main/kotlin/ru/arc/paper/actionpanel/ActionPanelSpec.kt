package ru.arc.paper.actionpanel

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import ru.arc.paper.display.PacketDisplayAudienceGroup
import java.util.UUID

/** Immutable world or panel-local coordinates. Local +X is display-right, +Y up, +Z forward. */
data class ActionPanelPoint(val x: Double, val y: Double, val z: Double) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) }
}

/** The complete visible rectangle, including its current scale, in panel-local blocks. */
data class ActionPanelRect(val center: ActionPanelPoint, val width: Double, val height: Double) {
    init {
        require(width.isFinite() && width > 0.0 && width <= 8.0)
        require(height.isFinite() && height > 0.0 && height <= 4.0)
    }
}

/** Actual overloads, rather than Kotlin default arguments, keep consumer calls classloader-safe. */
class ActionPanelTextStyle(
    val scale: Float,
    val lineWidth: Int,
    val displayWidth: Float,
    val displayHeight: Float,
    val baselineInset: Double,
    val interpolationDelay: Int,
    val interpolationDuration: Int,
) {
    constructor(scale: Float, lineWidth: Int, displayWidth: Float, displayHeight: Float, baselineInset: Double) :
        this(scale, lineWidth, displayWidth, displayHeight, baselineInset, 0, 0)
    init {
        require(scale.isFinite() && scale in 0.1f..2.0f)
        require(lineWidth in 1..1024)
        require(displayWidth.isFinite() && displayWidth in 0f..8f)
        require(displayHeight.isFinite() && displayHeight in 0f..4f)
        require(baselineInset.isFinite() && baselineInset in 0.0..0.25)
        require(interpolationDelay in -1..59 && interpolationDuration in 0..59)
    }

    companion object {
        @JvmStatic fun button(scale: Float, width: Float, height: Float): ActionPanelTextStyle =
            ActionPanelTextStyle(scale, 190, width, height, 0.025)
        @JvmStatic fun status(): ActionPanelTextStyle = ActionPanelTextStyle(0.48f, 240, 3f, 0.55f, 0.0)
        @JvmStatic fun pagination(): ActionPanelTextStyle = ActionPanelTextStyle(0.48f, 80, 1f, 0.18f, 0.025)
    }
}

class ActionPanelButton<A : Any>(
    val action: A,
    val rect: ActionPanelRect,
    val text: Component,
    val style: ActionPanelTextStyle,
    val visible: Boolean,
    val enabled: Boolean,
) {
    constructor(action: A, rect: ActionPanelRect, text: Component, style: ActionPanelTextStyle) :
        this(action, rect, text, style, true, true)
}

class ActionPanelLabel(
    val key: String,
    val rect: ActionPanelRect,
    val text: Component,
    val style: ActionPanelTextStyle,
    val visible: Boolean,
) {
    constructor(key: String, rect: ActionPanelRect, text: Component, style: ActionPanelTextStyle) :
        this(key, rect, text, style, text != Component.empty())
    init { require(key.isNotBlank() && key.length <= 64) }
}

/** Stable action/label keys retain display identities across content and geometry updates. */
class ActionPanelLayout<A : Any>(buttons: List<ActionPanelButton<A>>, labels: List<ActionPanelLabel>) {
    val buttons: List<ActionPanelButton<A>> = buttons.toList()
    val labels: List<ActionPanelLabel> = labels.toList()
    init {
        require(buttons.size <= 32 && labels.size <= 8)
        require(buttons.map { it.action }.distinct().size == buttons.size) { "Panel action keys must be unique" }
        require(labels.map { it.key }.distinct().size == labels.size) { "Panel label keys must be unique" }
    }
    internal fun rectangles(): List<ActionPanelRect> =
        buttons.filter { it.visible }.map { it.rect } + labels.filter { it.visible }.map { it.rect }
}

class ActionPanelFollowSettings(
    val distance: Double,
    val sideOffset: Double,
    val heightOffset: Double,
    val obstacleMaxShift: Double,
    val movementTolerance: Double,
) {
    constructor() : this(2.4, 0.0, 0.0, 1.25, 0.03)
    init {
        require(distance.isFinite() && distance > 0.0)
        require(sideOffset.isFinite() && heightOffset.isFinite())
        require(obstacleMaxShift.isFinite() && obstacleMaxShift in 0.0..1.5)
        require(movementTolerance.isFinite() && movementTolerance in 0.0..0.25)
    }
}

sealed class ActionPanelPlacement {
    abstract val worldId: UUID

    /** A seated/table control keeps its authored anchor, including arbitrary local depth offsets. */
    class Fixed(location: Location) : ActionPanelPlacement() {
        private val anchor = location.clone()
        override val worldId: UUID = requireNotNull(location.world).uid
        init { require(listOf(location.x, location.y, location.z, location.yaw.toDouble(), location.pitch.toDouble()).all(Double::isFinite)) }
        fun location(): Location = anchor.clone()
    }

    /** Faces a domain target; caller decides selection/land eligibility and the distance/context exit. */
    class FollowTarget(
        override val worldId: UUID,
        val target: ActionPanelPoint,
        val settings: ActionPanelFollowSettings,
    ) : ActionPanelPlacement() {
        constructor(worldId: UUID, target: ActionPanelPoint) : this(worldId, target, ActionPanelFollowSettings())
    }

    /** Uses camera yaw only after meaningful body movement; head turns and crouching never chase it. */
    class FollowView(
        override val worldId: UUID,
        val settings: ActionPanelFollowSettings,
    ) : ActionPanelPlacement() {
        constructor(worldId: UUID) : this(worldId, ActionPanelFollowSettings())
    }
}

enum class ActionPanelInputProfile {
    /** Builder-style native left/right targets; the feature explicitly owns cancelled right clicks. */
    ENTITY_BOTH,
    /** Seated controls: right click only, preserving prior event cancellation. */
    ENTITY_RIGHT,
    /** No native hitboxes; the owning tool routes its supported gestures through intercept(). */
    EXTERNAL_RAY,
}

class ActionPanelOptions(
    val input: ActionPanelInputProfile,
    val reach: Double,
    val suppressGuide: Boolean,
    val frontFaceOnly: Boolean,
) {
    constructor(input: ActionPanelInputProfile, reach: Double, suppressGuide: Boolean) : this(input, reach, suppressGuide, false)
    constructor() : this(ActionPanelInputProfile.ENTITY_BOTH, 4.0, true, false)
    init { require(reach.isFinite() && reach in 0.5..8.0) }
}

/**
 * One immutable rendering decision. [context] must have stable value equality and change whenever
 * the domain session/target changes. Build a fresh frame for every query; never mutate its context.
 * Audience groups belong to the same borrowed PaperPacketDisplays owner and cannot grant privacy.
 */
class ActionPanelFrame<C : Any, A : Any>(
    val context: C,
    val placement: ActionPanelPlacement,
    val layout: ActionPanelLayout<A>,
    val inputEnabled: Boolean,
    val options: ActionPanelOptions,
    val audienceGroup: PacketDisplayAudienceGroup?,
) {
    constructor(context: C, placement: ActionPanelPlacement, layout: ActionPanelLayout<A>) :
        this(context, placement, layout, true, ActionPanelOptions(), null)
}

enum class ActionPanelInputReason {
    AIMED_GESTURE, LABEL_PLANE, LABEL_PLANE_REMAPPED,
    EVENT_CANCELLED, OFF_HAND, OWNER_MISMATCH, STALE_CONTEXT, HIDDEN_ACTION,
    INPUT_DISABLED, RAY_MISS, DUPLICATE,
}

/**
 * Domain boundary. All methods run on Paper's primary thread. A null frame revokes the panel.
 * [activate] must re-resolve domain state and permissions before a mutation; a panel hit is no
 * authority. Geometry, input deduplication, rendering and cleanup are owned by PaperActionPanels.
 */
abstract class ActionPanelController<C : Any, A : Any> {
    abstract fun frame(player: Player): ActionPanelFrame<C, A>?
    abstract fun activate(player: Player, context: C, action: A)
    open fun blocksRay(player: Player, context: C, block: Block): Boolean = true
    open fun guideSuppressionChanged(playerId: UUID, context: C, suppressed: Boolean) = Unit
    open fun inputObserved(player: Player, context: C, action: A?, accepted: Boolean, reason: ActionPanelInputReason) = Unit
}

class ActionPanelAction<A : Any>(val action: A, val text: Component)

class ActionPanelGridSettings(
    val rowSpacing: Double,
    val columnSpacing: Double,
    val buttonWidth: Float,
    val buttonHeight: Float,
    val labelScale: Float,
) {
    constructor() : this(0.26, 1.55, 1.5f, 0.23f, 0.8f)
    init {
        require(rowSpacing.isFinite() && rowSpacing > 0.0)
        require(columnSpacing.isFinite() && columnSpacing > 0.0)
        require(buttonWidth.isFinite() && buttonWidth in 0.2f..3f)
        require(buttonHeight.isFinite() && buttonHeight in 0.1f..1f)
        require(labelScale.isFinite() && labelScale in 0.1f..2f)
    }
}

/** Builder's centred two-column layout. Explicit rectangles remain available for table/staff scenes. */
object ActionPanelLayouts {
    @JvmStatic fun <A : Any> twoColumn(actions: List<ActionPanelAction<A>>, status: Component): ActionPanelLayout<A> =
        twoColumn(actions, status, Component.empty(), 0, ActionPanelGridSettings())

    /** With navigationCount=2, final actions are previous/next; six body slots and the header stay fixed. */
    @JvmStatic fun <A : Any> twoColumn(
        actions: List<ActionPanelAction<A>>,
        status: Component,
        pagination: Component,
        navigationCount: Int,
        settings: ActionPanelGridSettings,
    ): ActionPanelLayout<A> {
        require(actions.size <= 16)
        require(navigationCount == 0 || navigationCount == 2)
        require(navigationCount <= actions.size)
        val bodyCount = actions.size - navigationCount
        if (navigationCount == 2) require(bodyCount <= 6)
        val bodyRows = (bodyCount + 1) / 2
        val rows = if (navigationCount == 2) 4 else (actions.size + 1) / 2
        val buttons = actions.mapIndexed { index, action ->
            val navigation = navigationCount == 2 && index >= bodyCount
            val activeRow = index / 2
            val row = if (navigation) 3 else if (navigationCount == 2) (3 - bodyRows) / 2 + activeRow else activeRow
            val column = if (navigation) index - bodyCount else index % 2
            val countInRow = if (navigation) 2 else minOf(2, bodyCount - activeRow * 2)
            val width = if (navigation) 0.45f else settings.buttonWidth
            val rect = ActionPanelRect(
                ActionPanelPoint((column - (countInRow - 1) / 2.0) * settings.columnSpacing,
                    ((rows - 1) / 2.0 - row) * settings.rowSpacing, 0.0),
                width.toDouble(), settings.buttonHeight.toDouble(),
            )
            ActionPanelButton(action.action, rect, action.text,
                ActionPanelTextStyle.button(settings.labelScale, width, settings.buttonHeight))
        }
        val highestTop = if (navigationCount == 2) (rows - 1) / 2.0 * settings.rowSpacing + settings.buttonHeight / 2.0
            else buttons.maxOfOrNull { it.rect.center.y + it.rect.height / 2.0 } ?: 0.0
        val labels = mutableListOf(ActionPanelLabel("status",
            ActionPanelRect(ActionPanelPoint(0.0, highestTop + 0.04 + 0.12, 0.0), 3.0, 0.24),
            status, ActionPanelTextStyle.status()))
        if (navigationCount == 2) labels += ActionPanelLabel("pagination",
            ActionPanelRect(ActionPanelPoint(0.0, buttons.last().rect.center.y, 0.0), 1.0, settings.buttonHeight.toDouble()),
            pagination, ActionPanelTextStyle.pagination())
        return ActionPanelLayout(buttons, labels)
    }
}
