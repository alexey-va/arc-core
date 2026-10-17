package ru.arc.paper.entity

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.netty.channel.ChannelHelper
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.WeakHashMap
import java.util.logging.Level
import java.util.logging.Logger
import ru.arc.paper.api.VisualPacketAdmission
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.packet.PaperVisualPackets

/**
 * Owns temporary viewer-only glowing for live entities.
 *
 * Construct, call [set] and close on Paper's primary thread after PacketEvents
 * has initialized and the shared ARC visual packet budget is registered.
 * No Bukkit entity glow state is changed: selected entity metadata has only its common
 * flags glow bit forced for that viewer, including later native metadata
 * updates. [set] with `false` restores the entity's current native flags as a
 * cleanup update, waiting for a writable channel while bypassing rate budgets.
 * A sent native flags update supersedes a queued restoration; after close the
 * packet observer stays registered only until restorations drain or disconnect.
 * Selections are cleared on viewer disconnect and close; callers should
 * clear a selection with [set] when it is no longer needed.
 */
class PaperViewerEntityGlow internal constructor(
    plugin: Plugin,
    private val transport: ViewerEntityGlowPacketTransport,
    private val cleanup: ArcNativeCleanupService = requireNotNull(plugin.server.servicesManager.load(ArcNativeCleanupService::class.java)) {
        "ArcCore native cleanup service is unavailable; enable ArcCore before visual owners"
    },
) : AutoCloseable, Listener {
    /** Creates an owner backed by the already initialized PacketEvents plugin. */
    constructor(plugin: Plugin) : this(plugin, PacketEventsViewerEntityGlowPacketTransport(plugin, "entity-glow"))

    /** Creates an owner with a distinct stable visual-packet source label. */
    constructor(plugin: Plugin, feature: String) : this(plugin, PacketEventsViewerEntityGlowPacketTransport(plugin, feature))

    private val selections = ViewerEntityGlowSelections()
    private val server = plugin.server
    private val cleanupOwner = "${plugin.name}:entity-glow"
    @Volatile private var closed = false
    private val listenerUnregistered = AtomicBoolean()
    private var completion: CompletableFuture<Void>? = null

    private val packetListener = object : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
        override fun onPacketSend(event: PacketSendEvent) {
            if (event.isCancelled) return
            val viewerId = event.user.uuid ?: return
            when (event.packetType) {
                PacketType.Play.Server.ENTITY_METADATA -> {
                    val metadata = WrapperPlayServerEntityMetadata(event)
                    val flags = entityFlags(metadata.entityMetadata) ?: return
                    val selected = !closed && selections.isSelected(viewerId, metadata.entityId)
                    val channel = event.user.channel
                    if (!selected && transport.hasPendingCleanup(channel, metadata.entityId) &&
                        !transport.isOwnWrite(channel, metadata.entityId, flags)
                    ) {
                        event.tasksAfterSend += Runnable {
                            if (!event.isCancelled) transport.nativeFlagsSent(channel, metadata.entityId, flags)
                        }
                    }
                    if (!selected) return
                    val adjusted = withViewerGlow(metadata.entityMetadata) ?: return
                    metadata.entityMetadata = adjusted
                    event.markForReEncode(true)
                }
                PacketType.Play.Server.DESTROY_ENTITIES -> {
                    val entityIds = WrapperPlayServerDestroyEntities(event).entityIds
                    transport.forgetEntities(event.user.channel, entityIds)
                    if (!closed) forgetEntities(viewerId, entityIds)
                }
            }
        }

        override fun onUserDisconnect(event: UserDisconnectEvent) {
            val viewerId = event.user.uuid ?: return
            if (!closed) selections.forgetViewer(viewerId)
            transport.forgetChannel(event.user.channel)
        }
    }

    init {
        checkPrimaryThread()
        transport.register(packetListener)
        try {
            plugin.server.pluginManager.registerEvents(this, plugin)
        } catch (failure: Throwable) {
            HandlerList.unregisterAll(this)
            unregisterPacketListener()
            throw failure
        }
    }

    /** Sets or clears this viewer's glow override without changing server entity state. */
    fun set(viewer: Player, entity: LivingEntity, glowing: Boolean) {
        set(viewer, entity as Entity, glowing)
    }

    /** Sets or clears viewer-only glow for any entity with common entity flags. */
    fun set(viewer: Player, entity: Entity, glowing: Boolean) {
        checkPrimaryThread()
        check(!closed) { "Viewer entity glow owner is closed" }

        val key = ViewerEntityGlowKey(viewer.uniqueId, entity.entityId)
        if (glowing && viewer.isOnline && entity.isValid) {
            selections.select(key, entity)
        } else {
            selections.clear(key)
        }
        if (!viewer.isOnline || !entity.isValid) return

        val nativeFlags = transport.nativeFlags(entity)
        transport.send(
            viewer,
            entity.entityId,
            if (glowing) withGlowBit(nativeFlags, true) else nativeFlags,
            cleanup = !glowing,
        )
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        selections.forgetViewer(event.player.uniqueId)
    }

    /**
     * Observes accepted restoration packets without retiring this shared owner. Call on Paper
     * after issuing the desired clears. Later calls can enqueue more work; this is not a barrier
     * for future updates. A closing owner instead returns its complete native/packet retirement.
     * Completion has the same write/flush invocation boundary as [closeAsync].
     */
    fun cleanupDrained(): CompletableFuture<Void> {
        checkPrimaryThread()
        completion?.let { return it.copy() }
        val result = CompletableFuture<Void>()
        try { transport.whenCleanupsDrained { result.complete(null) } }
        catch (failure: Throwable) { result.completeExceptionally(failure) }
        return result.copy()
    }

    /**
     * Seals overrides and restores one viewer/entity pair per host native step. Completion
     * includes native capture and the existing PacketEvents queue's successful write/flush
     * invocation (or a superseding native packet/disconnect), not client/socket delivery.
     * The host owns this cursor after consumer disable; host stop fails its observation.
     * Returned futures are copies: cancelling an observer cannot cancel accepted cleanup.
     */
    fun closeAsync(): CompletableFuture<Void> {
        checkPrimaryThread()
        completion?.let { return it.copy() }
        val result = CompletableFuture<Void>()
        completion = result
        closed = true
        HandlerList.unregisterAll(this)
        transport.retire()
        val cursor = selections.detach()
        val drained = AtomicBoolean()
        var observing = false
        var nativeFailure: Throwable? = null
        try {
            cleanup.submit(cleanupOwner) {
                if (cursor.hasNext()) {
                    val (key, reference) = cursor.next()
                    try {
                        val viewer = server.getPlayer(key.viewerId)?.takeIf(Player::isOnline)
                        val entity = reference.get()?.takeIf { it.isValid && it.entityId == key.entityId }
                        if (viewer != null && entity != null)
                            transport.send(viewer, key.entityId, transport.nativeFlags(entity), cleanup = true)
                    } catch (failure: Throwable) {
                        if (nativeFailure == null) nativeFailure = failure
                        else if (nativeFailure !== failure) nativeFailure!!.addSuppressed(failure)
                    }
                    false
                } else {
                    if (!observing) {
                        observing = true
                        transport.whenCleanupsDrained { drained.set(true) }
                    }
                    if (drained.get()) {
                        nativeFailure?.let { throw it }
                        true
                    } else false
                }
            }.whenComplete { _, failure ->
                var terminal = failure
                try { unregisterPacketListener() } catch (unregisterFailure: Throwable) {
                    if (terminal == null) terminal = unregisterFailure
                    else if (terminal !== unregisterFailure) terminal.addSuppressed(unregisterFailure)
                }
                if (terminal == null) result.complete(null) else result.completeExceptionally(terminal)
            }
        } catch (failure: Throwable) {
            try { unregisterPacketListener() } catch (unregisterFailure: Throwable) {
                if (failure !== unregisterFailure) failure.addSuppressed(unregisterFailure)
            }
            result.completeExceptionally(failure)
        }
        return result.copy()
    }

    /** Dispatch-only compatibility entry point; [closeAsync] observes the actual cleanup. */
    override fun close() { closeAsync() }

    internal fun isSelectedFor(viewerId: UUID, entityId: Int): Boolean =
        selections.isSelected(viewerId, entityId)

    internal fun trackedSelectionCount(): Int = selections.size

    internal fun forgetEntities(viewerId: UUID, entityIds: IntArray) {
        selections.forgetEntities(viewerId, entityIds)
    }

    private fun checkPrimaryThread() {
        check(Bukkit.isPrimaryThread()) { "Viewer entity glow state must be accessed on the server thread" }
    }

    private fun unregisterPacketListener() {
        if (listenerUnregistered.compareAndSet(false, true)) transport.unregister(packetListener)
    }
}

