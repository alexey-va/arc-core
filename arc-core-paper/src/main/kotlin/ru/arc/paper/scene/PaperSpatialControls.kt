package ru.arc.paper.scene

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.*
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.paper.display.PacketDisplayCaptureParticipant
import ru.arc.paper.display.PacketDisplayViewer
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import java.util.logging.Level

/** Native control ownership and actual rendered-plane input. The display owner is always borrowed. */
class PaperSpatialControls<C : Any, A : Any> internal constructor(
    private val plugin: Plugin, private val displays: PaperPacketDisplays,
    private val controller: SpatialControlController<C, A>, private val space: PaperSpatialSpace,
    private val nowNanos: () -> Long,
) : Listener, AutoCloseable {
    constructor(plugin: Plugin, displays: PaperPacketDisplays, controller: SpatialControlController<C, A>) :
        this(plugin, displays, controller, PaperSpatialSpace(), System::nanoTime)

    private class Native(val entity: Interaction) { val shown = mutableSetOf<UUID>() }
    private inner class Scene(var frame: SpatialControlFrame<C, A>) {
        val native = linkedMapOf<String, Native>()
        val accepted = mutableMapOf<UUID, Long>()
    }
    private data class Input(val scene: SpatialSceneKey, val key: String)
    private data class Hit<A : Any>(val control: SpatialControl<A>, val point: Vector, val distance: Double)
    private val scenes = linkedMapOf<SpatialSceneKey, Scene>()
    private val inputs = mutableMapOf<UUID, Input>()
    private var closed = false
    private val participant = object : PacketDisplayCaptureParticipant {
        override fun capture(viewers: List<PacketDisplayViewer>) {
            val players = viewers.map { it.player }
            scenes.toList().forEach { (key, scene) ->
                try { native(key, scene, players) } catch (failure: Exception) {
                    clear(key); plugin.logger.log(Level.WARNING, "Spatial controls failed for ${key.value}", failure)
                }
            }
        }
        override fun ownerClosed() = close()
    }
    init { displays.checkThread(); plugin.server.pluginManager.registerEvents(this, plugin) }

    fun apply(key: SpatialSceneKey, frame: SpatialControlFrame<C, A>) {
        displays.checkThread(); check(!closed)
        if (scenes.isEmpty()) displays.participate(participant)
        val scene = scenes.getOrPut(key) { Scene(frame) }
        if (scene.frame.context != frame.context) removeNative(scene)
        scene.frame = frame
        try { native(key, scene, plugin.server.onlinePlayers.toList()) } catch (failure: Exception) { clear(key); throw failure }
    }

    fun contains(key: SpatialSceneKey): Boolean { displays.checkThread(); return key in scenes }

    fun target(key: SpatialSceneKey, player: Player): SpatialControlTarget<C, A>? {
        return guardedInput(player, key, null) { targetUnsafe(key, player) }
    }

    private fun targetUnsafe(key: SpatialSceneKey, player: Player): SpatialControlTarget<C, A>? {
        displays.checkThread()
        val scene = scenes[key] ?: return null
        val hit = selected(player, scene.frame) ?: return null
        val current = controller.current(player, key) ?: return null
        if (!sameFrame(scene.frame, current) || !current.inputEnabled) return null
        val control = current.controls.firstOrNull { it.key == hit.control.key && it.visible && it.enabled } ?: return null
        return SpatialControlTarget(current.context, control.action)
    }

    /** Current rendered hit only, for hover. It never resolves domain authority or executes an action. */
    fun aimed(key: SpatialSceneKey, player: Player): A? {
        displays.checkThread()
        val frame = scenes[key]?.frame ?: return null
        return selected(player, frame)?.control?.takeIf { frame.inputEnabled && it.enabled }?.action
    }

    fun intercept(key: SpatialSceneKey, player: Player): Boolean = intercept(key, player, null, SpatialInputReason.AIMED_GESTURE)

    private fun intercept(key: SpatialSceneKey, player: Player, expected: A?, reason: SpatialInputReason): Boolean {
        return guardedInput(player, key, false) { interceptUnsafe(key, player, expected, reason) }
    }

    private fun interceptUnsafe(key: SpatialSceneKey, player: Player, expected: A?, reason: SpatialInputReason): Boolean {
        displays.checkThread()
        val scene = scenes[key] ?: return false
        val shown = scene.frame
        val hit = selected(player, shown) ?: return false
        val current = controller.current(player, key)
        if (current == null || !sameFrame(shown, current)) {
            observe(player, key, shown, hit.control.action, false, SpatialInputReason.STALE_CONTEXT)
            return true
        }
        val control = current.controls.firstOrNull { it.key == hit.control.key && it.visible }
        if (control == null) { observe(player, key, shown, hit.control.action, false, SpatialInputReason.HIDDEN_ACTION); return true }
        if (!current.inputEnabled || !control.enabled) {
            observe(player, key, current, control.action, false, SpatialInputReason.INPUT_DISABLED); return true
        }
        val now = nowNanos()
        if (scene.accepted[player.uniqueId]?.let { now - it < 250_000_000L } == true) {
            observe(player, key, current, control.action, false, SpatialInputReason.DUPLICATE); return true
        }
        scene.accepted[player.uniqueId] = now
        observe(player, key, current, control.action, true,
            if (expected != null && expected != control.action) SpatialInputReason.LABEL_PLANE_REMAPPED else reason)
        controller.activate(player, key, current.context, control.action)
        return true
    }

    private fun selected(player: Player, frame: SpatialControlFrame<C, A>): Hit<A>? {
        if (closed || !player.isOnline || player.isDead || !frame.audience.allows(player)) return null
        if (frame.clearanceFootprints.any { !space.clear(player.world, it, player) }) return null
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        var nearest: Hit<A>? = null
        val reach = frame.reach.distance(player)
        frame.controls.filter { it.visible }.forEach { control -> control.planes.forEach { plane ->
            if (plane.center().world?.uid != player.world.uid) return@forEach
            val hit = plane.hit(eye.toVector(), direction, reach) ?: return@forEach
            if (nearest == null || hit.second < nearest!!.distance) nearest = Hit(control, hit.first, hit.second)
        } }
        // Choose the nearest visible plane before evaluating enabled state: disabled controls block fall-through.
        return nearest?.takeIf { space.rayClear(player, eye.toVector(), it.point, 0.0) { block -> controller.blocksRay(player, frame.context, block) } }
    }

    private fun sameFrame(a: SpatialControlFrame<C, A>, b: SpatialControlFrame<C, A>): Boolean =
        a.context == b.context && a.audience == b.audience && a.scope == b.scope && a.input == b.input && a.reach == b.reach &&
            a.clearanceFootprints == b.clearanceFootprints &&
            a.controls.map { listOf(it.key, it.action, it.planes, it.nativeTarget, it.visible) } ==
            b.controls.map { listOf(it.key, it.action, it.planes, it.nativeTarget, it.visible) }

    private fun native(key: SpatialSceneKey, scene: Scene, players: List<Player>) {
        val frame = scene.frame
        val desired = if (frame.input == SpatialInputProfile.EXTERNAL_RAY) emptyList() else frame.controls.filter { it.nativeTarget != null }
        val keys = desired.mapTo(hashSetOf()) { it.key }
        (scene.native.keys - keys).forEach { removeNative(scene, it) }
        desired.forEach { control ->
            val target = checkNotNull(control.nativeTarget)
            val at = target.location()
            val world = checkNotNull(at.world)
            val bounds = BoundingBox(at.x - target.width / 2, at.y, at.z - target.width / 2,
                at.x + target.width / 2, at.y + target.height, at.z + target.width / 2)
            if (!space.known(world, bounds)) { removeNative(scene, control.key); return@forEach }
            if (scene.native[control.key]?.entity?.isValid == false) removeNative(scene, control.key)
            val native = scene.native[control.key] ?: Native(world.spawn(at, Interaction::class.java) {
                it.isVisibleByDefault = false; it.isPersistent = false; it.isInvulnerable = true
                it.setGravity(false); it.isResponsive = true
                it.interactionWidth = target.width; it.interactionHeight = target.height
            }).also {
                scene.native[control.key] = it; inputs[it.entity.uniqueId] = Input(key, control.key)
            }
            val entity = native.entity
            if (entity.interactionWidth != target.width) entity.interactionWidth = target.width
            if (entity.interactionHeight != target.height) entity.interactionHeight = target.height
            if (entity.location != at) check(entity.teleport(at)) { "Spatial Interaction teleport failed" }
            val allowed = players.filter { control.visible && it.world.uid == world.uid && frame.audience.allows(it) && space.known(world, bounds, it) }
            val ids = allowed.mapTo(hashSetOf()) { it.uniqueId }
            (native.shown - ids).forEach { id -> plugin.server.getPlayer(id)?.hideEntity(plugin, entity); native.shown.remove(id) }
            allowed.filter { it.uniqueId !in native.shown }.forEach { it.showEntity(plugin, entity); native.shown.add(it.uniqueId) }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onEarlyClick(event: PlayerInteractEntityEvent) { entityClick(event, true) }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onEarlyClickAt(event: PlayerInteractAtEntityEvent) { entityClick(event, true) }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onClick(event: PlayerInteractEntityEvent) { entityClick(event, false) }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onClickAt(event: PlayerInteractAtEntityEvent) { entityClick(event, false) }

    private fun entityClick(event: PlayerInteractEntityEvent, early: Boolean) {
        val input = inputs[event.rightClicked.uniqueId] ?: return
        guardedInput(event.player, input.scene, Unit) { entityClickUnsafe(event, early) }
    }

    private fun entityClickUnsafe(event: PlayerInteractEntityEvent, early: Boolean) {
        val input = inputs[event.rightClicked.uniqueId] ?: return
        val scene = scenes[input.scene] ?: return
        val frame = scene.frame
        if ((frame.input == SpatialInputProfile.ENTITY_RIGHT_EARLY) != early) return
        val control = frame.controls.firstOrNull { it.key == input.key } ?: return
        if (event.isCancelled && frame.input != SpatialInputProfile.ENTITY_BOTH) {
            observe(event.player, input.scene, frame, control.action, false, SpatialInputReason.EVENT_CANCELLED); return
        }
        if (early && event.hand != EquipmentSlot.HAND) return
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) { observe(event.player, input.scene, frame, control.action, false, SpatialInputReason.OFF_HAND); return }
        if (!frame.audience.allows(event.player)) { observe(event.player, input.scene, frame, control.action, false, SpatialInputReason.OWNER_MISMATCH); return }
        if (!control.visible) { observe(event.player, input.scene, frame, control.action, false, SpatialInputReason.HIDDEN_ACTION); return }
        if (!interceptUnsafe(input.scene, event.player, control.action, SpatialInputReason.LABEL_PLANE))
            observe(event.player, input.scene, frame, control.action, false, SpatialInputReason.RAY_MISS)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onAttack(event: PrePlayerAttackEntityEvent) {
        val input = inputs[event.attacked.uniqueId] ?: return
        guardedInput(event.player, input.scene, Unit) { attackUnsafe(event) }
    }

    private fun attackUnsafe(event: PrePlayerAttackEntityEvent) {
        val input = inputs[event.attacked.uniqueId] ?: return
        val frame = scenes[input.scene]?.frame ?: return
        val cancelled = event.willAttack() && event.isCancelled
        event.isCancelled = true
        if (cancelled || frame.input != SpatialInputProfile.ENTITY_BOTH || !frame.audience.allows(event.player)) return
        val control = frame.controls.firstOrNull { it.key == input.key && it.visible } ?: return
        interceptUnsafe(input.scene, event.player, control.action, SpatialInputReason.LABEL_PLANE)
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDamage(event: EntityDamageByEntityEvent) { if (event.entity.uniqueId in inputs) event.isCancelled = true }
    @EventHandler(priority = EventPriority.MONITOR) fun onQuit(event: PlayerQuitEvent) = clearViewer(event.player)
    @EventHandler(priority = EventPriority.MONITOR) fun onWorld(event: PlayerChangedWorldEvent) = clearViewer(event.player)
    @EventHandler(priority = EventPriority.MONITOR) fun onRespawn(event: PlayerRespawnEvent) = clearViewer(event.player)
    @EventHandler(priority = EventPriority.MONITOR) fun onDeath(event: PlayerDeathEvent) = clearViewer(event.player)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false) fun onTeleport(event: PlayerTeleportEvent) = clearViewer(event.player)
    private fun clearViewer(player: Player) {
        val id = player.uniqueId
        scenes.filterValues { it.frame.scope == SpatialSceneScope.Viewer(id) }.keys.toList().forEach(::clear)
        scenes.values.forEach { scene ->
            scene.accepted.remove(id)
            scene.native.values.forEach { native ->
                if (native.shown.remove(id)) runCatching { player.hideEntity(plugin, native.entity) }
            }
        }
    }
    private fun observe(player: Player, key: SpatialSceneKey, frame: SpatialControlFrame<C, A>, action: A?, accepted: Boolean, reason: SpatialInputReason) =
        controller.inputObserved(player, key, frame.context, action, accepted, reason)

    private inline fun <T> guardedInput(player: Player, key: SpatialSceneKey, rejected: T, input: () -> T): T {
        displays.checkThread()
        val context = scenes[key]?.frame?.context ?: return rejected
        return try { input() } catch (failure: Exception) {
            runCatching { clear(key) }.exceptionOrNull()?.takeIf { it !== failure }?.let(failure::addSuppressed)
            runCatching { controller.inputFailed(player, key, context, failure) }.exceptionOrNull()
                ?.takeIf { it !== failure }?.let(failure::addSuppressed)
            plugin.logger.log(Level.WARNING, "Spatial input failed for ${key.value}", failure)
            rejected
        }
    }
    private fun removeNative(scene: Scene, key: String) {
        val native = scene.native.remove(key) ?: return
        inputs.remove(native.entity.uniqueId)
        native.shown.forEach { plugin.server.getPlayer(it)?.let { player -> runCatching { player.hideEntity(plugin, native.entity) } } }
        native.entity.remove()
    }
    private fun removeNative(scene: Scene) {
        var failure: Exception? = null
        scene.native.keys.toList().forEach { try { removeNative(scene, it) } catch (e: Exception) { if (failure == null) failure = e else failure!!.addSuppressed(e) } }
        failure?.let { throw it }
    }
    fun clear(key: SpatialSceneKey) {
        displays.checkThread()
        val scene = scenes.remove(key) ?: return
        if (scenes.isEmpty()) displays.remove(participant)
        removeNative(scene)
    }
    override fun close() {
        displays.checkThread(); if (closed) return; closed = true
        displays.remove(participant); HandlerList.unregisterAll(this)
        scenes.keys.toList().forEach { runCatching { clear(it) }.onFailure { error -> plugin.logger.log(Level.WARNING, "Spatial cleanup failed", error) } }
        inputs.clear()
    }
}
