package ru.arc.paper.display

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.entity.LivingEntity
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
import java.util.concurrent.CompletionException
import java.util.function.BooleanSupplier
import java.util.UUID

// The queue checks its aggregate time allowance between these bounded, memory-only chunks.
private const val PURE_CAPTURE_BATCH_SIZE = 16

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

    private class CapturedFrames(
        val revision: Long,
        val entries: List<Pair<PacketDisplay, PacketDisplayFrame>>,
        val reusableVisibility: Boolean,
    )

    /** Contains no Player reference; retained only with this viewer's current connection state. */
    private class CachedVisibility(val scene: CapturedFrames, viewer: PacketDisplayViewer, val epoch: Long,
                                   val desired: List<PacketDisplayFrame>) {
        private val worldId = viewer.worldId
        private val entityId = viewer.entityId
        private val x = viewer.x
        private val y = viewer.y
        private val z = viewer.z
        private val sentChunks = viewer.sentChunks
        fun matches(scene: CapturedFrames, viewer: PacketDisplayViewer, epoch: Long): Boolean =
            this.scene === scene && this.epoch == epoch && worldId == viewer.worldId && entityId == viewer.entityId &&
                x == viewer.x && y == viewer.y && z == viewer.z && sentChunks == viewer.sentChunks
    }

    private data class ViewerState(val connection: PacketDisplayConnection, val worldId: UUID,
                                   val visibility: CachedVisibility? = null)

    /** One sliced sweep. Native references never leave Main or enter the retained visibility cache. */
    private class Capture {
        val audience = arrayListOf<PacketDisplayViewer>()
        val audienceById = hashMapOf<UUID, PacketDisplayViewer>()
        val nativeCarriers = hashMapOf<UUID, PacketDisplayCarrier>()
        val passengerSnapshots = hashMapOf<Int, List<Int>>()
        val liveVehiclesByWorld = hashMapOf<UUID, MutableSet<Int>>()
        val epochs = hashMapOf<UUID, Long>()
        lateinit var frames: CapturedFrames
    }
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
    private var sceneRevision = 0L
    private var staticFrames: CapturedFrames? = null
    internal fun sceneChanged() { sceneRevision++; staticFrames = null }
    internal fun visibilityChanged() { visibilityRevision++; sceneChanged() }
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
        return PacketBlockDisplay(this, transport.nextEntityId(), location, block).also { handles[it.entityId] = it; sceneChanged() }
    }

    fun spawnItem(location: Location, item: ItemStack): PacketItemDisplay {
        checkSpawn(location)
        return PacketItemDisplay(this, transport.nextEntityId(), location, item).also { handles[it.entityId] = it; sceneChanged() }
    }

    fun spawnText(location: Location, text: Component): PacketTextDisplay {
        checkSpawn(location)
        return PacketTextDisplay(this, transport.nextEntityId(), location, text).also { handles[it.entityId] = it; sceneChanged() }
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
                if (failure != null && !failure.isCancellation()) {
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
            if (failure != null && !closed && !failure.isCancellation()) plugin.logger.log(java.util.logging.Level.WARNING, "Packet display admission failed", failure)
        }
    }

    // CompletableFuture.copy/allOf wrap normal disconnect cancellation in CompletionException.
    private fun Throwable.isCancellation(): Boolean {
        var cause = this
        while (cause is CompletionException && cause.cause != null) cause = requireNotNull(cause.cause)
        return cause is CancellationException
    }

    private fun captureSteps(admissions: MutableList<CompletableFuture<Void>>): Iterator<Unit> = sequence {
        // Retire an empty scene without recapturing Bukkit viewers. Keep failed/deferred
        // cleanup eligible, then let the existing empty-owner fast path stop future sweeps.
        if (handles.isEmpty() && captureParticipants.isEmpty()) {
            for ((id, state) in viewers) {
                if (state.connection.isDrained) {
                    viewers.remove(id, state)
                    resetChunks.remove(id); resetViewers.remove(id)
                } else {
                    state.connection.submitAsync(emptyList()).also { admissions.add(it); track(it) }
                }
                yield(Unit)
            }
            return@sequence
        }
        val current = Capture()
        yieldAll(audience.captureSteps { viewer ->
            current.audience.add(viewer)
            current.audienceById[viewer.id] = viewer
            current.passengerSnapshots[viewer.entityId] = viewer.passengerEntityIds
            current.liveVehiclesByWorld.getOrPut(viewer.worldId, ::hashSetOf).add(viewer.entityId)
            current.epochs[viewer.id] = viewerEpochs[viewer.id] ?: 0L
        })
        for (participant in captureParticipants) {
            yieldAll(participant.captureSteps(current.audience))
            yield(Unit)
        }
        // Fence private state before frames, including any yields during frame capture.
        val visibility = visibilityRevision
        yieldAll(captureFrameSteps(current))
        for (id in viewers.keys) {
            if (id !in current.audienceById) forgetViewer(id)?.let(admissions::add)
            yield(Unit)
        }
        for (viewer in current.audience) {
            yieldAll(captureViewerSteps(current, viewer, visibility, admissions))
        }
    }.iterator()

    private fun captureFrameSteps(current: Capture): Iterator<Unit> = sequence {
        staticFrames?.let { current.frames = it; return@sequence }
        val revision = sceneRevision
        val frames = arrayListOf<Pair<PacketDisplay, PacketDisplayFrame>>()
        var attached = false
        var grouped = false
        var frameBatchSize = 0
        for (display in handles.values) {
            attached = attached || display.isAttached
            grouped = grouped || display.audienceGroup != null
            val targetId = display.attachmentTargetId
            var target = targetId?.let(current.audienceById::get)
            // A Citizens PLAYER carrier is native, but has no online viewer/connection entry.
            if (targetId != null && target == null) {
                if (frameBatchSize != 0) { frameBatchSize = 0; yield(Unit) }
                display.attachmentPlayer?.let { carrier ->
                    yieldAll(BukkitPacketDisplayAudience.captureCarrierSteps(carrier) { snapshot ->
                        target = snapshot
                        current.audienceById[snapshot.id] = snapshot
                        current.passengerSnapshots[snapshot.entityId] = snapshot.passengerEntityIds
                        current.liveVehiclesByWorld.getOrPut(snapshot.worldId, ::hashSetOf).add(snapshot.entityId)
                    })
                }
            }
            val entityTargetId = display.attachmentEntityTargetId
            if (entityTargetId != null && entityTargetId !in current.nativeCarriers) {
                if (frameBatchSize != 0) { frameBatchSize = 0; yield(Unit) }
                display.attachmentEntity?.let { carrier ->
                    yieldAll(BukkitPacketDisplayAudience.captureEntitySteps(carrier) { snapshot ->
                        current.nativeCarriers[snapshot.id] = snapshot
                        current.passengerSnapshots[snapshot.entityId] = snapshot.passengerEntityIds
                        current.liveVehiclesByWorld.getOrPut(snapshot.worldId, ::hashSetOf).add(snapshot.entityId)
                    })
                }
            }
            display.frame(target, entityTargetId?.let(current.nativeCarriers::get))?.let { frames.add(display to it) }
            if (++frameBatchSize == PURE_CAPTURE_BATCH_SIZE) { frameBatchSize = 0; yield(Unit) }
        }
        if (frameBatchSize != 0) yield(Unit)
        val captured = CapturedFrames(revision, frames, !attached && !grouped)
        current.frames = captured
        // Never retain a mixed generation captured across mutation/yield boundaries.
        if (!attached && revision == sceneRevision) staticFrames = captured
    }.iterator()

    private fun captureViewerSteps(current: Capture, viewer: PacketDisplayViewer, visibility: Long,
                                   admissions: MutableList<CompletableFuture<Void>>): Iterator<Unit> = sequence {
        val scene = current.frames
        val epoch = current.epochs.getValue(viewer.id)
        val previous = viewers[viewer.id]
        val cached = previous?.visibility?.takeIf {
            scene.reusableVisibility && scene.revision == sceneRevision && it.matches(scene, viewer, epoch)
        }
        val fresh = if (cached == null) arrayListOf<PacketDisplayFrame>() else null
        val desired = cached?.desired ?: requireNotNull(fresh)
        val desiredPassengers = hashMapOf<Int, MutableList<Int>>()
        val nativePassengers = hashMapOf<Int, List<Int>>()
        if (fresh != null) {
            val groupVisibility = hashMapOf<PacketDisplayAudienceGroup, Boolean>()
            var visibilityBatchSize = 0
            for ((display, frame) in scene.entries) {
                val targetId = display.attachmentTargetId
                val entityTargetId = display.attachmentEntityTargetId
                val packetCarrier = display.attachmentNpc
                val packetLease = packetCarrier?.lease(viewer.id)
                var nativeCheck = targetId != null || entityTargetId != null
                // Carrier visibility and uncached scene predicates retain individual native steps.
                if (nativeCheck && visibilityBatchSize != 0) { visibilityBatchSize = 0; yield(Unit) }
                val carrier = targetId?.let(current.audienceById::get)
                val nativeCarrier = entityTargetId?.let(current.nativeCarriers::get)
                val carrierVisible = if (entityTargetId != null) nativeCarrier != null &&
                    BukkitPacketDisplayAudience.isCurrent(nativeCarrier) &&
                    (entityTargetId == viewer.id || (viewer.id in nativeCarrier.trackedBy && viewer.player.canSee(nativeCarrier.entity)))
                else if (packetCarrier != null) packetLease != null else targetId == null || targetId == viewer.id ||
                    (carrier != null && audience.isCurrent(carrier) && viewer.id in carrier.trackedBy && viewer.player.canSee(carrier.player))
                if (carrierVisible && frame.chunkKey in viewer.sentChunks && display.visibleTo(viewer, frame)) {
                    val group = display.audienceGroup
                    if (group != null && group !in groupVisibility) {
                        if (visibilityBatchSize != 0) { visibilityBatchSize = 0; yield(Unit) }
                        groupVisibility[group] = group.allows(viewer.player)
                        nativeCheck = true
                    }
                    if (group == null || groupVisibility[group] == true) {
                        fresh.add(if (packetLease == null) frame else frame.copy(attachmentLease = packetLease))
                        frame.attachmentVehicleId?.let { vehicleId ->
                            desiredPassengers.getOrPut(vehicleId, ::arrayListOf).add(frame.entityId)
                            nativePassengers[vehicleId] = current.passengerSnapshots[vehicleId].orEmpty()
                        }
                    }
                }
                if (nativeCheck) yield(Unit)
                else if (++visibilityBatchSize == PURE_CAPTURE_BATCH_SIZE) { visibilityBatchSize = 0; yield(Unit) }
            }
            if (visibilityBatchSize != 0) yield(Unit)
        }
        // Camera/AFK, connection and replay checks stay live even when static visibility is reused.
        if (!audience.isCurrent(viewer) || visibility != visibilityRevision || epoch != (viewerEpochs[viewer.id] ?: 0L)) { yield(Unit); return@sequence }
        if (desired.isEmpty() && previous == null) {
            resetChunks.remove(viewer.id); resetViewers.remove(viewer.id)
            yield(Unit); return@sequence
        }
        val connection = transport.connection(viewer.player)
        if (connection == null) { forgetViewer(viewer.id)?.let(admissions::add); yield(Unit); return@sequence }
        if (previous != null && previous.connection.identity !== connection.identity) {
            previous.connection.closeAsync().also { admissions.add(it); track(it) }
        }
        // These collections are detached and never mutated after handoff. Sorting/copies/merge run on Netty.
        connection.submitViewAsync(desired, viewer.view, resetChunks.remove(viewer.id).orEmpty(),
            resetViewers.remove(viewer.id) || (previous != null && previous.worldId != viewer.worldId),
            desiredPassengers, nativePassengers, current.liveVehiclesByWorld[viewer.worldId].orEmpty() + desiredPassengers.keys)
            .also { admissions.add(it); track(it) }
        val reusable = if (scene.reusableVisibility && scene.revision == sceneRevision)
            cached ?: CachedVisibility(scene, viewer, epoch, desired) else null
        viewers[viewer.id] = ViewerState(connection, viewer.worldId, reusable)
        yield(Unit)
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
        staticFrames = null
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
    /** Native player passenger origin is at its current, pose/scale-aware height. */
    val height: Double,
)

