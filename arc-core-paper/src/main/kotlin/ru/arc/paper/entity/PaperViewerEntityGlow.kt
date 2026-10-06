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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.WeakHashMap
import java.util.logging.Level
import java.util.logging.Logger
import ru.arc.paper.api.VisualPacketAdmission
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
) : AutoCloseable, Listener {
    /** Creates an owner backed by the already initialized PacketEvents plugin. */
    constructor(plugin: Plugin) : this(plugin, PacketEventsViewerEntityGlowPacketTransport(plugin, "entity-glow"))

    /** Creates an owner with a distinct stable visual-packet source label. */
    constructor(plugin: Plugin, feature: String) : this(plugin, PacketEventsViewerEntityGlowPacketTransport(plugin, feature))

    private val selections = ViewerEntityGlowSelections()
    @Volatile private var closed = false
    private val listenerUnregistered = AtomicBoolean()

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

    override fun close() {
        checkPrimaryThread()
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        transport.retire()

        try {
            selections.drain().forEach { (key, reference) ->
                val viewer = Bukkit.getPlayer(key.viewerId)?.takeIf(Player::isOnline) ?: return@forEach
                val entity = reference.get()?.takeIf { it.isValid && it.entityId == key.entityId } ?: return@forEach
                transport.send(viewer, key.entityId, transport.nativeFlags(entity), cleanup = true)
            }
        } finally {
            transport.whenCleanupsDrained(::unregisterPacketListener)
        }
    }

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
    private val selected = ConcurrentHashMap<ViewerEntityGlowKey, WeakReference<Entity>>()

    val size: Int get() = selected.size

    fun select(key: ViewerEntityGlowKey, entity: Entity) {
        selected[key] = WeakReference(entity)
    }

    fun clear(key: ViewerEntityGlowKey) {
        selected.remove(key)
    }

    fun isSelected(viewerId: UUID, entityId: Int): Boolean =
        selected.containsKey(ViewerEntityGlowKey(viewerId, entityId))

    fun forgetViewer(viewerId: UUID) {
        selected.keys.removeIf { it.viewerId == viewerId }
    }

    fun forgetEntities(viewerId: UUID, entityIds: IntArray) {
        if (entityIds.isEmpty()) return
        val removed = entityIds.toHashSet()
        selected.keys.removeIf { it.viewerId == viewerId && it.entityId in removed }
    }

    fun drain(): List<Pair<ViewerEntityGlowKey, WeakReference<Entity>>> =
        selected.entries.map { it.key to it.value }.also { selected.clear() }
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
        val pending = linkedMapOf<Int, ViewerEntityGlowDelivery>()
        val inFlight = linkedMapOf<Int, ViewerEntityGlowDelivery>()
        var scheduled = false
        var failureLogged = false
    }

    private class OwnWrite(val channel: Any, val delivery: ViewerEntityGlowDelivery)

    private val connections = WeakHashMap<Any, ConnectionState>()
    private val cleanupsDrainedCallbacks = mutableListOf<() -> Unit>()
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
            synchronized(state) {
                state.pending[entityId] = delivery
                if (!state.scheduled) {
                    state.scheduled = true
                    schedule = true
                }
            }
        }
        if (schedule) schedule(channel, state, retry = false)
    }

    /** A server destroy packet makes any queued metadata for those IDs obsolete. */
    fun forgetEntities(channel: Any, entityIds: IntArray) {
        if (entityIds.isEmpty()) return
        val callbacks = synchronized(connections) {
            val state = connections[channel]
            if (state != null) synchronized(state) { entityIds.forEach(state.pending::remove) }
            collectCleanupsDrainedCallbacksLocked()
        }
        callbacks.forEach { it() }
    }

    /** A sent native flags entry supersedes any queued restoration for that entity. */
    fun nativeFlagsSent(channel: Any, entityId: Int, flags: Byte) {
        if (isOwnWrite(channel, entityId, flags)) return
        val callbacks = synchronized(connections) {
            val state = connections[channel]
            if (state != null) {
                synchronized(state) {
                    state.pending[entityId]?.takeIf { it.cleanup }?.let { state.pending.remove(entityId) }
                }
            }
            collectCleanupsDrainedCallbacksLocked()
        }
        callbacks.forEach { it() }
    }

    fun hasPendingCleanup(channel: Any, entityId: Int): Boolean = synchronized(connections) {
        val state = connections[channel] ?: return@synchronized false
        synchronized(state) { state.pending[entityId]?.cleanup == true }
    }

    /** Drops queued glow-on updates when the owner closes but preserves restorations. */
    fun retire() {
        retired = true
        val callbacks = synchronized(connections) {
            connections.values.forEach { state ->
                synchronized(state) {
                    state.pending.entries.removeIf { !it.value.cleanup }
                }
            }
            collectCleanupsDrainedCallbacksLocked()
        }
        callbacks.forEach { it() }
    }

    /** Runs once no pending or in-flight restoration remains, including disconnect cleanup. */
    fun whenCleanupsDrained(callback: () -> Unit) {
        val runNow = synchronized(connections) {
            if (hasPendingCleanupsLocked()) {
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
        val batch = synchronized(state) {
            state.pending.values.toList().also { pending ->
                state.pending.clear()
                pending.forEach { delivery -> state.inFlight[delivery.entityId] = delivery }
            }
        }
        if (batch.isEmpty()) {
            finish(channel, state, emptyList(), emptyList(), retry = false)
            return
        }

        val deferred = mutableListOf<ViewerEntityGlowDelivery>()
        val accepted = mutableListOf<ViewerEntityGlowDelivery>()
        var wrote = false
        var failure: Throwable? = null
        for (cleanup in listOf(true, false)) {
            val group = batch.filter { it.cleanup == cleanup && (cleanup || !retired) }
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
        finish(channel, state, batch, deferred, retry = deferred.isNotEmpty())
    }

    private fun finish(
        channel: Any,
        state: ConnectionState,
        completed: List<ViewerEntityGlowDelivery>,
        deferred: List<ViewerEntityGlowDelivery>,
        retry: Boolean,
    ) {
        var next = false
        var retryNext = false
        val callbacks = synchronized(connections) {
            if (connections[channel] !== state) return
            synchronized(state) {
                completed.forEach { delivery ->
                    if (state.inFlight[delivery.entityId] == delivery) state.inFlight.remove(delivery.entityId)
                }
                deferred.forEach { delivery ->
                    if (!retired || delivery.cleanup) state.pending.putIfAbsent(delivery.entityId, delivery)
                }
                if (state.pending.isEmpty()) {
                    state.scheduled = false
                    state.failureLogged = false
                } else {
                    next = true
                    retryNext = retry
                }
            }
            collectCleanupsDrainedCallbacksLocked()
        }
        callbacks.forEach { it() }
        if (next) schedule(channel, state, retryNext)
    }

    private fun schedule(channel: Any, state: ConnectionState, retry: Boolean) {
        try {
            val channelRef = WeakReference(channel)
            val stateRef = WeakReference(state)
            val task = {
                val currentState = stateRef.get()
                val currentChannel = channelRef.get()
                if (currentState != null) {
                    if (currentChannel != null) drain(currentChannel, currentState) else forgetState(currentState)
                }
            }
            if (retry) backend.retry(channel, task) else backend.enqueue(channel, task)
        } catch (failure: Throwable) {
            warnOnce(state, failure)
            if (!backend.isOpen(channel)) {
                forgetChannel(channel)
            } else {
                synchronized(state) { state.scheduled = false }
            }
        }
    }

    private fun warnOnce(state: ConnectionState, failure: Throwable) {
        val log = synchronized(state) {
            if (state.failureLogged) false else {
                state.failureLogged = true
                true
            }
        }
        if (log) logger.log(Level.WARNING, "Viewer entity glow packet send failed; retaining latest flags", failure)
    }

    private fun clear(state: ConnectionState) {
        synchronized(state) {
            state.pending.clear()
            state.inFlight.clear()
            state.scheduled = false
        }
    }

    private fun forgetState(state: ConnectionState) {
        val callbacks = synchronized(connections) {
            connections.entries.removeIf { it.value === state }
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

    private fun hasPendingCleanupsLocked(): Boolean = connections.values.any { state ->
        synchronized(state) {
            state.pending.values.any { it.cleanup } || state.inFlight.values.any { it.cleanup }
        }
    }

    private fun collectCleanupsDrainedCallbacksLocked(): List<() -> Unit> =
        if (cleanupsDrainedCallbacks.isEmpty() || hasPendingCleanupsLocked()) {
            emptyList()
        } else {
            cleanupsDrainedCallbacks.toList().also { cleanupsDrainedCallbacks.clear() }
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