internal data class ViewerEntityGlowKey(val viewerId: UUID, val entityId: Int)

/** Concurrent because PacketEvents observes metadata on connection event loops. */
internal class ViewerEntityGlowSelections {
    @Volatile private var selected = ConcurrentHashMap<UUID, ConcurrentHashMap<Int, WeakReference<Entity>>>()

    /** Test diagnostic only; gameplay operations never traverse other viewers. */
    val size: Int get() = selected.values.sumOf { it.size }

    fun select(key: ViewerEntityGlowKey, entity: Entity) {
        selected.compute(key.viewerId) { _, existing ->
            (existing ?: ConcurrentHashMap()).also { it[key.entityId] = WeakReference(entity) }
        }
    }

    fun clear(key: ViewerEntityGlowKey) {
        selected.computeIfPresent(key.viewerId) { _, targets ->
            targets.remove(key.entityId)
            targets.takeUnless { it.isEmpty() }
        }
    }

    fun isSelected(viewerId: UUID, entityId: Int): Boolean =
        selected[viewerId]?.containsKey(entityId) == true

    fun forgetViewer(viewerId: UUID) {
        selected.remove(viewerId)
    }

    fun forgetEntities(viewerId: UUID, entityIds: IntArray) {
        if (entityIds.isEmpty()) return
        for (entityId in entityIds) clear(ViewerEntityGlowKey(viewerId, entityId))
    }