/** Detached native carrier state, captured once per entity in a refresh; never sent to Netty. */
internal data class PacketDisplayCarrier(
    val entity: LivingEntity,
    val id: UUID,
    val worldId: UUID,
    val entityId: Int,
    val trackedBy: Set<UUID>,
    val x: Double,
    val y: Double,
    val z: Double,
    val passengerEntityIds: List<Int>,
)

internal object BukkitPacketDisplayAudience : PacketDisplayAudienceSource {
    internal fun isCurrent(carrier: PacketDisplayCarrier): Boolean = carrier.entity.isValid &&
        carrier.entity.world.uid == carrier.worldId && carrier.entity.entityId == carrier.entityId
    override fun isCurrent(viewer: PacketDisplayViewer): Boolean = viewer.player.isValid &&
        viewer.player.world.uid == viewer.worldId && viewer.player.entityId == viewer.entityId
    override fun checkThread() { check(Bukkit.isPrimaryThread()) { "Packet display state must be accessed on the server thread" } }
    override fun captureSteps(accept: (PacketDisplayViewer) -> Unit): Iterator<Unit> = sequence {
        val afk = Bukkit.getServicesManager().load(ArcAfkProvider::class.java)
        // Paper's collection is a live view. If its iterator is invalidated, the owner discards
        // the incomplete sweep and retries; no partially captured audience is published.
        for (player in Bukkit.getOnlinePlayers()) {
            yieldAll(captureCarrierSteps(player) { carrier ->
                val eye = player.eyeLocation
                val direction = eye.direction
                // Paper's sent-chunk snapshot is one indivisible native getter.
                accept(carrier.copy(sentChunks = player.sentChunkKeys,
                    view = PacketDisplayView(eye.x, eye.y, eye.z, direction.x, direction.y, direction.z,
                        afk?.isAfk(player.uniqueId) == true)))
            })
        }
    }.iterator()

