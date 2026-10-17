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
import ru.arc.paper.display.PacketTextDisplay
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

    private class Button<A : Any>(
        var spec: ActionPanelButton<A>, val display: PacketTextDisplay, var hitbox: Interaction?,
    ) { var hovered = false }
    private class Label(val display: PacketTextDisplay)
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
        var acceptedAt: Long? = null
        val buttons = linkedMapOf<A, Button<A>>()
        val labels = linkedMapOf<String, Label>()
    }
    private data class NativeInput<A : Any>(val owner: UUID, val action: A)
    private data class Placement(val anchor: ActionPanelAnchor, val distance: Double?, val shift: ActionPanelShift?)
    private inner class PlacementRetry(val frame: ActionPanelFrame<C, A>, val body: ActionPanelPoint, val until: Long)
    private val panels = linkedMapOf<UUID, Panel>()
    private val inputs = mutableMapOf<UUID, NativeInput<A>>()
    private val failuresLogged = mutableSetOf<UUID>()
    private val placementRetries = mutableMapOf<UUID, PlacementRetry>()
    private val rendererRetries = mutableMapOf<UUID, Long>()
    private var closed = false

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
            panels[player.uniqueId]?.takeIf { existing ->
                existing.buttons.values.any { !it.display.isValid || it.hitbox?.isValid == false } ||
                    existing.labels.values.any { !it.display.isValid }
            }?.let { removeCurrent(player.uniqueId) }
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
            val button = selected(player, panel, validatePlacement = true) ?: return null
            val current = controller.frame(player) ?: run { clear(player.uniqueId); return null }
            if (!sameInputFrame(panel.frame, current) || !current.inputEnabled ||
                current.layout.buttons.none { it.action == button.spec.action && it.visible && it.enabled }) {
                refresh(player)
                null
            } else ActionPanelTarget(current.context, button.spec.action)
        } catch (failure: Exception) { failed(player.uniqueId, failure); null }
    }

    /** Consumes only an aimed visible control; unrelated gameplay/tool gestures return false. */
    fun intercept(player: Player): Boolean = intercept(player, null, ActionPanelInputReason.AIMED_GESTURE)

    private fun intercept(player: Player, expected: A?, reason: ActionPanelInputReason): Boolean {
        displays.checkThread()
        if (closed) return false
        val panel = panels[player.uniqueId] ?: return false
        try {
            val selected = selected(player, panel, includeDisabled = true, validatePlacement = true) ?: return false
            val current = controller.frame(player) ?: run { clear(player.uniqueId); return true }
            if (!sameInputFrame(panel.frame, current)) {
                observe(player, panel, selected.spec.action, false, ActionPanelInputReason.STALE_CONTEXT)
                refresh(player)
                return true
            }
            val currentButton = current.layout.buttons.firstOrNull { it.action == selected.spec.action && it.visible }
            if (currentButton == null) {
                observe(player, panel, selected.spec.action, false, ActionPanelInputReason.HIDDEN_ACTION)
                refresh(player)
                return true
            }
            if (!current.inputEnabled || !currentButton.enabled) {
                observe(player, panel, selected.spec.action, false, ActionPanelInputReason.INPUT_DISABLED)
                return true
            }
            val now = nowNanos()
            if (panel.acceptedAt?.let { now - it < DEDUPLICATION_NANOS } == true) {
                observe(player, panel, selected.spec.action, false, ActionPanelInputReason.DUPLICATE)
                return true
            }
            panel.acceptedAt = now
            val actualReason = if (expected != null && expected != selected.spec.action) ActionPanelInputReason.LABEL_PLANE_REMAPPED else reason
            observe(player, panel, selected.spec.action, true, actualReason)
            controller.activate(player, current.context, selected.spec.action)
            return true
        } catch (failure: Exception) { failed(player.uniqueId, failure); return false }
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
            // Packet labels retain continuity, but native IDs are input capabilities for this
            // exact rendered context. Old queued entity clicks must not activate a new session.
            panel.buttons.values.forEach { removeHitbox(player, it) }
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
        val frame = panel.frame
        val actions = frame.layout.buttons.mapTo(hashSetOf()) { it.action }
        (panel.buttons.keys - actions).forEach { action -> panel.buttons.remove(action)?.let { removeButton(player, it) } }
        frame.layout.buttons.forEach { spec ->
            val center = ActionPanelGeometry.localToWorld(panel.anchor, spec.rect.center)
            val labelAt = at(player, panel.anchor, center, -spec.rect.height / 2.0 + spec.style.baselineInset)
            val button = panel.buttons[spec.action] ?: Button(spec, displays.spawnText(labelAt, spec.text), null).also {
                panel.buttons[spec.action] = it
            }
            button.spec = spec
            updateLabel(player, button.display, labelAt, spec.text, spec.style, spec.visible, frame, duration, button.hovered)
            if (frame.options.input == ActionPanelInputProfile.EXTERNAL_RAY) {
                removeHitbox(player, button)
            } else {
                val hitboxAt = at(player, panel.anchor, center, -spec.rect.height / 2.0)
                val hitbox = button.hitbox ?: player.world.spawn(hitboxAt, Interaction::class.java) {
                    it.isVisibleByDefault = false
                    it.isPersistent = false
                    it.isInvulnerable = true
                    it.setGravity(false)
                    it.isResponsive = true
                }.also {
                    button.hitbox = it
                    inputs[it.uniqueId] = NativeInput(player.uniqueId, spec.action)
                }
                hitbox.interactionWidth = spec.rect.width.toFloat()
                hitbox.interactionHeight = spec.rect.height.toFloat()
                if (hitbox.location != hitboxAt) check(hitbox.teleport(hitboxAt)) { "Action panel Interaction teleport failed" }
                if (spec.visible) player.showEntity(plugin, hitbox) else player.hideEntity(plugin, hitbox)
            }
        }
        val keys = frame.layout.labels.mapTo(hashSetOf()) { it.key }
        (panel.labels.keys - keys).forEach { panel.labels.remove(it)?.display?.remove() }
        frame.layout.labels.forEach { spec ->
            val center = ActionPanelGeometry.localToWorld(panel.anchor, spec.rect.center)
            val location = at(player, panel.anchor, center, -spec.rect.height / 2.0 + spec.style.baselineInset)
            val label = panel.labels[spec.key] ?: Label(displays.spawnText(location, spec.text)).also { panel.labels[spec.key] = it }
            updateLabel(player, label.display, location, spec.text, spec.style, spec.visible, frame, duration, false)
        }
    }

    private fun updateLabel(
        player: Player, display: PacketTextDisplay, location: Location, text: Component, style: ActionPanelTextStyle,
        visible: Boolean, frame: ActionPanelFrame<C, A>, duration: Int, hovered: Boolean,
    ) {
        display.isVisibleByDefault = false
        display.audienceGroup = frame.audienceGroup
        display.billboard = Display.Billboard.FIXED
        display.brightness = Display.Brightness(15, 15)
        display.isShadowed = true
        display.isSeeThrough = false
        display.isDefaultBackground = false
        display.alignment = org.bukkit.entity.TextDisplay.TextAlignment.CENTER
        display.textOpacity = 255.toByte()
        display.lineWidth = style.lineWidth
        display.displayWidth = style.displayWidth
        display.displayHeight = style.displayHeight
        display.viewRange = 0.15f
        display.interpolationDelay = style.interpolationDelay
        display.interpolationDuration = style.interpolationDuration
        display.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(style.scale), Quaternionf())
        display.backgroundColor = if (hovered) HOVER_BACKGROUND else BACKGROUND
        display.isGlowing = hovered
        display.glowColorOverride = if (hovered) HOVER_GLOW else null
        if (display.text() != text) display.text(text)
        if (display.location != location) { display.teleportDuration = duration; display.teleport(location) }
        if (visible) display.showTo(player) else display.hideFrom(player)
    }

    private fun selected(player: Player, panel: Panel, includeDisabled: Boolean = false, validatePlacement: Boolean = false): Button<A>? {
        if (!player.isOnline || player.isDead || player.world.uid != panel.frame.placement.worldId || player.uniqueId != panel.owner) return null
        if (panel.frame.audienceGroup?.allows(player) == false) return null
        if (!includeDisabled && !panel.frame.inputEnabled) return null
        val eye = player.eyeLocation
        if (panel.frame.options.frontFaceOnly) {
            val angle = Math.toRadians(panel.anchor.yaw.toDouble())
            if (eye.direction.x * sin(angle) - eye.direction.z * cos(angle) <= 1e-8) return null
        }
        if (followSettings(panel.frame.placement) != null && validatePlacement &&
            !worldSpace.safe(player, eye.point(), panel.anchor, panel.frame.layout.rectangles(), panel.frame.options.reach,
                0.0, true) { controller.blocksRay(player, panel.frame.context, it) }) return null
        val candidates = panel.buttons.values.filter { it.spec.visible && it.display.isValid && it.hitbox?.isValid != false }
        val planes = candidates.map { button ->
            ActionPanelPlane(button.spec.action, ActionPanelGeometry.localToWorld(panel.anchor, button.spec.rect.center),
                panel.anchor.yaw, button.spec.rect.width, button.spec.rect.height,
                includeDisabled || (button.spec.enabled && panel.frame.inputEnabled))
        }
        val hit = ActionPanelGeometry.nearestHit(eye.point(), eye.direction.let { ActionPanelPoint(it.x, it.y, it.z) },
            planes, panel.frame.options.reach, null) ?: return null
        if (!worldSpace.rayClear(player, eye.point(), hit.point) { controller.blocksRay(player, panel.frame.context, it) }) return null
        return candidates.firstOrNull { it.spec.action == hit.key }
    }

    private fun updateHover(player: Player, panel: Panel) {
        val selected = selected(player, panel)
        panel.buttons.values.forEach { button ->
            val hovered = button === selected
            if (button.hovered != hovered) {
                button.hovered = hovered
                button.display.isGlowing = hovered
                button.display.glowColorOverride = if (hovered) HOVER_GLOW else null
                button.display.backgroundColor = if (hovered) HOVER_BACKGROUND else BACKGROUND
            }
        }
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onClick(event: PlayerInteractEntityEvent) = entityClick(event)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onClickAt(event: PlayerInteractAtEntityEvent) = entityClick(event)
    private fun entityClick(event: PlayerInteractEntityEvent) {
        val input = inputs[event.rightClicked.uniqueId] ?: return
        val panel = panels[input.owner] ?: return
        if (event.isCancelled && panel.frame.options.input != ActionPanelInputProfile.ENTITY_BOTH) {
            observe(event.player, panel, input.action, false, ActionPanelInputReason.EVENT_CANCELLED)
            return
        }
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) { observe(event.player, panel, input.action, false, ActionPanelInputReason.OFF_HAND); return }
        if (event.player.uniqueId != input.owner) { observe(event.player, panel, input.action, false, ActionPanelInputReason.OWNER_MISMATCH); return }
        if (panel.buttons[input.action]?.spec?.visible != true) { observe(event.player, panel, input.action, false, ActionPanelInputReason.HIDDEN_ACTION); return }
        if (!intercept(event.player, input.action, ActionPanelInputReason.LABEL_PLANE))
            observe(event.player, panel, input.action, false, ActionPanelInputReason.RAY_MISS)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onAttack(event: PrePlayerAttackEntityEvent) {
        val input = inputs[event.attacked.uniqueId] ?: return
        val panel = panels[input.owner] ?: return
        val cancelled = event.willAttack() && event.isCancelled
        event.isCancelled = true
        if (cancelled || panel.frame.options.input != ActionPanelInputProfile.ENTITY_BOTH || event.player.uniqueId != input.owner) return
        intercept(event.player, input.action, ActionPanelInputReason.LABEL_PLANE)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDamage(event: EntityDamageByEntityEvent) { if (event.entity.uniqueId in inputs) event.isCancelled = true }
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
        val player = plugin.server.getPlayer(playerId)
        panel.buttons.values.forEach { removeButton(player, it) }
        panel.labels.values.forEach { it.display.remove() }
    }

    private fun removeHitbox(player: Player?, button: Button<A>) {
        val hitbox = button.hitbox ?: return
        button.hitbox = null
        inputs.remove(hitbox.uniqueId)
        if (player != null) runCatching { player.hideEntity(plugin, hitbox) }
        hitbox.remove()
    }
    private fun removeButton(player: Player?, button: Button<A>) { removeHitbox(player, button); button.display.remove() }

    private fun observe(player: Player, panel: Panel, action: A?, accepted: Boolean, reason: ActionPanelInputReason) =
        controller.inputObserved(player, panel.frame.context, action, accepted, reason)

    private fun failed(id: UUID, failure: Exception) {
        removeCurrent(id)
        placementRetries.remove(id)
        rendererRetries[id] = nowNanos() + 5_000_000_000L
        if (failuresLogged.add(id)) plugin.logger.log(Level.WARNING, "Action panel renderer failed for $id", failure)
    }

    override fun close() {
        displays.checkThread()
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        panels.keys.toList().forEach(::removeCurrent)
        inputs.clear()
        failuresLogged.clear()
        placementRetries.clear()
        rendererRetries.clear()
        if (ownsDisplays) displays.close()
    }

    private fun stableEye(player: Player, offset: ActionPanelPoint): ActionPanelPoint = player.location.let {
        ActionPanelPoint(it.x + offset.x, it.y + offset.y, it.z + offset.z)
    }
    private fun at(player: Player, anchor: ActionPanelAnchor, point: ActionPanelPoint, y: Double): Location =
        Location(player.world, point.x, point.y + y, point.z, anchor.yaw, 0f)
    private fun Location.point(): ActionPanelPoint = ActionPanelPoint(x, y, z)
    private class NoPlacement : RuntimeException(null, null, false, false)
    private companion object {
        const val DEDUPLICATION_NANOS = 250_000_000L
        val BACKGROUND: Color = Color.fromARGB(190, 15, 23, 30)
        val HOVER_BACKGROUND: Color = Color.fromARGB(225, 76, 57, 24)
        val HOVER_GLOW: Color = Color.fromRGB(255, 204, 64)
    }
}