    /** Native owner only; old packet observers can only remove from their captured old map. */
    fun detach(): Iterator<Pair<ViewerEntityGlowKey, WeakReference<Entity>>> {
        val previous = selected
        selected = ConcurrentHashMap()
        return sequence {
            for ((viewer, targets) in previous) {
                for ((entityId, reference) in targets) yield(ViewerEntityGlowKey(viewer, entityId) to reference)
            }
        }.iterator()
    }
}

/** Exact PacketEvents boundary used by the native implementation and deterministic tests. */
internal interface ViewerEntityGlowPacketTransport {
    fun register(listener: PacketListenerCommon)
    fun unregister(listener: PacketListenerCommon)
    fun send(viewer: Player, entityId: Int, flags: Byte, cleanup: Boolean = false)
    fun nativeFlags(entity: Entity): Byte
    fun forgetEntities(channel: Any, entityIds: IntArray) = Unit
    fun forgetChannel(channel: Any) = Unit
    fun hasPendingCleanup(channel: Any, entityId: Int): Boolean = false
    fun isOwnWrite(channel: Any, entityId: Int, flags: Byte): Boolean = false
    fun nativeFlagsSent(channel: Any, entityId: Int, flags: Byte) = Unit
    fun retire() = Unit
    fun whenCleanupsDrained(callback: () -> Unit) = callback()
}

private class PacketEventsViewerEntityGlowPacketTransport(plugin: Plugin, feature: String) : ViewerEntityGlowPacketTransport {
    private val queue = ViewerEntityGlowPacketQueue(PacketEventsViewerEntityGlowQueueBackend(plugin, feature), plugin.logger)

    override fun nativeFlags(entity: Entity): Byte = nativeEntityFlags(entity)

    override fun register(listener: PacketListenerCommon) {
        PacketEvents.getAPI().eventManager.registerListener(listener)
    }

    override fun unregister(listener: PacketListenerCommon) {
        PacketEvents.getAPI().eventManager.unregisterListener(listener)
    }

    override fun send(viewer: Player, entityId: Int, flags: Byte, cleanup: Boolean) {
        val channel = runCatching { PacketEvents.getAPI().playerManager.getChannel(viewer) }.getOrNull()
            ?: return
        queue.submit(channel, entityId, flags, cleanup)
    }

    override fun forgetEntities(channel: Any, entityIds: IntArray) =
        queue.forgetEntities(channel, entityIds)

    override fun forgetChannel(channel: Any) = queue.forgetChannel(channel)

    override fun hasPendingCleanup(channel: Any, entityId: Int): Boolean =
        queue.hasPendingCleanup(channel, entityId)

    override fun isOwnWrite(channel: Any, entityId: Int, flags: Byte): Boolean =
        queue.isOwnWrite(channel, entityId, flags)

    override fun nativeFlagsSent(channel: Any, entityId: Int, flags: Byte) =
        queue.nativeFlagsSent(channel, entityId, flags)

