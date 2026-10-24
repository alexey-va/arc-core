package ru.arc.paper.actionpanel

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.scene.*
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A fresh current-context ray target; resolving it does not execute or deduplicate a gesture. */
data class ActionPanelTarget<C : Any, A : Any>(val context: C, val action: A)

/**
 * One plugin-lifecycle owner of viewer-private contextual controls. Construct, tick, query, mutate
 * and close on Paper's primary thread. The caller ticks only while its feature is active, or calls
 * [refresh] for individual eligible viewers. No private scheduler/packet transport is installed.
 *
 * Text uses the existing PaperPacketDisplays asynchronous/budgeted/ordered transport. Only profiles
 * requesting native interaction targets create server entities. A ray or entity ID is never domain
 * authority: input resolves a fresh controller frame, fences its context and invokes the controller
 * only for the actual visible plane. The controller must recheck permissions at its mutation.
 *
 * The display-taking constructor borrows that owner and never closes it. The source-taking overload
 * owns its display runtime. Both release all panel handles, native targets, suppression and listeners.
 * In the default profiles, head turns/crouching only update actual-eye aiming; panels retain placement until
 * meaningful body movement, a target/layout change or obstructed geometry requires reconciliation.
 * Near obstacles, a safe world anchor remains within the bounded follow envelope; clearance allows
 * gradual recovery on subsequent refreshes, including when the viewer has stopped moving.
 * AIM_STABLE view overlays instead latch aiming yaw, follow body translation and recenter after looking away.
 */
