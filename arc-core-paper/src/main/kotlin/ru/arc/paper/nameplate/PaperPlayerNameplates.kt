package ru.arc.paper.nameplate

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.nameplate.PlayerNameplateRegistry
import ru.arc.nameplate.PlayerNameplateSnapshot
import java.util.UUID
import java.util.logging.Level

/**
 * Lifecycle owner that renders one composed TextDisplay nameplate per player.
 *
 * Register rows through the thread-safe [registry]. Bukkit entity creation,
 * visibility reconciliation and [close] run on Paper's primary thread. The
 * service preserves registry state while a target is offline and recreates its
 * transient display after join, respawn, world transfer or passenger loss.
 * Closing removes every owned display and event/task registration.
 */
class PaperPlayerNameplates private constructor(
    private val plugin: Plugin,
    val registry: PlayerNameplateRegistry,
    private val options: PaperNameplateOptions,
    scheduler: TaskScheduler,
    private val displays: PaperNameplateDisplayFactory,
    private val visibility: PaperNameplateVisibilityPolicy,
) : Listener, AutoCloseable {
    private data class RenderedNameplate(
        var revision: Long,
        val display: PaperNameplateDisplay,
        val visibleViewers: MutableSet<UUID> = linkedSetOf(),
    )

    private val tasks = LifecycleTaskScope(scheduler)
    private val rendered = linkedMapOf<UUID, RenderedNameplate>()
    private val reportedFailures = hashSetOf<UUID>()
    private var closed = false

    val activeDisplayCount: Int
        get() = rendered.size

    val visibleViewerCount: Int
        get() = rendered.values.sumOf { it.visibleViewers.size }

    /** Reconciles registry, online targets and per-viewer privacy immediately. */
    fun refreshNow() {
        requirePrimaryThread()
        check(!closed) { "Paper player nameplates are closed" }
        reconcileAll()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        detach(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        detach(event.entity.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChanged(event: PlayerChangedWorldEvent) {
        detach(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin === plugin) close()
    }

    override fun close() {
        if (closed) return
        requirePrimaryThread()
        closed = true
        val failures = mutableListOf<Throwable>()
        runCatching(tasks::close).onFailure(failures::add)
        runCatching { HandlerList.unregisterAll(this) }.onFailure(failures::add)
        rendered.values.forEach { current ->
            runCatching(current.display::close).onFailure(failures::add)
        }
        rendered.clear()
        reportedFailures.clear()
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Could not remove ${failures.size} Paper nameplate display(s)", failures.first())
                .also { combined -> failures.drop(1).forEach(combined::addSuppressed) }
        }
    }

    private fun start() {
        requirePrimaryThread()
        plugin.server.pluginManager.registerEvents(this, plugin)
        try {
            checkNotNull(tasks.runTimer(0L, options.reconcilePeriodTicks, ::reconcileAll)) {
                "Paper nameplate reconciliation task was not scheduled"
            }
        } catch (failure: Throwable) {
            closed = true
            HandlerList.unregisterAll(this)
            runCatching(tasks::close).onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun reconcileAll() {
        if (closed) return
        requirePrimaryThread()
        val targetIds = registry.targetIds()
        reportedFailures.retainAll(targetIds)
        (rendered.keys - targetIds).forEach(::detach)
        targetIds.forEach { playerId ->
            val snapshot = registry.snapshot(playerId) ?: return@forEach
            runCatching { reconcile(snapshot) }
                .onSuccess { reportedFailures.remove(playerId) }
                .onFailure { failure -> handleFailure(playerId, failure) }
        }
    }

    private fun reconcile(snapshot: PlayerNameplateSnapshot) {
        val target = plugin.server.getPlayer(snapshot.playerId)
        if (target == null || !target.isOnline || target.isDead) {
            detach(snapshot.playerId)
            return
        }

        var current = rendered[snapshot.playerId]
        if (current == null || !current.display.isAttachedTo(target)) {
            rendered.remove(snapshot.playerId)?.display?.close()
            current = RenderedNameplate(snapshot.revision, displays.create(target, snapshot.content))
            rendered[snapshot.playerId] = current
        } else if (current.revision != snapshot.revision) {
            current.display.update(snapshot.content)
            current.revision = snapshot.revision
        }

        val onlineViewerIds = hashSetOf<UUID>()
        plugin.server.onlinePlayers.forEach { viewer ->
            onlineViewerIds += viewer.uniqueId
            val shouldShow = visibility.canView(viewer, target)
            val isShown = viewer.uniqueId in current.visibleViewers
            when {
                shouldShow && !isShown -> {
                    current.display.show(viewer)
                    current.visibleViewers += viewer.uniqueId
                }
                !shouldShow && isShown -> {
                    current.display.hide(viewer)
                    current.visibleViewers -= viewer.uniqueId
                }
            }
        }
        current.visibleViewers.retainAll(onlineViewerIds)
    }

    private fun handleFailure(
        playerId: UUID,
        failure: Throwable,
    ) {
        runCatching { rendered.remove(playerId)?.display?.close() }
        if (reportedFailures.add(playerId)) {
            plugin.logger.log(
                Level.WARNING,
                "ARC_NAMEPLATE outcome=reconcile_failed reason=${failure.javaClass.simpleName}",
                failure,
            )
        }
    }

    private fun detach(playerId: UUID) {
        rendered.remove(playerId)?.display?.close()
        reportedFailures.remove(playerId)
    }

    private fun requirePrimaryThread() {
        check(plugin.server.isPrimaryThread) { "Paper player nameplates must be reconciled on the primary thread" }
    }

    companion object {
        /**
         * Opens and schedules one nameplate runtime for the owning plugin.
         * Register the result with [ru.arc.paper.runtime.PaperPluginRuntime.own]
         * or close it explicitly during plugin disable.
         */
        fun open(
            plugin: Plugin,
            registry: PlayerNameplateRegistry = PlayerNameplateRegistry(),
            options: PaperNameplateOptions = PaperNameplateOptions(),
            scheduler: TaskScheduler = Tasks.scheduler,
            displays: PaperNameplateDisplayFactory = NativePaperNameplateDisplayFactory(plugin, options),
            visibility: PaperNameplateVisibilityPolicy = NativePaperNameplateVisibilityPolicy(options),
        ): PaperPlayerNameplates = PaperPlayerNameplates(
            plugin = plugin,
            registry = registry,
            options = options,
            scheduler = scheduler,
            displays = displays,
            visibility = visibility,
        ).also(PaperPlayerNameplates::start)
    }
}