    override fun retire() = queue.retire()

    override fun whenCleanupsDrained(callback: () -> Unit) = queue.whenCleanupsDrained(callback)
}

internal data class ViewerEntityGlowDelivery(
    val entityId: Int,
    val flags: Byte,
    val cleanup: Boolean,
)

/** Netty seam keeps pressure/coalescing checks independent of MockBukkit and PacketEvents channels. */
internal interface ViewerEntityGlowQueueBackend {
    fun isOpen(channel: Any): Boolean
    fun enqueue(channel: Any, task: () -> Unit)
    fun retry(channel: Any, task: () -> Unit)
    fun write(channel: Any, deliveries: List<ViewerEntityGlowDelivery>, cleanup: Boolean): VisualPacketAdmission
    fun flush(channel: Any)
}

/**
 * One latest-flags entry per entity and at most one Netty drain/retry per connection.
 * Channel keys are weak; entries contain no Player, entity, or channel handles.
 */
internal class ViewerEntityGlowPacketQueue(
    private val backend: ViewerEntityGlowQueueBackend,
    private val logger: Logger,
) {
    private class ConnectionState {
        var pending = linkedMapOf<Int, ViewerEntityGlowDelivery>()
        var pendingCleanups = 0
        var inFlightCleanups = 0
        var active = true
        var scheduled = false
        var failureLogged = false
    }

    private class OwnWrite(val channel: Any, val delivery: ViewerEntityGlowDelivery)

    private val connections = WeakHashMap<Any, ConnectionState>()
    private var cleanupsDrainedCallbacks = mutableListOf<() -> Unit>()
    private var cleanupCount = 0 // Pending plus in-flight, updated only under the short admission monitor.
    private val ownWrites = ThreadLocal<MutableList<OwnWrite>>()
    @Volatile private var retired = false

    fun submit(channel: Any, entityId: Int, flags: Byte, cleanup: Boolean) {
        if (retired && !cleanup) return
        if (!backend.isOpen(channel)) return
        val delivery = ViewerEntityGlowDelivery(entityId, flags, cleanup)
        var state: ConnectionState
        var schedule = false
        synchronized(connections) {
            if (retired && !cleanup) return
            state = connections[channel] ?: ConnectionState().also {
                connections[channel] = it
            }
            putPending(state, delivery)
            if (!state.scheduled) {
                state.scheduled = true
                schedule = true
            }
        }
        if (schedule) schedule(channel, state, retry = false)
    }

    /** A server destroy packet makes any queued metadata for those IDs obsolete. */
    fun forgetEntities(channel: Any, entityIds: IntArray) {
        if (entityIds.isEmpty()) return
        for (entityId in entityIds) synchronized(connections) {
            connections[channel]?.let { removePending(it, entityId) }
        }
        val callbacks = synchronized(connections) { collectCleanupsDrainedCallbacksLocked() }
        callbacks.forEach { it() }
    }

    /** A sent native flags entry supersedes any queued restoration for that entity. */
    fun nativeFlagsSent(channel: Any, entityId: Int, flags: Byte) {
        if (isOwnWrite(channel, entityId, flags)) return
        val callbacks = synchronized(connections) {
            val state = connections[channel]
            if (state?.pending?.get(entityId)?.cleanup == true) removePending(state, entityId)
            collectCleanupsDrainedCallbacksLocked()
        }
        callbacks.forEach { it() }
    }

    fun hasPendingCleanup(channel: Any, entityId: Int): Boolean = synchronized(connections) {
        val state = connections[channel] ?: return@synchronized false
        state.pending[entityId]?.cleanup == true
    }

    /** Constant-time seal; the existing connection drain discards queued ordinary updates. */
    fun retire() { retired = true }

    /** Runs once no pending or in-flight restoration remains, including disconnect cleanup. */
    fun whenCleanupsDrained(callback: () -> Unit) {
        val runNow = synchronized(connections) {
            if (cleanupCount != 0) {
                cleanupsDrainedCallbacks += callback
                false
            } else {
                true
            }
        }
        if (runNow) callback()
    }

    /** Called from PacketEvents disconnect; weak channel keys cover idle connections. */
    fun forgetChannel(channel: Any) {
        val callbacks = synchronized(connections) {
            connections.remove(channel)?.let(::clear)
            collectCleanupsDrainedCallbacksLocked()
        }
        callbacks.forEach { it() }
    }

    fun isOwnWrite(channel: Any, entityId: Int, flags: Byte): Boolean =
        ownWrites.get()?.any {
            it.channel === channel && it.delivery.entityId == entityId && it.delivery.flags == flags
        } == true

    internal fun trackedConnectionCount(): Int =
        synchronized(connections) { connections.size }

    private fun drain(channel: Any, state: ConnectionState) {
        if (!backend.isOpen(channel)) {
            forgetChannel(channel)
            return
        }
        // Detach in O(1). Encoding/filtering runs on the event loop without an admission lock.
        val batch = synchronized(connections) {
            if (!state.active || connections[channel] !== state) return
            state.pending.also {
                state.pending = linkedMapOf()
                state.inFlightCleanups = state.pendingCleanups
                state.pendingCleanups = 0
            }
        }
        if (batch.isEmpty()) {
            finish(channel, state, emptyList(), retry = false)
            return
        }

        val deferred = mutableListOf<ViewerEntityGlowDelivery>()
        val accepted = mutableListOf<ViewerEntityGlowDelivery>()
        var wrote = false
        var failure: Throwable? = null
        for (cleanup in listOf(true, false)) {
            val group = batch.values.filter { it.cleanup == cleanup && (cleanup || !retired) }
            if (group.isEmpty()) continue
            try {
                val admission = withOwnWriteMarkers(channel, group) {
                    backend.write(channel, group, cleanup)
                }
                if (admission == VisualPacketAdmission.ALLOWED) {
                    wrote = true
                    accepted += group
                } else if (cleanup || admission != VisualPacketAdmission.CLOSED) {
                    // A closed provider cannot admit ordinary visual updates; retain restoration only.
                    deferred += group
                }
            } catch (sendFailure: Throwable) {
                failure = sendFailure
                deferred += group
            }
        }
        if (wrote) {
            try {
                backend.flush(channel)
            } catch (flushFailure: Throwable) {
                failure = flushFailure
                deferred += accepted
            }
        }
        if (failure != null) warnOnce(state, failure)
        finish(channel, state, deferred, retry = deferred.isNotEmpty())
    }

    private fun finish(
        channel: Any,
        state: ConnectionState,
        deferred: List<ViewerEntityGlowDelivery>,
        retry: Boolean,
    ) {
        var next = false
        var retryNext = false
        // Each held section is one entry, never a batch-sized merge that can block Paper.
        for (delivery in deferred) synchronized(connections) {
            if (state.active && (!retired || delivery.cleanup) && delivery.entityId !in state.pending)
                putPending(state, delivery)
        }
        val callbacks = synchronized(connections) {
            if (!state.active || connections[channel] !== state) return
            cleanupCount -= state.inFlightCleanups
            state.inFlightCleanups = 0
            if (state.pending.isEmpty()) {
                state.scheduled = false
                state.failureLogged = false
            } else {
                next = true
                retryNext = retry
            }
            collectCleanupsDrainedCallbacksLocked()
        }
        callbacks.forEach { it() }
        if (next) schedule(channel, state, retryNext)
    }

    private fun schedule(channel: Any, state: ConnectionState, retry: Boolean) {
        try {
            val channelRef = WeakReference(channel)
            val task = {
                val currentChannel = channelRef.get()
                // Keep the accounting state until its queued task observes weak-channel loss.
                if (currentChannel != null) drain(currentChannel, state) else forgetState(state)
            }
            if (retry) backend.retry(channel, task) else backend.enqueue(channel, task)
        } catch (failure: Throwable) {
            warnOnce(state, failure)
            if (!backend.isOpen(channel)) {
                forgetChannel(channel)
            } else {
                synchronized(connections) { state.scheduled = false }
            }
        }
    }

    private fun warnOnce(state: ConnectionState, failure: Throwable) {
        val log = synchronized(connections) {
            if (state.failureLogged) false else {
                state.failureLogged = true
                true
            }
        }
        if (log) logger.log(Level.WARNING, "Viewer entity glow packet send failed; retaining latest flags", failure)
    }

    private fun clear(state: ConnectionState) {
        if (!state.active) return
        cleanupCount -= state.pendingCleanups + state.inFlightCleanups
        state.pending = linkedMapOf()
        state.pendingCleanups = 0
        state.inFlightCleanups = 0
        state.scheduled = false
        state.active = false
    }

    private fun forgetState(state: ConnectionState) {
        val callbacks = synchronized(connections) {
            // WeakHashMap expunges the lost key itself. No global map scan under admission lock.
            clear(state)
            collectCleanupsDrainedCallbacksLocked()
        }
        callbacks.forEach { it() }
    }

    private fun <T> withOwnWriteMarkers(
        channel: Any,
        deliveries: List<ViewerEntityGlowDelivery>,
        block: () -> T,
    ): T {
        val active = ownWrites.get() ?: mutableListOf<OwnWrite>().also(ownWrites::set)
        val added = deliveries.map { OwnWrite(channel, it) }
        active.addAll(added)
        try {
            return block()
        } finally {
            added.forEach(active::remove)
            if (active.isEmpty()) ownWrites.remove()
        }
    }

    private fun putPending(state: ConnectionState, delivery: ViewerEntityGlowDelivery) {
        val previous = state.pending.put(delivery.entityId, delivery)
        val change = (if (delivery.cleanup) 1 else 0) - (if (previous?.cleanup == true) 1 else 0)
        state.pendingCleanups += change
        cleanupCount += change
    }

    private fun removePending(state: ConnectionState, entityId: Int) {
        if (state.pending.remove(entityId)?.cleanup == true) {
            state.pendingCleanups--
            cleanupCount--
        }
    }

    private fun collectCleanupsDrainedCallbacksLocked(): List<() -> Unit> =
        if (cleanupsDrainedCallbacks.isEmpty() || cleanupCount != 0) {
            emptyList()
        } else {
            cleanupsDrainedCallbacks.also { cleanupsDrainedCallbacks = mutableListOf() }
        }
}