class PaperActionPanels<C : Any, A : Any> internal constructor(
    private val plugin: Plugin,
    private val displays: PaperPacketDisplays,
    private val controller: ActionPanelController<C, A>,
    private val ownsDisplays: Boolean,
    private val worldSpace: ActionPanelWorldSpace,
    private val nowNanos: () -> Long,
) : Listener, AutoCloseable {
    constructor(plugin: Plugin, displays: PaperPacketDisplays, controller: ActionPanelController<C, A>) :
        this(plugin, displays, controller, false, ActionPanelWorldSpace(), System::nanoTime)
    constructor(plugin: Plugin, source: String, controller: ActionPanelController<C, A>) :
        this(plugin, PaperPacketDisplays(plugin, source), controller, true, ActionPanelWorldSpace(), System::nanoTime)

    private data class VisualKey<A : Any>(val action: A?, val label: String?)
    private inner class Panel(
        val owner: UUID,
        var frame: ActionPanelFrame<C, A>,
        var anchor: ActionPanelAnchor,
        var positionedFrom: ActionPanelPoint,
        val eyeOffset: ActionPanelPoint,
    ) {
        var distance: Double? = null
        var shift: ActionPanelShift? = null
        var recovering = false
        var suppressed = false
        var lookingAt = false
        val scene = PacketDisplayScene<VisualKey<A>>(displays)
        val controlKeys = linkedMapOf<A, String>()
        var nextControlKey = 0
        var hovered: A? = null
        var recenterYaw: Float? = null
    }
    private data class Placement(val anchor: ActionPanelAnchor, val distance: Double?, val shift: ActionPanelShift?, val recovering: Boolean = false)
    private inner class PlacementRetry(val frame: ActionPanelFrame<C, A>, val body: ActionPanelPoint, val until: Long)
    private val panels = linkedMapOf<UUID, Panel>()
    private val failuresLogged = mutableSetOf<UUID>()
    private val placementRetries = mutableMapOf<UUID, PlacementRetry>()
    private val rendererRetries = mutableMapOf<UUID, Long>()
    private var closed = false

    private val controls = PaperSpatialControls(plugin, displays, object : SpatialControlController<C, A>() {
        override fun current(player: Player, scene: SpatialSceneKey): SpatialControlFrame<C, A>? {
            val panel = panels[player.uniqueId] ?: return null
            if (scene != key(player.uniqueId)) return null
            val current = controller.frame(player) ?: run { clear(player.uniqueId); return null }
            if (!sameInputFrame(panel.frame, current)) { refresh(player); return null }
            if (!validPlacement(player, panel)) return null
            return controlFrame(player, panel, current)
        }
        override fun activate(player: Player, scene: SpatialSceneKey, context: C, action: A) {
            panels[player.uniqueId]?.recenterYaw = null
            controller.activate(player, context, action)
        }
        override fun blocksRay(player: Player, context: C, block: org.bukkit.block.Block) = controller.blocksRay(player, context, block)
        override fun requiresLineOfSight(player: Player, context: C): Boolean =
            panels[player.uniqueId]?.frame?.takeIf { it.context == context }?.options?.depth != ActionPanelDepth.VIEW_OVERLAY
        override fun inputObserved(player: Player, scene: SpatialSceneKey, context: C, action: A?, accepted: Boolean, reason: SpatialInputReason) =
            controller.inputObserved(player, context, action, accepted, ActionPanelInputReason.valueOf(reason.name))
        override fun inputFailed(player: Player, scene: SpatialSceneKey, context: C, failure: Exception) =
            failed(player.uniqueId, failure, report = false)
    }, PaperSpatialSpace(), nowNanos)

    init {
        displays.checkThread()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun tick() {
        displays.checkThread()
        if (closed) return
        val online = plugin.server.onlinePlayers.toList()
        val present = online.mapTo(hashSetOf()) { it.uniqueId }
        ((panels.keys + placementRetries.keys + rendererRetries.keys) - present).forEach(::clear)
        online.forEach(::refresh)
    }

    /** Reconcile one viewer; useful when the tool's existing lifecycle already discovers eligibility. */
    fun refresh(player: Player) {
        displays.checkThread()
        if (closed) return
        if (rendererRetries[player.uniqueId]?.let { nowNanos() < it } == true) return
        var attempted: ActionPanelFrame<C, A>? = null
        try {
            val frame = controller.frame(player)
            if (!player.isOnline || player.isDead || frame == null || frame.placement.worldId != player.world.uid ||
                (frame.layout.rectangles().isEmpty() && (frame.placement !is ActionPanelPlacement.Fixed ||
                    (frame.layout.buttons.isEmpty() && frame.layout.labels.isEmpty())))) {
                clear(player.uniqueId)
                return
            }
            attempted = frame
            val retry = placementRetries[player.uniqueId]
            if (retry != null && nowNanos() < retry.until && retry.body == player.location.point() &&
                sameInputFrame(retry.frame, frame)) return
            placementRetries.remove(player.uniqueId)
            panels[player.uniqueId]?.takeIf { !it.scene.isValid() }?.let { removeCurrent(player.uniqueId) }
            val panel = panels[player.uniqueId] ?: create(player, frame).also { panels[player.uniqueId] = it }
            apply(player, panel, frame)
            updateSuppression(player, panel)
            updateHover(player, panel)
            failuresLogged.remove(player.uniqueId)
            rendererRetries.remove(player.uniqueId)
        } catch (_: NoPlacement) {
            removeCurrent(player.uniqueId)
            attempted?.let { placementRetries[player.uniqueId] = PlacementRetry(it, player.location.point(), nowNanos() + 250_000_000L) }
        } catch (failure: Exception) {
            failed(player.uniqueId, failure)
        }
    }

    fun contains(playerId: UUID): Boolean { displays.checkThread(); return playerId in panels }
    fun isLookingAt(playerId: UUID): Boolean { displays.checkThread(); return panels[playerId]?.lookingAt == true }

    /** Fresh ray/context resolution for tools that own their own gesture dispatch and deduplication. */
    fun target(player: Player): ActionPanelTarget<C, A>? {
        displays.checkThread()
        if (closed) return null
        val panel = panels[player.uniqueId] ?: return null
        return try {
            if (!validPlacement(player, panel)) null else controls.target(key(player.uniqueId), player)?.let { ActionPanelTarget(it.context, it.action) }
        } catch (failure: Exception) { failed(player.uniqueId, failure); null }
    }

    /** Consumes only an aimed visible control; unrelated gameplay/tool gestures return false. */
    fun intercept(player: Player): Boolean {
        displays.checkThread()
        if (closed) return false
        val panel = panels[player.uniqueId] ?: return false
        return try { (validPlacement(player, panel) && controls.intercept(key(player.uniqueId), player)).also {
            if (it) panel.recenterYaw = null
        } }
        catch (failure: Exception) { failed(player.uniqueId, failure); false }
    }

    private fun create(player: Player, frame: ActionPanelFrame<C, A>): Panel {
        val body = player.location.point()
        val eye = player.eyeLocation.point()
        val offset = ActionPanelPoint(eye.x - body.x, eye.y - body.y, eye.z - body.z)
        val placed = place(player, frame, offset, null)
        return Panel(player.uniqueId, frame, placed.anchor, body, offset).also {
            it.distance = placed.distance; it.shift = placed.shift; it.recovering = placed.recovering
        }
    }

    private fun apply(player: Player, panel: Panel, frame: ActionPanelFrame<C, A>) {
        val previous = panel.frame
        val contextChanged = previous.context != frame.context
        val shapeChanged = geometryKey(previous) != geometryKey(frame)
        val placementChanged = !samePlacement(previous.placement, frame.placement)
        val settings = followSettings(frame.placement)
        val moved = ActionPanelGeometry.distance(panel.positionedFrom, player.location.point()) > (settings?.movementTolerance ?: 0.0)
        val eye = stableEye(player, panel.eyeOffset)
        val rectangles = frame.layout.rectangles()
        val invalid = settings != null && !safe(player, frame, eye, panel.anchor)
        var duration = 0
        val aimStable = (frame.placement as? ActionPanelPlacement.FollowView)?.motion == ActionPanelViewMotion.AIM_STABLE
        if (aimStable && !placementChanged && previous.options.depth == frame.options.depth) {
            // Content/context changes retain this plane. Body translation must not recapture the
            // yaw used to aim at an off-centre button (otherwise confirmation circles the cursor).
            val body = player.location.point()
            val delta = if (moved) ActionPanelPoint(body.x - panel.positionedFrom.x,
                body.y - panel.positionedFrom.y, body.z - panel.positionedFrom.z) else ActionPanelPoint(0.0, 0.0, 0.0)
            var anchor = ActionPanelAnchor(ActionPanelPoint(panel.anchor.point.x + delta.x,
                panel.anchor.point.y + delta.y, panel.anchor.point.z + delta.z), panel.anchor.yaw)
            val actualEye = player.eyeLocation
            val direction = actualEye.direction
            if (contextChanged || shapeChanged) panel.recenterYaw = null
            if (panel.recenterYaw == null && !contextChanged && !shapeChanged &&
                !ActionPanelGeometry.gazeIntersects(actualEye.point(), ActionPanelPoint(direction.x, direction.y, direction.z),
                    anchor, rectangles, 13.0)) panel.recenterYaw = actualEye.yaw + 180f
            panel.recenterYaw?.let { desiredYaw ->
                val turn = ((((desiredYaw - anchor.yaw).toDouble() % 360.0 + 540.0) % 360.0) - 180.0).coerceIn(-12.0, 12.0)
                val angle = Math.toRadians(turn)
                val x = anchor.point.x - eye.x
                val z = anchor.point.z - eye.z
                anchor = ActionPanelAnchor(ActionPanelPoint(eye.x + cos(angle) * x - sin(angle) * z,
                    anchor.point.y, eye.z + sin(angle) * x + cos(angle) * z), ((anchor.yaw + turn + 360.0) % 360.0).toFloat())
                if (ActionPanelGeometry.yawDelta(anchor.yaw, desiredYaw) < 1e-4) panel.recenterYaw = null
            }
            val placed = if (safe(player, frame, eye, anchor)) Placement(anchor, panel.distance, panel.shift)
                else place(player, frame, panel.eyeOffset, panel)
            duration = interpolation(player, frame, panel.anchor, placed.anchor)
            panel.anchor = placed.anchor
            if (moved) panel.positionedFrom = body
            panel.distance = placed.distance
            panel.shift = placed.shift
            panel.recovering = placed.recovering
        } else if (shapeChanged || placementChanged || (settings != null && moved) || invalid || panel.recovering) {
            val placed = place(player, frame, panel.eyeOffset, panel.takeUnless { shapeChanged || placementChanged })
            val canInterpolate = settings != null && !shapeChanged && !invalid &&
                ActionPanelGeometry.distance(panel.anchor.point, placed.anchor.point) <= 1.0 &&
                ActionPanelGeometry.yawDelta(panel.anchor.yaw, placed.anchor.yaw) <= 30.0 &&
                (frame.options.depth == ActionPanelDepth.VIEW_OVERLAY || worldSpace.clearSweep(player,
                    ActionPanelGeometry.sweptBounds(panel.anchor, placed.anchor, rectangles, ActionPanelGeometry.SURFACE_CLEARANCE)))
            duration = if (canInterpolate) 2 else 0
            panel.anchor = placed.anchor
            panel.positionedFrom = player.location.point()
            panel.distance = placed.distance
            panel.shift = placed.shift
            panel.recovering = placed.recovering
        }
        if (contextChanged) {
            setSuppressed(panel, false)
        }
        panel.frame = frame
        reconcile(player, panel, duration)
    }

    private fun interpolation(player: Player, frame: ActionPanelFrame<C, A>, from: ActionPanelAnchor, to: ActionPanelAnchor): Int =
        if (from != to && ActionPanelGeometry.distance(from.point, to.point) <= 1.0 &&
            ActionPanelGeometry.yawDelta(from.yaw, to.yaw) <= 30.0 &&
            (frame.options.depth == ActionPanelDepth.VIEW_OVERLAY || worldSpace.clearSweep(player,
                ActionPanelGeometry.sweptBounds(from, to, frame.layout.rectangles(), ActionPanelGeometry.SURFACE_CLEARANCE)))) 2 else 0

    private fun safe(player: Player, frame: ActionPanelFrame<C, A>, eye: ActionPanelPoint, anchor: ActionPanelAnchor,
        clearance: Double = 0.0): Boolean = if (frame.options.depth == ActionPanelDepth.VIEW_OVERLAY)
        ActionPanelGeometry.allReachable(eye, anchor, frame.layout.rectangles(), frame.options.reach)
    else worldSpace.safe(player, eye, anchor, frame.layout.rectangles(), frame.options.reach, clearance, true) {
        controller.blocksRay(player, frame.context, it)
    }

    private fun place(
        player: Player, frame: ActionPanelFrame<C, A>, eyeOffset: ActionPanelPoint, previous: Panel?,
    ): Placement {
        val fixed = frame.placement as? ActionPanelPlacement.Fixed
        if (fixed != null) return Placement(fixed.location().let { ActionPanelAnchor(it.point(), it.yaw) }, null, null)
        val settings = checkNotNull(followSettings(frame.placement))
        val eye = stableEye(player, eyeOffset)
        val fallbackYaw = previous?.anchor?.yaw ?: ((player.eyeLocation.yaw + 180f) % 360f + 360f) % 360f
        val target = (frame.placement as? ActionPanelPlacement.FollowTarget)?.target
        val yaw = if (target != null) {
            val dx = target.x - eye.x
            val dz = target.z - eye.z
            if (hypot(dx, dz) >= 0.25) Math.toDegrees(atan2(dx, -dz)).toFloat() else fallbackYaw
        } else if (previous == null || ActionPanelGeometry.distance(previous.positionedFrom, player.location.point()) > settings.movementTolerance) {
            ((player.eyeLocation.yaw + 180f) % 360f + 360f) % 360f
        } else fallbackYaw
        val rects = frame.layout.rectangles()
        fun anchor(distance: Double, shift: ActionPanelShift): ActionPanelAnchor = ActionPanelGeometry.followingAnchor(
            eye, yaw, rects, distance, settings.sideOffset + shift.side, settings.heightOffset + shift.up)
        fun safe(distance: Double, shift: ActionPanelShift, clearance: Double): Boolean =
            safe(player, frame, eye, anchor(distance, shift), clearance)
        val maximumDistance = minOf(settings.distance, frame.options.reach)
        val minimumDistance = minOf(0.35, maximumDistance)
        val center = ActionPanelShift(0.0, 0.0)
        val nominalWithinReach = ActionPanelGeometry.allReachable(eye, anchor(maximumDistance, center), rects, frame.options.reach)
        var previousDistance = previous?.distance
        var previousShift = previous?.shift
        if (previous != null && nominalWithinReach) {
            // Relative offsets alone move with a strafing viewer. Preserve the actual safe world pose,
            // bounded by the same forward/side/up envelope as the ordinary obstacle search.
            val origin = anchor(0.0, center).point
            val delta = ActionPanelPoint(previous.anchor.point.x - origin.x, previous.anchor.point.y - origin.y,
                previous.anchor.point.z - origin.z)
            val angle = Math.toRadians(yaw.toDouble())
            val distance = delta.x * sin(angle) - delta.z * cos(angle)
            val side = delta.x * cos(angle) + delta.z * sin(angle)
            if (distance in (minimumDistance - 1e-6)..(maximumDistance + 1e-6) &&
                abs(side) <= settings.obstacleMaxShift + 1e-6 && delta.y in -1e-6..(settings.obstacleMaxShift + 1e-6) &&
                safe(player, frame, eye, previous.anchor)) {
                val rebasedDistance = distance.coerceIn(minimumDistance, maximumDistance)
                val rebasedShift = ActionPanelShift(side.coerceIn(-settings.obstacleMaxShift, settings.obstacleMaxShift),
                    delta.y.coerceIn(0.0, settings.obstacleMaxShift))
                if (!safe(maximumDistance, center, 0.12)) {
                    return Placement(previous.anchor, rebasedDistance, rebasedShift, true)
                }
                if (previous.recovering) {
                    previousDistance = rebasedDistance
                    previousShift = rebasedShift
                }
            }
        }
        val preferredDistance = previousDistance?.coerceAtMost(maximumDistance) ?: maximumDistance
        val shift = ActionPanelGeometry.adaptiveShift(settings.obstacleMaxShift, previousShift) { candidate, clearance ->
            safe(preferredDistance, candidate, clearance)
        }
        if (shift != null) {
            val distance = ActionPanelGeometry.stableDistance(maximumDistance, previousDistance) { safe(it, shift, 0.0) }
            if (distance != null) return Placement(anchor(distance, shift), distance, shift,
                nominalWithinReach && (distance + 1e-6 < maximumDistance || hypot(shift.side, shift.up) > 1e-6))
        }
        val distance = ActionPanelGeometry.firstSafeDistance(maximumDistance, minimumDistance) { safe(it, center, 0.0) }
            ?: throw NoPlacement()
        return Placement(anchor(distance, center), distance, center, nominalWithinReach && distance + 1e-6 < maximumDistance)
    }

    private fun reconcile(player: Player, panel: Panel, duration: Int) {
        val actions = panel.frame.layout.buttons.mapTo(hashSetOf()) { it.action }
        panel.controlKeys.keys.retainAll(actions)
        panel.frame.layout.buttons.forEach { panel.controlKeys.getOrPut(it.action) { (panel.nextControlKey++).toString() } }
        render(player, panel, duration)
        controls.apply(key(player.uniqueId), controlFrame(player, panel, panel.frame))
    }

    private fun render(player: Player, panel: Panel, duration: Int) {
        val parts = mutableListOf<ScenePart<VisualKey<A>>>()
        panel.frame.layout.buttons.forEach { spec ->
            parts += textPart(player, panel, VisualKey(spec.action, null), spec.rect, spec.text, spec.style, spec.visible, panel.hovered == spec.action)
        }
        panel.frame.layout.labels.forEach { spec ->
            parts += textPart(player, panel, VisualKey(null, spec.key), spec.rect, spec.text, spec.style, spec.visible, false)
        }
        panel.scene.apply(SceneFrame(parts, SceneAudience.Private(player.uniqueId, panel.frame.audienceGroup), duration))
    }

    private fun textPart(player: Player, panel: Panel, key: VisualKey<A>, rect: ActionPanelRect, text: Component,
        style: ActionPanelTextStyle, visible: Boolean, hovered: Boolean): ScenePart.Text<VisualKey<A>> {
        val center = ActionPanelGeometry.localToWorld(panel.anchor, rect.center)
        val at = at(player, panel.anchor, center, -rect.height / 2.0 + style.baselineInset)
        return ScenePart.Text(key, ScenePose(at, Transformation(Vector3f(), Quaternionf(), Vector3f(style.scale), Quaternionf())),
            SceneDisplayStyle(billboard = Display.Billboard.FIXED, brightness = Display.Brightness(15, 15),
                interpolationDelay = style.interpolationDelay, interpolationDuration = style.interpolationDuration,
                viewRange = 0.15f, displayWidth = style.displayWidth, displayHeight = style.displayHeight,
                glowing = hovered, glowColor = if (hovered) HOVER_GLOW else null), text,
            SceneTextStyle(lineWidth = style.lineWidth, background = if (hovered) HOVER_BACKGROUND else BACKGROUND,
                shadowed = true, seeThrough = panel.frame.options.depth == ActionPanelDepth.VIEW_OVERLAY), visible)
    }

    private fun controlFrame(player: Player, panel: Panel, frame: ActionPanelFrame<C, A>): SpatialControlFrame<C, A> {
        val specs = frame.layout.buttons.map { spec ->
            val center = ActionPanelGeometry.localToWorld(panel.anchor, spec.rect.center)
            SpatialControl(checkNotNull(panel.controlKeys[spec.action]), spec.action,
                listOf(SpatialHitPlane(at(player, panel.anchor, center, 0.0), spec.rect.width, spec.rect.height, frame.options.frontFaceOnly)),
                if (frame.options.input == ActionPanelInputProfile.EXTERNAL_RAY) null else
                    SpatialNativeTarget(at(player, panel.anchor, center, -spec.rect.height / 2.0), spec.rect.width.toFloat(), spec.rect.height.toFloat()),
                spec.visible, spec.enabled)
        }
        return SpatialControlFrame(frame.context, SceneAudience.Private(player.uniqueId, frame.audienceGroup), SpatialSceneScope.Viewer(player.uniqueId),
            specs, SpatialInputProfile.valueOf(frame.options.input.name), SpatialReach.Fixed(frame.options.reach), frame.inputEnabled)
    }

    private fun validPlacement(player: Player, panel: Panel): Boolean =
        (followSettings(panel.frame.placement) == null && panel.frame.options.depth == ActionPanelDepth.WORLD) ||
            safe(player, panel.frame, player.eyeLocation.point(), panel.anchor)

    private fun updateHover(player: Player, panel: Panel) {
        val hovered = controls.aimed(key(player.uniqueId), player)
        if (panel.hovered != hovered) { panel.hovered = hovered; render(player, panel, 0) }
    }

    private fun updateSuppression(player: Player, panel: Panel) {
        val eye = player.eyeLocation
        val lookedAt = panel.frame.audienceGroup?.allows(player) != false && ActionPanelGeometry.gazeIntersects(eye.point(),
            eye.direction.let { ActionPanelPoint(it.x, it.y, it.z) }, panel.anchor, panel.frame.layout.rectangles(),
            if (panel.lookingAt) 13.0 else 8.0)
        panel.lookingAt = lookedAt
        setSuppressed(panel, panel.frame.options.suppressGuide && lookedAt)
    }

    private fun setSuppressed(panel: Panel, value: Boolean) {
        if (panel.suppressed == value) return
        panel.suppressed = value
        controller.guideSuppressionChanged(panel.owner, panel.frame.context, value)
    }

    private fun sameInputFrame(previous: ActionPanelFrame<C, A>, current: ActionPanelFrame<C, A>): Boolean =
        previous.context == current.context && samePlacement(previous.placement, current.placement) &&
            geometryKey(previous) == geometryKey(current) && previous.options.input == current.options.input &&
            previous.options.reach == current.options.reach && previous.options.frontFaceOnly == current.options.frontFaceOnly &&
            previous.options.depth == current.options.depth &&
            previous.audienceGroup === current.audienceGroup

    private fun geometryKey(frame: ActionPanelFrame<C, A>): List<Any> =
        frame.layout.buttons.map { listOf(it.action, it.rect, it.visible) } +
            frame.layout.labels.map { listOf(it.key, it.rect, it.visible) }

    private fun samePlacement(first: ActionPanelPlacement, second: ActionPanelPlacement): Boolean = when {
        first.worldId != second.worldId -> false
        first is ActionPanelPlacement.Fixed && second is ActionPanelPlacement.Fixed -> first.location() == second.location()
        first is ActionPanelPlacement.FollowTarget && second is ActionPanelPlacement.FollowTarget ->
            first.target == second.target && sameSettings(first.settings, second.settings)
        first is ActionPanelPlacement.FollowView && second is ActionPanelPlacement.FollowView ->
            first.motion == second.motion && sameSettings(first.settings, second.settings)
        else -> false
    }
    private fun sameSettings(first: ActionPanelFollowSettings, second: ActionPanelFollowSettings): Boolean =
        first.distance == second.distance && first.sideOffset == second.sideOffset && first.heightOffset == second.heightOffset &&
            first.obstacleMaxShift == second.obstacleMaxShift && first.movementTolerance == second.movementTolerance
    private fun followSettings(placement: ActionPanelPlacement): ActionPanelFollowSettings? = when (placement) {
        is ActionPanelPlacement.FollowTarget -> placement.settings
        is ActionPanelPlacement.FollowView -> placement.settings
        is ActionPanelPlacement.Fixed -> null
    }

    // Public forwarding methods preserve source/test compatibility; only the shared owner registers input handlers.
    fun onClick(event: PlayerInteractEntityEvent) = controls.onClick(event)
    fun onClickAt(event: PlayerInteractAtEntityEvent) = controls.onClickAt(event)
    fun onAttack(event: PrePlayerAttackEntityEvent) = controls.onAttack(event)
    fun onDamage(event: EntityDamageByEntityEvent) = controls.onDamage(event)
    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = clear(event.player.uniqueId)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onTeleport(event: PlayerTeleportEvent) = clear(event.player.uniqueId)
    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = clear(event.player.uniqueId)
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) = clear(event.player.uniqueId)
    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) = clear(event.player.uniqueId)

    fun clear(playerId: UUID) {
        displays.checkThread()
        removeCurrent(playerId)
        failuresLogged.remove(playerId)
        placementRetries.remove(playerId)
        rendererRetries.remove(playerId)
    }

    private fun removeCurrent(playerId: UUID) {
        val panel = panels.remove(playerId) ?: return
        runCatching { setSuppressed(panel, false) }.onFailure {
            plugin.logger.log(Level.WARNING, "Action panel guide restoration failed for $playerId", it)
        }
        controls.clear(key(playerId))
        panel.scene.close()
    }

    private fun failed(id: UUID, failure: Exception, report: Boolean = true) {
        placementRetries.remove(id)
        rendererRetries[id] = nowNanos() + 5_000_000_000L
        if (failuresLogged.add(id) && report) plugin.logger.log(Level.WARNING, "Action panel renderer failed for $id", failure)
        removeCurrent(id)
    }

    override fun close() {
        displays.checkThread()
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        panels.keys.toList().forEach(::removeCurrent)
        controls.close()
        failuresLogged.clear()
        placementRetries.clear()
        rendererRetries.clear()
        if (ownsDisplays) displays.close()
    }

    private fun key(id: UUID) = SpatialSceneKey(id.toString())

    private fun stableEye(player: Player, offset: ActionPanelPoint): ActionPanelPoint = player.location.let {
        ActionPanelPoint(it.x + offset.x, it.y + offset.y, it.z + offset.z)
    }
    private fun at(player: Player, anchor: ActionPanelAnchor, point: ActionPanelPoint, y: Double): Location =
        Location(player.world, point.x, point.y + y, point.z, anchor.yaw, 0f)
    private fun Location.point(): ActionPanelPoint = ActionPanelPoint(x, y, z)
    private class NoPlacement : RuntimeException(null, null, false, false)
    private companion object {
        val BACKGROUND: Color = Color.fromARGB(190, 15, 23, 30)
        val HOVER_BACKGROUND: Color = Color.fromARGB(225, 76, 57, 24)
        val HOVER_GLOW: Color = Color.fromRGB(255, 204, 64)
    }
}
