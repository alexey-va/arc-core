package ru.arc.paper.inspection

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.api.ArcInspectionFrame
import ru.arc.paper.api.ArcInspectionProvider
import ru.arc.paper.api.ArcInspectionService
import ru.arc.paper.api.InspectionHologramAnchor
import ru.arc.paper.api.InspectionViewMode
import ru.arc.paper.api.InspectionViewPreferences
import ru.arc.paper.audience.NativePaperAudienceEffects
import ru.arc.paper.audience.PaperAudienceEffects
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import java.util.logging.Level

/**
 * The single Paper-side owner for transient inspection cards.
 *
 * Source resolution is deliberately pull-based: the host's existing inspection
 * loop calls [update] and [follow], so providers do not create competing timers
 * or client visuals. All public operations run on Paper's primary thread.
 */
class PaperArcInspectionService internal constructor(
    private val host: Plugin,
    private val displays: PaperPacketDisplays,
    private val audience: PaperAudienceEffects,
) : ArcInspectionService, AutoCloseable, Listener {
    constructor(host: Plugin) : this(host, PaperPacketDisplays(host, "inspection"), NativePaperAudienceEffects)

    private data class SourceKey(val ownerName: String, val id: String)

    private class Source(
        val key: SourceKey,
        val owner: Plugin,
        val priority: Int,
        val order: Long,
        val provider: ArcInspectionProvider,
    ) {
        var warned = false
    }

    private class Viewer(
        val player: Player,
        var preferences: InspectionViewPreferences,
    ) {
        var winner: Source? = null
        var text: PacketTextDisplay? = null
        var bossBar: BossBar? = null
        var shownComponent: Component? = null
        var anchor: InspectionHologramAnchor? = null
    }

    private val registrations = linkedMapOf<SourceKey, Source>()
    private val viewers = linkedMapOf<UUID, Viewer>()
    private var nextOrder = 0L
    private var closed = false

    init {
        check(Bukkit.isPrimaryThread()) { "Inspection service must be created on the Paper primary thread" }
        host.server.pluginManager.registerEvents(this, host)
    }

    override fun register(
        owner: Plugin,
        id: String,
        priority: Int,
        provider: ArcInspectionProvider,
    ): AutoCloseable {
        requireOpen()
        checkThread("Inspection sources must be registered on the Paper primary thread")
        require(isValidId(id)) { "Inspection source id is invalid" }
        val key = SourceKey(owner.name, id)
        require(key !in registrations) { "Inspection source ${key.ownerName}:${key.id} is already registered" }

        val source = Source(key, owner, priority, nextOrder++, provider)
        registrations[key] = source
        // A newly registered, higher-priority source may immediately own a view
        // already being polled by the host.
        viewers.values.toList().forEach { update(it.player, it.preferences) }
        return SourceHandle(source)
    }

    /** Resolve and render the winning source using the caller's current preferences. */
    fun update(player: Player, preferences: InspectionViewPreferences) {
        requireOpen()
        checkThread("Inspection views must be updated on the Paper primary thread")
        if (!player.isOnline || player.isDead) {
            forget(player.uniqueId, player)
            return
        }

        val viewer = viewers.getOrPut(player.uniqueId) { Viewer(player, preferences) }
        viewer.preferences = preferences
        if (preferences.mode == InspectionViewMode.OFF) {
            viewer.winner = null
            clearVisual(viewer, player)
            return
        }

        val resolved = resolve(player)
        viewer.winner = resolved?.first
        val frame = resolved?.second
        if (frame == null || frame.suppressesLowerSources) {
            clearVisual(viewer, player)
            return
        }
        val anchor = frame.hologramAnchor
        if (anchor != null && anchor.worldId != player.world.uid) {
            clearVisual(viewer, player)
            return
        }
        viewer.anchor = anchor

        val component = when (preferences.mode) {
            InspectionViewMode.HOLOGRAM -> frame.hologram
            InspectionViewMode.BOSSBAR -> frame.bossbar
            InspectionViewMode.OFF -> Component.empty()
        }
        if (component == Component.empty()) {
            clearVisual(viewer, player)
            return
        }

        when (preferences.mode) {
            InspectionViewMode.HOLOGRAM -> showHologram(viewer, player, component)
            InspectionViewMode.BOSSBAR -> showBossBar(viewer, player, component)
            InspectionViewMode.OFF -> clearVisual(viewer, player)
        }
    }

    /** Follow viewer-relative cards; anchored cards retain the position resolved in [update]. */
    fun follow(player: Player) {
        requireOpen()
        checkThread("Inspection views must follow on the Paper primary thread")
        val viewer = viewers[player.uniqueId] ?: return
        if (!player.isOnline || player.isDead || player.world != viewer.text?.location?.world && viewer.text != null) {
            forget(player.uniqueId, player)
            return
        }
        if (viewer.preferences.mode != InspectionViewMode.HOLOGRAM) return
        val display = viewer.text ?: return
        if (!display.isValid) {
            viewer.text = null
            viewer.shownComponent = null
            return
        }
        if (viewer.anchor == null) moveDisplay(display, player, viewer.preferences, null)
    }

    /** Clear this viewer's visual and cached state. */
    fun clear(player: Player) {
        requireOpen()
        checkThread("Inspection views must be cleared on the Paper primary thread")
        forget(player.uniqueId, player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = forget(event.player.uniqueId, event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) = forget(event.entity.uniqueId, event.entity as? Player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = forget(event.player.uniqueId, event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) = forget(event.player.uniqueId, event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginDisable(event: PluginDisableEvent) {
        if (closed) return
        if (event.plugin === host) {
            close()
            return
        }
        registrations.values.filter { it.owner === event.plugin }.toList().forEach(::unregister)
    }

    override fun close() {
        if (closed) return
        checkThread("Inspection service must close on the Paper primary thread")
        closed = true
        HandlerList.unregisterAll(this)
        viewers.values.toList().forEach { clearVisual(it, it.player) }
        viewers.clear()
        registrations.clear()
        displays.close()
    }

    private fun resolve(player: Player): Pair<Source, ArcInspectionFrame>? =
        registrations.values.sortedWith(SOURCE_ORDER).firstNotNullOfOrNull { source ->
            try {
                val frame = source.provider.resolve(player)
                source.warned = false
                frame?.let { source to it }
            } catch (failure: Exception) {
                if (!source.warned) {
                    source.warned = true
                    host.logger.log(
                        Level.WARNING,
                        "Inspection source ${source.key.ownerName}:${source.key.id} failed; trying the next source",
                        failure,
                    )
                }
                null
            }
        }

    private fun showHologram(viewer: Viewer, player: Player, component: Component) {
        clearBossBar(viewer, player)
        var display = viewer.text
        if (display == null || !display.isValid || display.location.world != player.world) {
            display?.remove()
            val location = inspectionLocation(player, viewer.preferences, viewer.anchor)
            display = displays.spawnText(location, component).apply {
                isVisibleByDefault = false
                billboard = if (viewer.anchor == null) Display.Billboard.CENTER else Display.Billboard.VERTICAL
                brightness = Display.Brightness(15, 15)
                viewRange = 2.5f
                isSeeThrough = true
                isShadowed = true
                backgroundColor = Color.fromARGB(255, 15, 23, 30)
                lineWidth = 230
                showTo(player)
            }
            viewer.text = display
            viewer.shownComponent = component
            applyScale(display, viewer.preferences.scale)
        } else {
            if (viewer.shownComponent != component) {
                display.text(component)
                viewer.shownComponent = component
            }
            applyScale(display, viewer.preferences.scale)
            display.billboard = if (viewer.anchor == null) Display.Billboard.CENTER else Display.Billboard.VERTICAL
            moveDisplay(display, player, viewer.preferences, viewer.anchor)
        }
    }

    private fun showBossBar(viewer: Viewer, player: Player, component: Component) {
        clearText(viewer)
        val bar = viewer.bossBar
        if (bar == null) {
            viewer.bossBar = BossBar.bossBar(component, 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS)
                .also { audience.showBossBar(player, it) }
        } else if (viewer.shownComponent != component) {
            bar.name(component)
        }
        viewer.shownComponent = component
    }

    private fun moveDisplay(
        display: PacketTextDisplay,
        player: Player,
        preferences: InspectionViewPreferences,
        anchor: InspectionHologramAnchor?,
    ) {
        val target = inspectionLocation(player, preferences, anchor)
        val previous = display.location
        val snap = anchor != null || previous.world != target.world ||
            previous.distanceSquared(target) > MAX_INTERPOLATED_DISTANCE_SQUARED
        display.teleportDuration = if (snap) 0 else INTERPOLATION_TICKS
        display.teleport(target)
    }

    private fun inspectionLocation(
        player: Player,
        preferences: InspectionViewPreferences,
        anchor: InspectionHologramAnchor?,
    ): Location {
        if (anchor != null) return Location(player.world, anchor.x, anchor.y, anchor.z)
        val eye = player.eyeLocation
        val location = eye.clone().add(eye.direction.multiply(RENDER_DISTANCE))
            .add(0.0, BASE_VERTICAL_OFFSET + preferences.verticalOffset, 0.0)
        if (preferences.horizontalOffset != 0.0) {
            val yaw = Math.toRadians(eye.yaw.toDouble())
            location.add(
                -kotlin.math.cos(yaw) * preferences.horizontalOffset,
                0.0,
                -kotlin.math.sin(yaw) * preferences.horizontalOffset,
            )
        }
        return location
    }

    private fun applyScale(display: PacketTextDisplay, scale: Float) {
        display.transformation = Transformation(
            Vector3f(), Quaternionf(), Vector3f(scale, scale, scale), Quaternionf(),
        )
    }

    private fun clearVisual(viewer: Viewer, player: Player?) {
        clearText(viewer)
        clearBossBar(viewer, player)
        viewer.shownComponent = null
        viewer.anchor = null
    }

    private fun clearText(viewer: Viewer) {
        viewer.text?.remove()
        viewer.text = null
    }

    private fun clearBossBar(viewer: Viewer, player: Player?) {
        val bar = viewer.bossBar ?: return
        if (player != null) audience.hideBossBar(player, bar)
        viewer.bossBar = null
    }

    private fun forget(playerId: UUID, player: Player? = Bukkit.getPlayer(playerId)) {
        val viewer = viewers.remove(playerId) ?: return
        clearVisual(viewer, player)
    }

    private fun unregister(source: Source) {
        if (closed || registrations[source.key] !== source) return
        checkThread("Inspection sources must close on the Paper primary thread")
        registrations.remove(source.key)
        viewers.values.filter { it.winner === source }.toList().forEach { update(it.player, it.preferences) }
    }

    private fun requireOpen() = check(!closed) { "Inspection service is closed" }

    private fun checkThread(message: String) = check(Bukkit.isPrimaryThread()) { message }

    private inner class SourceHandle(private val source: Source) : AutoCloseable {
        private var handleClosed = false
        override fun close() {
            if (handleClosed) return
            checkThread("Inspection sources must close on the Paper primary thread")
            handleClosed = true
            unregister(source)
        }
    }

    companion object {
        private const val MAX_ID_LENGTH = 64
        private const val RENDER_DISTANCE = 4.0
        private const val BASE_VERTICAL_OFFSET = 0.65
        private const val INTERPOLATION_TICKS = 2
        private const val MAX_INTERPOLATED_DISTANCE_SQUARED = 64.0
        private val SOURCE_ORDER = compareByDescending<Source> { it.priority }.thenBy { it.order }

        private fun isValidId(value: String): Boolean =
            value.isNotBlank() && value.length <= MAX_ID_LENGTH &&
                value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }
}
