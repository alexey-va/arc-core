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
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

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
    private val viewerContent: PaperNameplateViewerContent?,
) : Listener, AutoCloseable {
    private data class RenderedNameplate(
        var revision: Long,
        val display: PaperNameplateDisplay,
        val visibleViewers: MutableSet<UUID> = linkedSetOf(),
    )

    private data class ViewerVariant(
        var content: Component,
        val display: PaperNameplateDisplay,
        val visibleViewers: MutableSet<UUID> = linkedSetOf(),
    )

    private val tasks = LifecycleTaskScope(scheduler)
    private val rendered = linkedMapOf<UUID, RenderedNameplate>()
    private val viewerRendered = linkedMapOf<UUID, MutableList<ViewerVariant>>()
    private val reportedFailures = hashSetOf<UUID>()
    private var closed = false

    val activeDisplayCount: Int
        get() = rendered.size + viewerRendered.values.sumOf { it.size }

    val visibleViewerCount: Int
        get() = rendered.values.sumOf { it.visibleViewers.size } +
            viewerRendered.values.sumOf { variants -> variants.sumOf { it.visibleViewers.size } }

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
        viewerRendered.values.flatten().forEach { current ->
            runCatching(current.display::close).onFailure(failures::add)
        }
        viewerRendered.clear()
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
        ((rendered.keys + viewerRendered.keys) - targetIds).forEach(::detach)
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

        viewerContent?.let {
            reconcileViewerScoped(snapshot, target, it)
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

    private fun reconcileViewerScoped(snapshot: PlayerNameplateSnapshot, target: Player,
        content: PaperNameplateViewerContent) {
        val desired = linkedMapOf<Component, MutableMap<UUID, Player>>()
        plugin.server.onlinePlayers.forEach { viewer ->
            if (!visibility.canView(viewer, target)) return@forEach
            val component = content.render(viewer, target, snapshot.content) ?: return@forEach
            if (component !in desired && desired.size >= MAX_VIEWER_VARIANTS) return@forEach
            desired.getOrPut(component) { linkedMapOf() }[viewer.uniqueId] = viewer
        }
        val variants = viewerRendered.getOrPut(target.uniqueId) { mutableListOf() }
        val unused = variants.toMutableList()
        val assignments = desired.map { (component, audience) ->
            val current = unused.firstOrNull { it.content == component && it.display.isAttachedTo(target) }
                ?: unused.firstOrNull { it.display.isAttachedTo(target) }
                ?: ViewerVariant(component, displays.create(target, component)).also(variants::add)
            unused.remove(current)
            Triple(current, component, audience)
        }
        // Remove old private audiences before reusing handles for different content.
        // A viewer switching groups must never briefly receive the other group's text.
        assignments.forEach { (current, _, audience) ->
            (current.visibleViewers - audience.keys).forEach { id ->
                plugin.server.getPlayer(id)?.let(current.display::hide)
                current.visibleViewers.remove(id)
            }
        }
        unused.forEach { current -> current.display.close(); variants.remove(current) }
        assignments.forEach { (current, component, audience) ->
            if (current.content != component) {
                current.display.update(component)
                current.content = component
            }
            audience.forEach { (id, viewer) ->
                if (current.visibleViewers.add(id)) current.display.show(viewer)
            }
        }
        if (variants.isEmpty()) viewerRendered.remove(target.uniqueId)
    }

    private fun handleFailure(
        playerId: UUID,
        failure: Throwable,
    ) {
        val report = playerId !in reportedFailures
        runCatching { detach(playerId) }
        reportedFailures.add(playerId)
        if (report) {
            plugin.logger.log(
                Level.WARNING,
                "ARC_NAMEPLATE outcome=reconcile_failed reason=${failure.javaClass.simpleName}",
                failure,
            )
        }
    }

    private fun detach(playerId: UUID) {
        val displays = listOfNotNull(rendered.remove(playerId)?.display) +
            viewerRendered.remove(playerId).orEmpty().map { it.display }
        var failure: Throwable? = null
        displays.forEach { display ->
            runCatching(display::close).onFailure { problem ->
                if (failure == null) failure = problem else failure?.addSuppressed(problem)
            }
        }
        reportedFailures.remove(playerId)
        failure?.let { throw it }
    }

    private fun requirePrimaryThread() {
        check(plugin.server.isPrimaryThread) { "Paper player nameplates must be reconciled on the primary thread" }
    }

    companion object {
        private const val MAX_VIEWER_VARIANTS = 16

        /** Opt-in viewer content; legacy [open] callers retain their single-display behavior. */
        fun openViewerScoped(
            plugin: Plugin,
            registry: PlayerNameplateRegistry,
            options: PaperNameplateOptions,
            scheduler: TaskScheduler,
            displays: PaperNameplateDisplayFactory,
            visibility: PaperNameplateVisibilityPolicy,
            content: PaperNameplateViewerContent,
        ): PaperPlayerNameplates = PaperPlayerNameplates(plugin, registry, options, scheduler, displays,
            visibility, content).also(PaperPlayerNameplates::start)

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
            viewerContent = null,
        ).also(PaperPlayerNameplates::start)
    }
}