private class PacketEventsViewerEntityGlowQueueBackend(plugin: Plugin, feature: String) : ViewerEntityGlowQueueBackend {
    private val packets = PaperVisualPackets(plugin, feature)

    override fun isOpen(channel: Any): Boolean = ChannelHelper.isOpen(channel)

    override fun enqueue(channel: Any, task: () -> Unit) {
        ChannelHelper.runInEventLoop(channel, Runnable(task))
    }

    override fun retry(channel: Any, task: () -> Unit) = PaperVisualPackets.retry(channel, task)

    override fun write(
        channel: Any,
        deliveries: List<ViewerEntityGlowDelivery>,
        cleanup: Boolean,
    ): VisualPacketAdmission = packets.write(
        channel,
        deliveries.map {
            WrapperPlayServerEntityMetadata(
                it.entityId,
                listOf(EntityData(0, EntityDataTypes.BYTE, it.flags)),
            )
        },
        cleanup,
    )

    override fun flush(channel: Any) {
        ChannelHelper.flush(channel)
    }
}

/** Changes only the common-entity-flags glowing bit (0x40). */
internal fun withGlowBit(flags: Byte, glowing: Boolean): Byte =
    if (glowing) (flags.toInt() or ENTITY_GLOWING_FLAG).toByte()
    else (flags.toInt() and ENTITY_GLOWING_FLAG.inv()).toByte()

