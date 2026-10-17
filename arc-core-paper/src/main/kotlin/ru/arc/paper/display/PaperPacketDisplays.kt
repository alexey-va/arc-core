package ru.arc.paper.display

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.BudgetedTaskQueue
import ru.arc.paper.api.ArcAfkProvider
import ru.arc.paper.api.ArcNativeCleanupService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.function.BooleanSupplier
import java.util.UUID

/**
 * Owns ephemeral Block/Item/TextDisplay visuals for one plugin/module lifecycle.
 *
 * Construct, mutate handles and close on the server thread after installing core
 * scheduling. The installed PacketEvents 2.12.1 plugin is required. No server
 * entities or chunk tickets are created. Coalesced budgeted sweeps capture immutable frames and
 * current player connections/received chunks; encoding, delta calculation and
 * writes run on each connection's Netty event loop. Slow connections keep only
 * the latest pending frame. Unchanged frames send no packets.
 *
 * Visibility combines the handle's explicit audience, world, range and received
 * chunks. Dead or detached players are omitted until their entity is valid again.
 * Client chunk eviction/respawn and connection replacement replay the
 * current frame. [closeAsync] cancels this owner's tasks/listeners and observes
 * native retirement plus connection cleanup, including after a partial write failure. A visual is not
 * persistent state: callers retain their own durable anchor/crate meaning.
 */
