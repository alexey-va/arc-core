package ru.arc.paper.npc

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.display.BukkitPacketDisplayAudience
import ru.arc.paper.display.PacketDisplayAudienceSource
import ru.arc.paper.display.PacketDisplayViewer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.function.BooleanSupplier
import java.util.function.LongSupplier
import java.util.logging.Level

/**
 * Player NPC rendering for a bounded consumer-owned actor population. Paper captures native
 * state in the shared tick budget; Netty owns packet preparation and the newest desired frame.
 * No Bukkit entities or chunk tickets exist. Nearby received chunks, world and explicit handle
 * privacy gate each viewer. Declare PacketEvents and the ArcCore host as plugin dependencies.
 *
 * [onInteract] is called on Paper only after the exact connection's admitted entity ID,
 * generation, current audience, reach, aim and block visibility have been revalidated.
 * Core does not authorize any domain operation; the consumer performs that final check.
 * [closeAsync] observes cleanup write/flush invocation, never client acknowledgement.
 */
class PaperPacketNpcs internal constructor(
    private val plugin: Plugin,
    source: String,
    private val tasks: LifecycleTaskScope,
    private val audience: PacketDisplayAudienceSource,
    private val work: BudgetedTaskQueue = BudgetedTaskQueue(tasks),
    private val ownsWork: Boolean = true,
    private val cleanup: ArcNativeCleanupService = cleanup(plugin),
    private val currentTick: LongSupplier? = null,
    suppliedTransport: PacketNpcTransport? = null,
) : AutoCloseable, Listener {
    constructor(plugin: Plugin, source: String) : this(
        plugin, source, LifecycleTaskScope(), BukkitPacketDisplayAudience,
        currentTick = LongSupplier { Bukkit.getCurrentTick().toLong() },
    )
    constructor(plugin: Plugin, source: String, work: BudgetedTaskQueue) : this(
        plugin, source, LifecycleTaskScope(), BukkitPacketDisplayAudience,
        work, false, cleanup(plugin), LongSupplier { Bukkit.getCurrentTick().toLong() },
    )

    private val transport = suppliedTransport ?: PacketNpcTransport(plugin, source, ::enqueueInput)
    private val handles = linkedMapOf<Int, PacketPlayerNpc>()
    private data class Viewer(val connection: PacketNpcConnection, val world: UUID)
    private val viewers = mutableMapOf<UUID, Viewer>()
    private val epochs = mutableMapOf<UUID, Long>()
    private val resetChunks = mutableMapOf<UUID, MutableSet<Long>>()
    private val resets = mutableSetOf<UUID>()
    private val retirements = ConcurrentHashMap.newKeySet<CompletableFuture<Void>>()
    private val retirementFailures = ConcurrentHashMap.newKeySet<Throwable>()
    private var capture: CompletableFuture<Unit>? = null
    private var revision = 0L
    private var completion: CompletableFuture<Void>? = null
    internal var closed = false
        private set
    var onInteract: ((Player, PacketPlayerNpc, PacketNpcInteraction) -> Unit)? = null
        set(value) { checkThread(); field = value }

    init {
        checkThread()
        plugin.server.pluginManager.registerEvents(this, plugin)
        tasks.runTimer(1, 1) { refresh() }
    }

    fun spawnPlayer(location: Location, profile: PacketNpcProfile): PacketPlayerNpc {
        checkThread(); check(!closed) { "Packet NPC owner is closed" }
        requireNotNull(location.world)
        require(listOf(location.x, location.y, location.z).all(Double::isFinite))
        require(handles.values.none { it.uniqueId == profile.uuid || it.profile.name == profile.name }) { "NPC profile UUID and name must be unique within their owner" }
        val actor = PacketPlayerNpc(this, transport.nextEntityId(), profile, location)
        handles[actor.entityId] = actor
        transport.ownedIds.add(actor.entityId)
        return actor
    }

    internal fun checkThread() = audience.checkThread()
    internal fun itemSnapshot(item: ItemStack) = transport.itemSnapshot(item)
    internal fun lease(viewerId: UUID, entityId: Int): PacketNpcLease? = viewers[viewerId]?.connection?.lease(entityId)
    internal fun remove(actor: PacketPlayerNpc) {
        handles.remove(actor.entityId)
        transport.ownedIds.remove(actor.entityId)
        invalidateAudience(actor)
    }

    internal fun invalidateAudience(actor: PacketPlayerNpc, viewerId: UUID? = null) {
        revision++
        if (viewerId == null) viewers.values.forEach { it.connection.revoke(actor.entityId) }
        else viewers[viewerId]?.connection?.revoke(actor.entityId)
    }

    internal fun refresh(): CompletableFuture<Unit> {
        checkThread()
        capture?.takeUnless { it.isDone }?.let { return it }
        if (closed) return CompletableFuture.failedFuture(CancellationException("NPC owner is closed"))
        if (handles.isEmpty() && viewers.isEmpty()) return CompletableFuture.completedFuture(Unit)
        val steps = captureSteps()
        return work.submit({ !closed }) { if (steps.hasNext()) { steps.next(); false } else true }.also {
            capture = it
            it.whenComplete { _, error ->
                if (error != null && !closed) plugin.logger.log(Level.WARNING, "Packet NPC audience capture failed; next tick retries", error)
            }
            currentTick?.let(work::drainAvailable)
        }
    }

    private fun captureSteps(): Iterator<Unit> = sequence {
        val snapshot = arrayListOf<PacketDisplayViewer>()
        val generations = hashMapOf<UUID, Long>()
        yieldAll(audience.captureSteps {
            snapshot.add(it)
            generations[it.id] = epochs[it.id] ?: 0L
        })
        val currentIds = snapshot.mapTo(hashSetOf()) { it.id }
        for (id in viewers.keys.toList()) {
            if (id !in currentIds) retire(id)
            yield(Unit)
        }
        val capturedRevision = revision
        val frames = arrayListOf<Pair<PacketPlayerNpc, PacketNpcFrame>>()
        for (actor in handles.values.toList()) { frames.add(actor to actor.frame()); yield(Unit) }
        for (viewer in snapshot) {
            val desired = arrayListOf<PacketNpcFrame>()
            var batch = 0
            for ((actor, frame) in frames) {
                val dx = frame.x - viewer.x; val dy = frame.y - viewer.y; val dz = frame.z - viewer.z
                if (actor.audienceAllows(viewer.id) && frame.worldId == viewer.worldId && frame.chunkKey in viewer.sentChunks &&
                    dx * dx + dy * dy + dz * dz <= actor.viewRange * actor.viewRange) desired.add(frame)
                if (++batch == 16) { batch = 0; yield(Unit) }
            }
            if (batch != 0) yield(Unit)
            if (closed || revision != capturedRevision || generations[viewer.id] != (epochs[viewer.id] ?: 0L) || !audience.isCurrent(viewer)) { yield(Unit); continue }
            val previous = viewers[viewer.id]
            if (desired.isEmpty() && previous == null) { yield(Unit); continue }
            val connection = transport.connection(viewer.player)
            if (connection == null) { retire(viewer.id); yield(Unit); continue }
            if (previous != null && previous.connection !== connection) track(previous.connection.closeAsync())
            val reset = viewer.id in resets || (previous != null && previous.world != viewer.worldId)
            connection.submit(desired, resetChunks.remove(viewer.id).orEmpty(), reset)
            resets.remove(viewer.id)
            viewers[viewer.id] = Viewer(connection, viewer.worldId)
            yield(Unit)
        }
    }.iterator()

    internal fun enqueueInput(id: UUID, connection: PacketNpcConnection, entityId: Int, admittedLease: PacketNpcLease, action: PacketNpcInteraction) {
        if (tasks.runSync {
                try { interact(id, connection, entityId, admittedLease, action) } finally { connection.finishInput() }
            } == null) connection.finishInput()
    }

    internal fun interact(id: UUID, connection: PacketNpcConnection, entityId: Int, admittedLease: PacketNpcLease, action: PacketNpcInteraction) {
        checkThread()
        if (closed || viewers[id]?.connection !== connection) return
        val actor = handles[entityId] ?: return
        val lease = connection.lease(entityId) ?: return
        if (lease !== admittedLease || !admittedLease.isActive()) return
        if (!actor.audienceAllows(id) || lease.frame.lifetime !== actor.frame().lifetime) return
        val player = plugin.server.getPlayer(id) ?: return
        if (!player.isOnline || !player.isValid || player.isDead || player.world.uid != actor.world.uid) return
        if (actor.frame().chunkKey !in player.sentChunkKeys) return
        val eye = player.eyeLocation
        val location = actor.location
        if (player.location.distanceSquared(location) > actor.viewRange * actor.viewRange) return
        val reach = (player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE)?.value ?: 3.0).coerceIn(0.0, 6.0) + 0.1
        val halfWidth = if (actor.pose == PacketNpcPose.SLEEPING) 0.1 else 0.3
        val box = BoundingBox(location.x - halfWidth, location.y, location.z - halfWidth,
            location.x + halfWidth, location.y + actor.height, location.z + halfWidth)
        val hit = box.rayTrace(eye.toVector(), eye.direction, reach) ?: return
        val distance = hit.hitPosition.distance(eye.toVector())
        val wall = player.world.rayTraceBlocks(eye, eye.direction, distance, FluidCollisionMode.NEVER, true)
        if (wall != null && wall.hitPosition.distance(eye.toVector()) + 0.02 < distance) return
        onInteract?.invoke(player, actor, action)
    }

    @EventHandler fun onChunkLoad(event: PlayerChunkLoadEvent) = resetChunk(event.player, event.chunk.chunkKey)
    @EventHandler fun onChunkUnload(event: PlayerChunkUnloadEvent) = resetChunk(event.player, event.chunk.chunkKey)
    private fun resetChunk(player: Player, chunk: Long) {
        if (closed) return
        val id = player.uniqueId
        epochs[id] = (epochs[id] ?: 0L) + 1
        viewers[id]?.connection?.revokeChunk(chunk)
        if (id in viewers) resetChunks.getOrPut(id, ::hashSetOf).add(chunk)
    }
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) = reset(event.player)
    @EventHandler fun onRespawn(event: PlayerPostRespawnEvent) = reset(event.player)
    private fun reset(player: Player) {
        if (closed) return
        val id = player.uniqueId
        epochs[id] = (epochs[id] ?: 0L) + 1
        viewers[id]?.connection?.revokeAll()
        resets.add(id)
    }
    @EventHandler fun onQuit(event: PlayerQuitEvent) {
        retire(event.player.uniqueId)
        transport.forget(event.player.uniqueId)
        epochs.remove(event.player.uniqueId)
    }
    private fun retire(id: UUID) {
        viewers.remove(id)?.let { track(it.connection.closeAsync()) }
        resetChunks.remove(id); resets.remove(id)
    }
    private fun track(future: CompletableFuture<Void>) {
        retirements.add(future)
        future.whenComplete { _, error -> if (error != null && closed) retirementFailures.add(error); retirements.remove(future) }
    }

    /** Host-owned bounded retirement survives consumer task cancellation and plugin disable. */
    fun closeAsync(): CompletableFuture<Void> {
        checkThread()
        completion?.let { return it.copy() }
        val result = CompletableFuture<Void>().also { completion = it }
        closed = true
        capture?.cancel(false)
        tasks.close()
        if (ownsWork) work.close()
        HandlerList.unregisterAll(this)
        onInteract = null
        val closing = sequence {
            for (actor in handles.values.toList()) { actor.remove(); yield(Unit) }
            for (id in viewers.keys.toList()) { retire(id); yield(Unit) }
            while (retirements.isNotEmpty()) yield(Unit)
        }.iterator()
        try {
            cleanup.submit("packet-npcs", BooleanSupplier { if (closing.hasNext()) { closing.next(); false } else true })
                .whenComplete { _, error ->
                    var failure = error ?: retirementFailures.firstOrNull()
                    try { transport.close() } catch (closeFailure: Throwable) {
                        if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
                    }
                    epochs.clear(); resetChunks.clear(); resets.clear()
                    if (failure == null) result.complete(null) else result.completeExceptionally(failure)
                }
        } catch (error: Throwable) {
            runCatching { transport.close() }.exceptionOrNull()?.let(error::addSuppressed)
            result.completeExceptionally(error)
        }
        return result.copy()
    }
    override fun close() { closeAsync() }

    companion object {
        private fun cleanup(plugin: Plugin) = requireNotNull(plugin.server.servicesManager.load(ArcNativeCleanupService::class.java)) {
            "ArcNativeCleanupService is unavailable; enable ArcCore before packet NPCs"
        }
    }
}