/** Returns null when this packet has no flags entry or already has the requested viewer bit. */
internal fun withViewerGlow(metadata: List<EntityData<*>>): List<EntityData<*>>? {
    val flagIndex = metadata.indexOfFirst { it.index == ENTITY_FLAGS_INDEX && it.type == EntityDataTypes.BYTE }
    if (flagIndex < 0) return null
    val current = metadata[flagIndex].value as? Byte ?: return null
    val adjusted = withGlowBit(current, glowing = true)
    if (adjusted == current) return null
    return metadata.toMutableList().also {
        it[flagIndex] = EntityData(ENTITY_FLAGS_INDEX, EntityDataTypes.BYTE, adjusted)
    }
}

/** Reads the exact native flags instead of reconstructing pose/fire/invisibility through Bukkit. */
internal fun nativeEntityFlags(entity: Entity): Byte =
    SpigotConversionUtil.getEntityMetadata(entity)
        .first { it.index == ENTITY_FLAGS_INDEX && it.type == EntityDataTypes.BYTE }.value as Byte

/** Returns the common entity-flags byte when a metadata packet carries it. */
internal fun entityFlags(metadata: List<EntityData<*>>): Byte? =
    metadata.firstOrNull { it.index == ENTITY_FLAGS_INDEX && it.type == EntityDataTypes.BYTE }?.value as? Byte

private const val ENTITY_FLAGS_INDEX = 0
private const val ENTITY_GLOWING_FLAG = 0x40