class PaperPacketDisplays internal constructor(
    private val plugin: Plugin,
    private val transport: PacketDisplayTransport,
    private val tasks: LifecycleTaskScope,
    private val audience: PacketDisplayAudienceSource,
    private val work: BudgetedTaskQueue = BudgetedTaskQueue(tasks),
    private val ownsWork: Boolean = true,
    private val cleanup: ArcNativeCleanupService = requireNotNull(plugin.server.servicesManager.load(ArcNativeCleanupService::class.java)) { "ArcNativeCleanupService is unavailable" },
    private val currentTick: java.util.function.LongSupplier? = null,
) : AutoCloseable, Listener {
    constructor(plugin: Plugin) : this(plugin, "displays")

    /** [source] is a stable feature name for the server-wide visual traffic budget. */
    constructor(plugin: Plugin, source: String) : this(
        plugin, PacketEventsDisplayTransport(plugin, source), LifecycleTaskScope(), BukkitPacketDisplayAudience,
        currentTick = java.util.function.LongSupplier { Bukkit.getCurrentTick().toLong() },
    )

    /** Share the consumer runtime's aggregate native budget across visuals and other work. */
    constructor(plugin: Plugin, source: String, work: BudgetedTaskQueue) : this(
        plugin, PacketEventsDisplayTransport(plugin, source), LifecycleTaskScope(), BukkitPacketDisplayAudience,
        work, false,
        currentTick = java.util.function.LongSupplier { Bukkit.getCurrentTick().toLong() },
    )

    private data class ViewerState(val connection: PacketDisplayConnection, val worldId: UUID)
    private val handles = ConcurrentHashMap<Int, PacketDisplay>()
    private val audienceGroups = ConcurrentHashMap.newKeySet<PacketDisplayAudienceGroup>()
    private val viewers = ConcurrentHashMap<UUID, ViewerState>()
    private val resetChunks = mutableMapOf<UUID, MutableSet<Long>>()
    private val resetViewers = mutableSetOf<UUID>()
    private val captureParticipants = ConcurrentHashMap.newKeySet<PacketDisplayCaptureParticipant>()
    internal var closed = false
        private set
    private val viewerEpochs = mutableMapOf<UUID, Long>()
    private var capture: CompletableFuture<Unit>? = null
    internal var visibilityRevision = 0L
        private set
    internal fun visibilityChanged() { visibilityRevision++ }
    private var completion: CompletableFuture<Void>? = null
    // Each connection has at most one prepared submission in this owner sweep.
    private val submissions = ConcurrentHashMap.newKeySet<CompletableFuture<Void>>()

    init {
        checkThread()
        plugin.server.pluginManager.registerEvents(this, plugin)
        tasks.runTimer(1L, 1L) { refresh() }
    }

    fun spawnBlock(location: Location, block: BlockData): PacketBlockDisplay {
        checkSpawn(location)
        return PacketBlockDisplay(this, transport.nextEntityId(), location, block).also { handles[it.entityId] = it }
    }

    fun spawnItem(location: Location, item: ItemStack): PacketItemDisplay {
        checkSpawn(location)
        return PacketItemDisplay(this, transport.nextEntityId(), location, item).also { handles[it.entityId] = it }
    }

    fun spawnText(location: Location, text: Component): PacketTextDisplay {
        checkSpawn(location)
        return PacketTextDisplay(this, transport.nextEntityId(), location, text).also { handles[it.entityId] = it }
    }

    /** Creates a scene gate; configure it before the first frame and close it with the scene. */
    fun createAudienceGroup(predicate: (Player) -> Boolean): PacketDisplayAudienceGroup {
        checkThread()
        check(!closed) { "Packet display owner is closed" }
        return PacketDisplayAudienceGroup(this, predicate).also(audienceGroups::add)
    }

    internal fun checkThread() = audience.checkThread()
    internal fun blockStateId(block: BlockData): Int = transport.blockStateId(block)
    internal fun itemSnapshot(item: ItemStack) = transport.itemSnapshot(item)
    internal fun remove(display: PacketDisplay) { handles.remove(display.entityId, display) }
    internal fun remove(group: PacketDisplayAudienceGroup) { audienceGroups.remove(group) }
    internal fun participate(participant: PacketDisplayCaptureParticipant) { checkThread(); check(!closed); captureParticipants.add(participant) }
    internal fun remove(participant: PacketDisplayCaptureParticipant) { checkThread(); captureParticipants.remove(participant) }
    internal fun captureFailure(failure: Exception) { plugin.logger.log(java.util.logging.Level.WARNING, "Spatial scene refresh failed", failure) }

    private fun checkSpawn(location: Location) {
        checkThread()
        check(!closed) { "Packet display owner is closed" }
        requireNotNull(location.world) { "A packet display must belong to a world" }
    }

    /** Starts one coalesced sweep. Completion includes off-thread frame admission, not delivery. */
    internal fun refresh(): CompletableFuture<Unit> {
        checkThread()
        capture?.takeUnless { it.isDone }?.let { return it }
        if (closed) return CompletableFuture.failedFuture(CancellationException("Packet display owner is closed"))
        if (handles.isEmpty() && viewers.isEmpty() && captureParticipants.isEmpty()) {
            resetChunks.clear(); resetViewers.clear()
            return CompletableFuture.completedFuture(Unit)
        }
        val admissions = arrayListOf<CompletableFuture<Void>>()
        val steps = captureSteps(admissions)
        val result = work.submit({ !closed }) {
            if (steps.hasNext()) { steps.next(); false } else true
        }.thenCompose {
            // Keep one prepared sweep while Netty admits it, without spending native quanta polling.
            CompletableFuture.allOf(*admissions.toTypedArray()).thenApply { Unit }
        }.also { result ->
            capture = result
            result.whenComplete { _, failure ->
                if (failure != null && failure !is CancellationException) {
                    plugin.logger.log(java.util.logging.Level.WARNING, "Packet display capture failed; the next sweep will retry", failure)
                }
            }
        }
        currentTick?.let(work::drainAvailable)
        return result
    }

    private fun track(submission: CompletableFuture<Void>) {
        submissions.add(submission)
        submission.whenComplete { _, failure ->
            if (failure != null) terminalFailures?.add(failure)
            submissions.remove(submission)
            if (failure != null && !closed) plugin.logger.log(java.util.logging.Level.WARNING, "Packet display admission failed", failure)
        }
    }

    private fun captureSteps(admissions: MutableList<CompletableFuture<Void>>): Iterator<Unit> = sequence {
        val currentAudience = arrayListOf<PacketDisplayViewer>()
        val audienceById = hashMapOf<UUID, PacketDisplayViewer>()
        val passengerSnapshots = hashMapOf<Int, List<Int>>()
        val liveVehiclesByWorld = hashMapOf<UUID, MutableSet<Int>>()
        val epochs = hashMapOf<UUID, Long>()
        yieldAll(audience.captureSteps { viewer ->
            currentAudience.add(viewer)
            audienceById[viewer.id] = viewer
            passengerSnapshots[viewer.entityId] = viewer.passengerEntityIds
            liveVehiclesByWorld.getOrPut(viewer.worldId, ::hashSetOf).add(viewer.entityId)
            epochs[viewer.id] = viewerEpochs[viewer.id] ?: 0L
        })
        for (participant in captureParticipants) {
            yieldAll(participant.captureSteps(currentAudience))
            yield(Unit)
        }
        val frames = arrayListOf<Pair<PacketDisplay, PacketDisplayFrame>>()
        for (display in handles.values) {
            val target = display.attachmentTargetId?.let(audienceById::get)
            display.frame(target)?.let { frames.add(display to it) }
            yield(Unit)
        }
        for (id in viewers.keys) {
            if (id !in audienceById) forgetViewer(id)?.let(admissions::add)
            yield(Unit)
        }
        for (viewer in currentAudience) {
            val visibility = visibilityRevision
            val groupVisibility = hashMapOf<PacketDisplayAudienceGroup, Boolean>()
            val desired = arrayListOf<PacketDisplayFrame>()
            val desiredPassengers = hashMapOf<Int, MutableList<Int>>()
            val nativePassengers = hashMapOf<Int, List<Int>>()
            for ((display, frame) in frames) {
                val targetId = display.attachmentTargetId
                val carrier = targetId?.let(audienceById::get)
                val carrierVisible = targetId == null || targetId == viewer.id ||
                    (carrier != null && audience.isCurrent(carrier) && viewer.id in carrier.trackedBy && viewer.player.canSee(carrier.player))
                if (carrierVisible && frame.chunkKey in viewer.sentChunks && display.visibleTo(viewer, frame) &&
                    (display.audienceGroup?.let { group -> groupVisibility.getOrPut(group) { group.allows(viewer.player) } } ?: true)) {
                    desired.add(frame)
                    frame.attachmentVehicleId?.let { vehicleId ->
                        desiredPassengers.getOrPut(vehicleId, ::arrayListOf).add(frame.entityId)
                        nativePassengers[vehicleId] = passengerSnapshots[vehicleId].orEmpty()
                    }
                }
                yield(Unit)
            }
            // A world/respawn/chunk transition during a sliced capture must not publish the old view.
            if (!audience.isCurrent(viewer) || visibility != visibilityRevision || epochs[viewer.id] != (viewerEpochs[viewer.id] ?: 0L)) { yield(Unit); continue }
            val previous = viewers[viewer.id]
            if (desired.isEmpty() && previous == null) {
                resetChunks.remove(viewer.id); resetViewers.remove(viewer.id)
                yield(Unit); continue
            }
            val connection = transport.connection(viewer.player)
            if (connection == null) { forgetViewer(viewer.id)?.let(admissions::add); yield(Unit); continue }
            if (previous != null && previous.connection.identity !== connection.identity) {
                previous.connection.closeAsync().also { admissions.add(it); track(it) }
            }
            // These collections are detached and never mutated after handoff. Sorting/copies/merge run on Netty.
            connection.submitViewAsync(desired, viewer.view, resetChunks.remove(viewer.id).orEmpty(),
                resetViewers.remove(viewer.id) || (previous != null && previous.worldId != viewer.worldId),
                desiredPassengers, nativePassengers, liveVehiclesByWorld[viewer.worldId].orEmpty())
                .also { admissions.add(it); track(it) }
            viewers[viewer.id] = ViewerState(connection, viewer.worldId)
            yield(Unit)
        }
    }.iterator()

    @EventHandler
    fun onChunkLoad(event: PlayerChunkLoadEvent) = resetChunk(event.player, event.chunk.chunkKey)

    @EventHandler
    fun onChunkUnload(event: PlayerChunkUnloadEvent) = resetChunk(event.player, event.chunk.chunkKey)

    private fun resetChunk(player: Player, chunkKey: Long) {
        if (!closed) {
            val id = player.uniqueId
            viewerEpochs[id] = (viewerEpochs[id] ?: 0L) + 1
            if (viewers.containsKey(id)) resetChunks.getOrPut(id, ::mutableSetOf).add(chunkKey)
        }
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) = resetViewer(event.player)

    @EventHandler
    fun onRespawn(event: PlayerPostRespawnEvent) = resetViewer(event.player)

    private fun resetViewer(player: Player) {
        if (closed) return
        val id = player.uniqueId
        viewerEpochs[id] = (viewerEpochs[id] ?: 0L) + 1
        resetViewers.add(id)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        resetViewer(event.player)
        forgetViewer(event.player.uniqueId)
        transport.forget(event.player)
    }

    private fun forgetViewer(id: UUID): CompletableFuture<Void>? {
        val admission = viewers.remove(id)?.connection?.closeAsync()?.also(::track)
        resetChunks.remove(id)
        resetViewers.remove(id)
        return admission
    }

    /** Detach an owner's native handles first, then retire them through the same host lifetime. */
    internal fun retireNative(owner: String, steps: Iterator<Unit>): CompletableFuture<Void> {
        checkThread()
        val result = try {
            cleanup.submit(owner, BooleanSupplier { if (steps.hasNext()) { steps.next(); false } else true }).toCompletableFuture()
        } catch (failure: Throwable) { CompletableFuture.failedFuture<Void>(failure) }
        track(result)
        return result.copy()
    }

    /**
     * Seals capture immediately. The host-owned cursor outlives consumer tasks and removes one
     * handle/participant step per quantum, then waits by polling completed Netty cleanup futures.
     * Success means cleanup packets were admitted/flushed (not a client acknowledgement). Host
     * stop/cancellation or a lost channel reports failure; it never claims a completed cleanup.
     * Observe this future before starting a replacement generation. No main-thread wait occurs.
     */
    fun closeAsync(): CompletableFuture<Void> {
        checkThread()
        completion?.let { return it.copy() }
        val result = CompletableFuture<Void>()
        completion = result
        closed = true
        capture?.cancel(false)
        if (ownsWork) work.close()
        tasks.close()
        HandlerList.unregisterAll(this)
        val closing = sequence {
            for (participant in captureParticipants) { yieldAll(participant.closeSteps()); yield(Unit) }
            for (group in audienceGroups) { group.close(); yield(Unit) }
            for (display in handles.values) { display.remove(); yield(Unit) }
            for ((id, state) in viewers) { track(state.connection.closeAsync()); viewers.remove(id, state); yield(Unit) }
            while (submissions.isNotEmpty()) yield(Unit)
        }.iterator()
        var failed: Throwable? = null
        // Capture future failures independently from the polling set: completion callbacks can
        // remove an already-failed connection before the next native quantum.
        val cleanupFailures = ConcurrentHashMap.newKeySet<Throwable>()
        terminalFailures = cleanupFailures
        try {
            cleanup.submit("packet-displays", BooleanSupplier {
                if (closing.hasNext()) { closing.next(); false } else true
            }).whenComplete { _, failure ->
                failed = failure ?: cleanupFailures.firstOrNull()
                try { transport.close() } catch (error: Throwable) {
                    if (failed == null) failed = error else failed!!.addSuppressed(error)
                }
                resetChunks.clear(); resetViewers.clear(); viewerEpochs.clear()
                if (failed == null) result.complete(null) else result.completeExceptionally(failed!!)
            }
        } catch (failure: Throwable) {
            runCatching { transport.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            result.completeExceptionally(failure)
        }
        return result.copy()
    }

    @Volatile private var terminalFailures: MutableSet<Throwable>? = null

    /** Dispatch-only compatibility method; use [closeAsync] to observe the actual drain. */
    override fun close() { closeAsync() }
}

/** Internal typed participant in this owner's existing main-thread capture, never a second timer. */
internal interface PacketDisplayCaptureParticipant {
    fun captureSteps(viewers: List<PacketDisplayViewer>): Iterator<Unit>
    fun closeSteps(): Iterator<Unit>
}

/** Server-thread-only audience port; the connection queue never receives these Bukkit references. */
internal interface PacketDisplayAudienceSource {
    fun checkThread()
    fun captureSteps(accept: (PacketDisplayViewer) -> Unit): Iterator<Unit>
    fun isCurrent(viewer: PacketDisplayViewer): Boolean
}

internal data class PacketDisplayViewer(
    val player: Player,
    val id: UUID,
    val worldId: UUID,
    val entityId: Int,
    val trackedBy: Set<UUID>,
    val x: Double,
    val y: Double,
    val z: Double,
    val sentChunks: Set<Long>,
    val passengerEntityIds: List<Int> = emptyList(),
    val view: PacketDisplayView? = null,
)

internal object BukkitPacketDisplayAudience : PacketDisplayAudienceSource {
    override fun isCurrent(viewer: PacketDisplayViewer): Boolean = viewer.player.isValid &&
        viewer.player.world.uid == viewer.worldId && viewer.player.entityId == viewer.entityId
    override fun checkThread() { check(Bukkit.isPrimaryThread()) { "Packet display state must be accessed on the server thread" } }
    override fun captureSteps(accept: (PacketDisplayViewer) -> Unit): Iterator<Unit> = sequence {
        val afk = Bukkit.getServicesManager().load(ArcAfkProvider::class.java)
        // Paper's collection is a live view. If its iterator is invalidated, the owner discards
        // the incomplete sweep and retries; no partially captured audience is published.
        for (player in Bukkit.getOnlinePlayers()) {
            if (!player.isValid) { yield(Unit); continue }
            val location = player.location
            val worldId = player.world.uid
            val entityId = player.entityId
            val tracked = hashSetOf<UUID>()
            for (observer in player.getTrackedBy()) { tracked.add(observer.uniqueId); yield(Unit) }
            val passengers = arrayListOf<Int>()
            for (passenger in player.passengers) { passengers.add(passenger.entityId); yield(Unit) }
            if (player.isValid && player.world.uid == worldId && player.entityId == entityId) {
                val eye = player.eyeLocation
                val direction = eye.direction
                // Paper's sent-chunk snapshot is one indivisible native getter.
                accept(PacketDisplayViewer(player, player.uniqueId, worldId, entityId, tracked,
                    location.x, location.y, location.z, player.sentChunkKeys, passengers,
                    PacketDisplayView(eye.x, eye.y, eye.z, direction.x, direction.y, direction.z,
                        afk?.isAfk(player.uniqueId) == true)))
            }
            yield(Unit)
        }
    }.iterator()
}