    /** Native tracking only: a Citizens carrier has no connection or player chunk loader. */
    internal fun captureCarrierSteps(player: Player, accept: (PacketDisplayViewer) -> Unit): Iterator<Unit> = sequence {
        if (!player.isValid) { yield(Unit); return@sequence }
        val location = player.location
        val worldId = player.world.uid
        val entityId = player.entityId
        val height = player.height
        val tracked = hashSetOf<UUID>()
        for (observer in player.getTrackedBy()) { tracked.add(observer.uniqueId); yield(Unit) }
        val passengers = arrayListOf<Int>()
        for (passenger in player.passengers) { passengers.add(passenger.entityId); yield(Unit) }
        if (player.isValid && player.world.uid == worldId && player.entityId == entityId) {
            accept(PacketDisplayViewer(player, player.uniqueId, worldId, entityId, tracked,
                location.x, location.y, location.z, emptySet(), passengers, height = height))
        }
        yield(Unit)
    }.iterator()

    /** Capture native mobs without treating them as viewers or touching a connection/chunk loader. */
    internal fun captureEntitySteps(entity: LivingEntity, accept: (PacketDisplayCarrier) -> Unit): Iterator<Unit> = sequence {
        if (!entity.isValid) { yield(Unit); return@sequence }
        val location = entity.location
        val worldId = entity.world.uid
        val entityId = entity.entityId
        val tracked = hashSetOf<UUID>()
        for (observer in entity.getTrackedBy()) { tracked.add(observer.uniqueId); yield(Unit) }
        val passengers = arrayListOf<Int>()
        for (passenger in entity.passengers) { passengers.add(passenger.entityId); yield(Unit) }
        if (entity.isValid && entity.world.uid == worldId && entity.entityId == entityId) {
            accept(PacketDisplayCarrier(entity, entity.uniqueId, worldId, entityId, tracked,
                location.x, location.y, location.z, passengers))
        }
        yield(Unit)
    }.iterator()
}
