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
 * Head turns/crouching only update actual-eye aiming; following panels retain their placement until
 * meaningful body movement, a target/layout change or obstructed geometry requires reconciliation.
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
        var suppressed = false
        var lookingAt = false
        val scene = PacketDisplayScene<VisualKey<A>>(displays)
        val controlKeys = linkedMapOf<A, String>()
        var nextControlKey = 0
        var hovered: A? = null
    }
    private data class Placement(val anchor: ActionPanelAnchor, val distance: Double?, val shift: ActionPanelShift?)
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
        override fun activate(player: Player, scene: SpatialSceneKey, context: C, action: A) = controller.activate(player, context, action)
        override fun blocksRay(player: Player, context: C, block: org.bukkit.block.Block) = controller.blocksRay(player, context, block)
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
        return try { validPlacement(player, panel) && controls.intercept(key(player.uniqueId), player) }
        catch (failure: Exception) { failed(player.uniqueId, failure); false }
    }

    private fun create(player: Player, frame: ActionPanelFrame<C, A>): Panel {
        val body = player.location.point()
        val eye = player.eyeLocation.point()
        val offset = ActionPanelPoint(eye.x - body.x, eye.y - body.y, eye.z - body.z)
        val placed = place(player, frame, offset, null)
        return Panel(player.uniqueId, frame, placed.anchor, body, offset).also {
            it.distance = placed.distance; it.shift = placed.shift
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
        val invalid = settings != null && !worldSpace.safe(player, eye, panel.anchor, rectangles, frame.options.reach,
            0.0, true) { controller.blocksRay(player, frame.context, it) }
        var duration = 0
        if (shapeChanged || placementChanged || (settings != null && moved) || invalid) {
            val placed = place(player, frame, panel.eyeOffset, panel)
            val canInterpolate = settings != null && !shapeChanged && !invalid &&
                ActionPanelGeometry.distance(panel.anchor.point, placed.anchor.point) <= 1.0 &&
                ActionPanelGeometry.yawDelta(panel.anchor.yaw, placed.anchor.yaw) <= 30.0 &&
                worldSpace.clearSweep(player, ActionPanelGeometry.sweptBounds(panel.anchor, placed.anchor,
                    rectangles, ActionPanelGeometry.SURFACE_CLEARANCE))
            duration = if (canInterpolate) 2 else 0
            panel.anchor = placed.anchor
            panel.positionedFrom = player.location.point()
            panel.distance = placed.distance
            panel.shift = placed.shift
        }
        if (contextChanged) {
            setSuppressed(panel, false)
        }
        panel.frame = frame
        reconcile(player, panel, duration)
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
            worldSpace.safe(player, eye, anchor(distance, shift), rects, frame.options.reach, clearance, true) {
                controller.blocksRay(player, frame.context, it)
            }
        val maximumDistance = minOf(settings.distance, frame.options.reach)
        val preferredDistance = previous?.distance?.coerceAtMost(maximumDistance) ?: maximumDistance
        val shift = ActionPanelGeometry.adaptiveShift(settings.obstacleMaxShift, previous?.shift) { candidate, clearance ->
            safe(preferredDistance, candidate, clearance)
        }
        if (shift != null) {
            val distance = ActionPanelGeometry.stableDistance(maximumDistance, previous?.distance) { safe(it, shift, 0.0) }
            if (distance != null) return Placement(anchor(distance, shift), distance, shift)
        }
        val center = ActionPanelShift(0.0, 0.0)
        val distance = ActionPanelGeometry.firstSafeDistance(maximumDistance, minOf(0.35, maximumDistance)) { safe(it, center, 0.0) }
            ?: throw NoPlacement()
        return Placement(anchor(distance, center), distance, center)
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
            SceneTextStyle(lineWidth = style.lineWidth, background = if (hovered) HOVER_BACKGROUND else BACKGROUND, shadowed = true), visible)
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

    private fun validPlacement(player: Player, panel: Panel): Boolean = followSettings(panel.frame.placement) == null ||
        worldSpace.safe(player, player.eyeLocation.point(), panel.anchor, panel.frame.layout.rectangles(), panel.frame.options.reach,
            0.0, true) { controller.blocksRay(player, panel.frame.context, it) }

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
            previous.audienceGroup === current.audienceGroup

    private fun geometryKey(frame: ActionPanelFrame<C, A>): List<Any> =
        frame.layout.buttons.map { listOf(it.action, it.rect, it.visible) } +
            frame.layout.labels.map { listOf(it.key, it.rect, it.visible) }

    private fun samePlacement(first: ActionPanelPlacement, second: ActionPanelPlacement): Boolean = when {
        first.worldId != second.worldId -> false
        first is ActionPanelPlacement.Fixed && second is ActionPanelPlacement.Fixed -> first.location() == second.location()
        first is ActionPanelPlacement.FollowTarget && second is ActionPanelPlacement.FollowTarget ->
            first.target == second.target && sameSettings(first.settings, second.settings)
        first is ActionPanelPlacement.FollowView && second is ActionPanelPlacement.FollowView -> sameSettings(first.settings, second.settings)
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
