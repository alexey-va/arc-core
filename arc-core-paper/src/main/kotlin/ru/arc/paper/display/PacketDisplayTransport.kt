package ru.arc.paper.display

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.netty.channel.ChannelHelper
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityType
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItemStack
import com.github.retrooper.packetevents.util.Quaternion4f
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import org.bukkit.Bukkit
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.paper.api.VisualPacketAdmission
import ru.arc.paper.packet.PaperVisualPackets
import java.lang.ref.WeakReference
import java.util.Optional
import java.util.UUID
import java.util.WeakHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Server-thread port for the client-only display packet transport.
 *
 * Captures Bukkit values on the server thread and queues only immutable frame
 * snapshots to Netty. The returned attachment never retains the Player and its
 * channel is weakly referenced. One attachment keeps a bounded latest frame and
 * one queued event-loop drain; replay requests and pending snap boundaries are
 * merged while that drain is pending.
 */
internal interface PacketDisplayTransport : AutoCloseable {
    fun blockStateId(block: BlockData): Int
    fun itemSnapshot(item: ItemStack): PacketItemStack
    fun nextEntityId(): Int
    fun connection(player: Player): PacketDisplayConnection?
    fun forget(player: Player)
}

internal interface PacketDisplayConnection {
    val identity: Any

    fun submit(
        desired: List<PacketDisplayFrame>,
        resetChunks: Set<Long> = emptySet(),
        resetAll: Boolean = false,
        desiredPassengers: Map<Int, List<Int>> = emptyMap(),
        nativePassengerSnapshots: Map<Int, List<Int>> = emptyMap(),
        liveVehicleIds: Set<Int>? = null,
    )
}

/** Narrow seam used by deterministic tests; production delegates to PacketEvents/Netty. */
internal interface PacketDisplayChannelBackend {
    fun isOpen(channel: Any): Boolean
    fun enqueue(channel: Any, task: () -> Unit)
    fun retry(channel: Any, task: () -> Unit)
    fun tryWrite(channel: Any, packets: List<PacketWrapper<*>>, cleanup: Boolean): Boolean
    fun flush(channel: Any)
}

private class PacketEventsChannelBackend(plugin: Plugin, source: String) : PacketDisplayChannelBackend {
    private val packets = PaperVisualPackets(plugin, source)
    override fun isOpen(channel: Any): Boolean = ChannelHelper.isOpen(channel)

    override fun enqueue(channel: Any, task: () -> Unit) {
        ChannelHelper.runInEventLoop(channel, Runnable(task))
    }

    override fun retry(channel: Any, task: () -> Unit) = PaperVisualPackets.retry(channel, task)

    override fun tryWrite(channel: Any, packets: List<PacketWrapper<*>>, cleanup: Boolean): Boolean =
        this.packets.write(channel, packets, cleanup) == VisualPacketAdmission.ALLOWED

    override fun flush(channel: Any) {
        ChannelHelper.flush(channel)
    }
}

internal class PacketEventsDisplayTransport(
    private val logger: Logger,
    private val backend: PacketDisplayChannelBackend,
) : PacketDisplayTransport {
    constructor(plugin: Plugin, source: String) : this(plugin.logger, PacketEventsChannelBackend(plugin, source))
    /* Weak keys and weak channel snapshots avoid retaining disconnected players. */
    private val attachments = WeakHashMap<Player, PacketDisplayAttachment>()
    private val passengerStates = mutableMapOf<UUID, WeakReference<PacketDisplayPassengerState>>()
    private var closed = false
    private val passengerListener = object : PacketListenerAbstract(PacketListenerPriority.MONITOR) {
        override fun onPacketSend(event: PacketSendEvent) {
            if (event.isCancelled) return
            val viewerId = event.user.uuid ?: return
            val state = synchronized(passengerStates) { passengerStates[viewerId]?.get() } ?: return
            when (event.packetType) {
                PacketType.Play.Server.SET_PASSENGERS -> {
                    val packet = WrapperPlayServerSetPassengers(event)
                    if (state.isOwnPassengerWrite(packet.entityId)) return
                    val original = packet.passengers
                    val merged = state.observeNative(packet.entityId, original)
                    if (!original.contentEquals(merged)) {
                        packet.passengers = merged
                        event.markForReEncode(true)
                    }
                }
                PacketType.Play.Server.DESTROY_ENTITIES -> {
                    state.forgetVehicles(WrapperPlayServerDestroyEntities(event).entityIds)
                }
            }
        }

        override fun onUserDisconnect(event: UserDisconnectEvent) {
            event.user.uuid?.let { viewerId ->
                synchronized(passengerStates) { passengerStates.remove(viewerId)?.get()?.closeConnection() }
            }
        }
    }

    init {
        PacketEvents.getAPI().eventManager.registerListener(passengerListener)
    }

    override fun blockStateId(block: BlockData): Int =
        SpigotConversionUtil.fromBukkitBlockData(block).globalId

    override fun itemSnapshot(item: ItemStack): PacketItemStack =
        SpigotConversionUtil.fromBukkitItemStack(item).copy()

    @Suppress("DEPRECATION")
    override fun nextEntityId(): Int = Bukkit.getUnsafe().nextEntityId()

    override fun connection(player: Player): PacketDisplayConnection? {
        val channel = runCatching { PacketEvents.getAPI().playerManager.getChannel(player) }.getOrNull()
            ?: return null
        if (!backend.isOpen(channel)) return null

        synchronized(attachments) {
            val current = attachments[player]
            if (current != null && current.isUsableFor(channel)) return current
            if (current?.deferReplacementUntilCleanup(channel) == true) return null
            val passengerState = PacketDisplayPassengerState()
            synchronized(passengerStates) { passengerStates[player.uniqueId] = WeakReference(passengerState) }
            val replacement = PacketDisplayAttachment(channel, logger, backend, passengerState)
            attachments[player] = replacement
            return replacement
        }
    }

    override fun forget(player: Player) {
        synchronized(attachments) { attachments.remove(player)?.abandonPassengerState() }
        synchronized(passengerStates) { passengerStates.remove(player.uniqueId) }
    }

    override fun close() {
        /* Queued attachment drains keep their own weak channel and cleanup IDs. */
        synchronized(attachments) { attachments.clear() }
        synchronized(passengerStates) { passengerStates.clear() }
        if (!closed) {
            closed = true
            PacketEvents.getAPI().eventManager.unregisterListener(passengerListener)
        }
    }
}

/** Per-viewer native passenger baseline plus only this queue's admitted fake IDs. */
internal class PacketDisplayPassengerState {
    private val trackedVehicles = mutableSetOf<Int>()
    private val native = mutableMapOf<Int, List<Int>>()
    private val observedNative = mutableSetOf<Int>()
    private val mounted = mutableMapOf<Int, List<Int>>()
    private val deadVehicles = mutableSetOf<Int>()
    private val invalidatedVehicles = mutableSetOf<Int>()
    private val ownPassengerWrites = ThreadLocal<Set<Int>?>()
    @Volatile private var disconnected = false

    /** PacketEvents raises send events while encoding Core's already budgeted passenger writes. */
    fun <T> duringOwnPassengerWrites(vehicleIds: Set<Int>, action: () -> T): T {
        val previous = ownPassengerWrites.get()
        ownPassengerWrites.set(previous.orEmpty() + vehicleIds)
        return try {
            action()
        } finally {
            if (previous == null) ownPassengerWrites.remove() else ownPassengerWrites.set(previous)
        }
    }

    fun isOwnPassengerWrite(vehicleId: Int): Boolean = vehicleId in ownPassengerWrites.get().orEmpty()

    @Synchronized
    fun seedNative(vehicleId: Int, passengers: List<Int>) {
        trackedVehicles += vehicleId
        deadVehicles.remove(vehicleId)
        if (vehicleId !in observedNative) native[vehicleId] = withoutMounted(vehicleId, passengers)
    }

    @Synchronized
    fun nativePassengers(vehicleId: Int, fallback: List<Int> = emptyList()): List<Int> {
        if (vehicleId in deadVehicles) return emptyList()
        return native[vehicleId] ?: withoutMounted(vehicleId, fallback).also { native[vehicleId] = it }
    }

    /** Captures a full native replacement list, then appends admitted packet displays. */
    @Synchronized
    fun observeNative(vehicleId: Int, passengers: IntArray): IntArray {
        if (isOwnPassengerWrite(vehicleId)) return passengers
        if (disconnected || vehicleId !in trackedVehicles) return passengers
        deadVehicles.remove(vehicleId)
        observedNative += vehicleId
        native[vehicleId] = withoutMounted(vehicleId, passengers.toList())
        return (native[vehicleId].orEmpty() + mounted[vehicleId].orEmpty()).distinct().toIntArray()
    }

    @Synchronized
    fun setMounted(vehicleId: Int, passengers: List<Int>) {
        deadVehicles.remove(vehicleId)
        invalidatedVehicles.remove(vehicleId)
        if (passengers.isEmpty()) {
            trackedVehicles.remove(vehicleId)
            native.remove(vehicleId)
            observedNative.remove(vehicleId)
            mounted.remove(vehicleId)
        } else {
            trackedVehicles += vehicleId
            mounted[vehicleId] = passengers.distinct()
        }
    }

    @Synchronized
    fun forgetVehicles(vehicleIds: IntArray) {
        vehicleIds.forEach { id ->
            if (id in trackedVehicles) {
                deadVehicles += id
                if (mounted[id].orEmpty().isNotEmpty()) invalidatedVehicles += id
            }
            trackedVehicles.remove(id)
            native.remove(id)
            observedNative.remove(id)
            mounted.remove(id)
        }
    }

    @Synchronized
    fun clear() {
        native.clear()
        trackedVehicles.clear()
        observedNative.clear()
        mounted.clear()
        deadVehicles.clear()
        invalidatedVehicles.clear()
    }

    fun isDisconnected(): Boolean = disconnected

    @Synchronized
    fun isDead(vehicleId: Int): Boolean = vehicleId in deadVehicles

    @Synchronized
    fun invalidatedVehicles(): Set<Int> = invalidatedVehicles.toSet()

    @Synchronized
    fun acknowledgeInvalidations(vehicleIds: Set<Int>) {
        invalidatedVehicles.removeAll(vehicleIds)
    }

    @Synchronized
    fun retainVehicles(vehicleIds: Set<Int>) {
        (trackedVehicles - vehicleIds - mounted.keys).forEach { id ->
            trackedVehicles.remove(id)
            native.remove(id)
            observedNative.remove(id)
            deadVehicles.remove(id)
        }
        val discarded = deadVehicles - vehicleIds - mounted.keys
        deadVehicles.removeAll(discarded)
        invalidatedVehicles.removeAll(discarded)
    }

    @Synchronized
    fun closeConnection() {
        disconnected = true
        clear()
    }

    @Synchronized
    private fun withoutMounted(vehicleId: Int, passengers: List<Int>): List<Int> {
        val fakeIds = mounted[vehicleId].orEmpty().toSet()
        return passengers.filterNot(fakeIds::contains)
    }
}

/**
 * One channel attachment. This class is internal to keep the pure queue seam
 * testable without Bukkit or a live PacketEvents channel.
 * A pending zero-duration transform edge survives replacement by a newer frame
 * only while that same entity ID, UUID, and display-content kind remain present.
 * The latest frame is sent with the edge; its effective metadata becomes the
 * next submitted baseline so a later normal-duration frame restores animation.
 */
internal class PacketDisplayAttachment internal constructor(
    channel: Any,
    private val logger: Logger,
    private val backend: PacketDisplayChannelBackend,
    private val passengerState: PacketDisplayPassengerState = PacketDisplayPassengerState(),
) : PacketDisplayConnection {
    private val channelRef = WeakReference(channel)
    private val lock = Any()
    private val attachmentIdentity = Any()
    private var valid = true
    private var scheduled = false
    private var pending: Pending? = null
    private var submitted: Map<Int, PacketDisplayFrame> = emptyMap()
    @Volatile private var submittedPassengers: Map<Int, List<Int>> = emptyMap()
    private var inFlightFrames: Map<Int, PacketDisplayFrame>? = null
    private var cleanupIds: Set<Int> = emptySet()
    private var failureLogged = false
    private var resumeEntityId: Int? = null

    override val identity: Any get() = attachmentIdentity

    internal fun isUsableFor(channel: Any): Boolean = synchronized(lock) {
        valid && channelRef.get() === channel
    }

    internal fun abandonPassengerState() = passengerState.closeConnection()

    /** Keep same-channel listener state alive until an invalid queue has removed all admitted IDs. */
    internal fun deferReplacementUntilCleanup(channel: Any): Boolean {
        val sameChannel = synchronized(lock) { channelRef.get() === channel }
        if (!sameChannel) {
            abandonPassengerState()
            return false
        }
        val cleaned = synchronized(lock) {
            !valid && pending == null && !scheduled && inFlightFrames == null &&
                submitted.isEmpty() && submittedPassengers.isEmpty() && cleanupIds.isEmpty()
        }
        if (cleaned) return false
        submit(emptyList())
        return true
    }

    override fun submit(
        desired: List<PacketDisplayFrame>,
        resetChunks: Set<Long>,
        resetAll: Boolean,
        desiredPassengers: Map<Int, List<Int>>,
        nativePassengerSnapshots: Map<Int, List<Int>>,
        liveVehicleIds: Set<Int>?,
    ) {
        val resetSnapshot = resetChunks.toSet()
        val passengerSnapshot = desiredPassengers.mapValues { (_, ids) -> ids.distinct().sorted() }.filterValues { it.isNotEmpty() }
        val nativeSnapshot = nativePassengerSnapshots.mapValues { (_, ids) -> ids.distinct() }
        nativeSnapshot.forEach { (vehicleId, ids) -> passengerState.seedNative(vehicleId, ids) }
        val liveSnapshot = liveVehicleIds?.toSet()
        val invalidatedSnapshot = passengerState.invalidatedVehicles()
        var schedule = false
        synchronized(lock) {
            val previous = pending
            val frames = captureFrames(desired)
            val snapTargets = frames.mapNotNull { (id, current) ->
                val prior = previous?.frames?.let { queued ->
                    if (id in queued) queued[id] else inFlightFrames?.get(id) ?: submitted[id]
                } ?: inFlightFrames?.get(id) ?: submitted[id]
                if (prior != null && sameGeneration(prior, current) &&
                    current.metadata.interpolationDuration == 0 && visualStateChanged(prior, current)
                ) generation(current) else null
            }.toSet()
            val next = Pending(
                frames, resetSnapshot, resetAll, snapTargets, passengerSnapshot, nativeSnapshot, liveSnapshot,
                invalidatedSnapshot,
            ).bounded()
            if (valid && !scheduled && previous == null && next.resetChunks.isEmpty() && !next.resetAll &&
                next.resyncVehicles.isEmpty() && submitted == next.frames && submittedPassengers == next.desiredPassengers
            ) {
                return
            }
            if (scheduled && previous != null && previous.frames == next.frames &&
                previous.desiredPassengers == next.desiredPassengers &&
                next.resyncVehicles.all(previous.resyncVehicles::contains) &&
                next.resetChunks.all { it in previous.resetChunks } && (!next.resetAll || previous.resetAll)
            ) {
                return
            }
            pending = if (previous == null) next else previous.merge(next)
            if (!scheduled) {
                scheduled = true
                schedule = true
            }
        }
        if (!schedule) return
        val channel = channelRef.get()
        if (channel == null) {
            synchronized(lock) { cleanupIds = cleanupIds + submitted.keys }
            fail(IllegalStateException("display channel was collected"))
            synchronized(lock) { scheduled = false }
            return
        }
        try {
            backend.enqueue(channel) { drain() }
        } catch (failure: Throwable) {
            synchronized(lock) { cleanupIds = cleanupIds + submitted.keys }
            fail(failure)
            synchronized(lock) { scheduled = false }
        }
    }

    private fun drain() {
        while (true) {
            val batch = synchronized(lock) {
                val value = pending ?: run {
                    scheduled = false
                    return
                }
                pending = null
                val previous = submitted
                val desired = value.frames.mapValues { (_, frame) ->
                    val old = previous[frame.entityId]
                    if (generation(frame) in value.snapTargets && old != null &&
                        sameGeneration(old, frame) && frame.metadata.interpolationDuration != 0 &&
                        visualStateChanged(old, frame)
                    ) {
                        frame.copy(metadata = frame.metadata.copy(interpolationDuration = 0))
                    } else {
                        frame
                    }
                }
                inFlightFrames = desired
                DeliveryBatch(value, previous, desired)
            }
            val deferred = try {
                process(batch)
            } catch (failure: Throwable) {
                fail(failure)
                null
            } finally {
                synchronized(lock) { inFlightFrames = null }
            }
            if (deferred != null) {
                synchronized(lock) {
                    pending = pending?.let(deferred::merge) ?: deferred
                }
                val channel = channelRef.get()
                try {
                    if (channel != null) {
                        backend.retry(channel) { drain() }
                        return
                    }
                    fail(IllegalStateException("display channel was collected"))
                } catch (failure: Throwable) {
                    fail(failure)
                }
                synchronized(lock) { scheduled = false }
                return
            }
            synchronized(lock) {
                if (pending == null) {
                    scheduled = false
                    return
                }
            }
        }
    }

    /** Returns the coalescible remainder; a denied transaction never advances its baseline. */
    private fun process(batch: DeliveryBatch): Pending? {
        val work = batch.pending
        val channel = channelRef.get()
        if (channel == null || !backend.isOpen(channel)) {
            synchronized(lock) { cleanupIds = cleanupIds + batch.previous.keys }
            fail(IllegalStateException("display channel is closed"))
            return null
        }
        if (!synchronized(lock) { valid }) {
            synchronized(lock) { cleanupIds = cleanupIds + batch.previous.keys }
            return if (cleanup(channel)) null else work.copy(frames = emptyMap(), desiredPassengers = emptyMap())
        }

        val acknowledged = batch.previous.toMutableMap()
        work.resyncVehicles.forEach { vehicleId -> submittedPassengers = submittedPassengers - vehicleId }
        if (work.liveVehicleIds != null) {
            val departed = submittedPassengers.keys.filter { it !in work.liveVehicleIds }
            departed.forEach { vehicleId ->
                passengerState.forgetVehicles(intArrayOf(vehicleId))
                submittedPassengers = submittedPassengers - vehicleId
            }
        }
        val destroys = linkedSetOf<Int>().apply { addAll(synchronized(lock) { cleanupIds }) }
        val forceDetachVehicles = linkedSetOf<Int>()
        val resetVehicles = linkedSetOf<Int>()
        batch.previous.forEach { (id, old) ->
            val current = batch.desired[id]
            val reset = work.resetAll || old.chunkKey in work.resetChunks ||
                (current != null && current.chunkKey in work.resetChunks)
            val replaced = current == null || !sameGeneration(old, current)
            val relationReplaced = current != null &&
                (!sameGeneration(old, current) || old.attachmentVehicleId != current.attachmentVehicleId)
            if (old.attachmentVehicleId != null && (reset || relationReplaced)) {
                forceDetachVehicles += old.attachmentVehicleId
                if (reset) resetVehicles += old.attachmentVehicleId
            }
            if (replaced || reset) destroys += id
        }
        var wrote = false
        var failed = false
        try {
            val detachUpdates = linkedMapOf<Int, List<Int>>()
            (submittedPassengers.keys + forceDetachVehicles).forEach { vehicleId ->
                val oldPassengers = submittedPassengers[vehicleId].orEmpty()
                val wanted = work.desiredPassengers[vehicleId].orEmpty()
                val retained = if (vehicleId in resetVehicles) emptyList() else oldPassengers.filter(wanted::contains)
                if (oldPassengers != retained && (oldPassengers.isNotEmpty() || retained.isNotEmpty())) {
                    detachUpdates[vehicleId] = retained
                }
            }
            if (detachUpdates.isNotEmpty()) {
                if (!writePassengerSets(channel, detachUpdates, work.nativePassengerSnapshots, cleanup = true)) return work
                wrote = true
            }
            if (destroys.isNotEmpty()) {
                synchronized(lock) { cleanupIds = cleanupIds + destroys }
                if (!backend.tryWrite(channel, listOf(WrapperPlayServerDestroyEntities(*destroys.toIntArray())), true)) {
                    return work
                }
                wrote = true
                destroys.forEach(acknowledged::remove)
            }
            // Admit each entity's spawn+metadata/update atomically. Large scenes
            // can progressively appear even when their total size exceeds a burst.
            val frames = batch.desired.values.toList()
            val start = frames.indexOfFirst { it.entityId == resumeEntityId }.coerceAtLeast(0)
            for (offset in frames.indices) {
                val current = frames[(start + offset) % frames.size]
                val id = current.entityId
                val old = acknowledged[id]
                val packets = if (old == null) {
                    listOf(spawnPacket(current), metadataPacket(current, metadataFor(current)))
                } else {
                    buildList {
                        val values = metadataDelta(old, current)
                        if (values.isNotEmpty()) add(metadataPacket(current, values))
                        if (positionChanged(old, current)) add(teleportPacket(current))
                    }
                }
                if (packets.isNotEmpty()) {
                    synchronized(lock) { cleanupIds = cleanupIds + id }
                    if (!backend.tryWrite(channel, packets, false)) {
                        synchronized(lock) { cleanupIds = cleanupIds - id }
                        resumeEntityId = id
                        // Resets were already applied by the destroy transaction.
                        // Missing IDs in the acknowledged baseline still need spawns.
                        return work.copy(resetChunks = emptySet(), resetAll = false)
                    }
                    wrote = true
                }
                acknowledged[id] = current
            }
            resumeEntityId = null
            val mountUpdates = work.desiredPassengers.mapValues { (vehicleId, displayIds) ->
                displayIds.filter { id -> batch.desired[id]?.attachmentVehicleId == vehicleId && id in acknowledged }
            }.filterValues { it.isNotEmpty() }
                .filter { (vehicleId, displayIds) -> submittedPassengers[vehicleId] != displayIds }
            if (mountUpdates.isNotEmpty()) {
                if (!writePassengerSets(channel, mountUpdates, work.nativePassengerSnapshots, cleanup = false)) return work
                wrote = true
            }
            passengerState.acknowledgeInvalidations(work.resyncVehicles)
            passengerState.retainVehicles(submittedPassengers.keys + work.desiredPassengers.keys)
            return null
        } catch (failure: Throwable) {
            failed = true
            throw failure
        } finally {
            if (wrote) backend.flush(channel)
            synchronized(lock) {
                submitted = acknowledged.toMap()
                if (!failed) cleanupIds = emptySet()
            }
        }
    }

    private fun cleanup(channel: Any): Boolean {
        val passengerRestores = submittedPassengers.keys.associateWith { emptyList<Int>() }
        if (passengerRestores.isNotEmpty() && !writePassengerSets(channel, passengerRestores, emptyMap(), cleanup = true)) return false
        val ids = synchronized(lock) { cleanupIds }
        if (ids.isEmpty()) return true
        if (!backend.tryWrite(channel, listOf(WrapperPlayServerDestroyEntities(*ids.toIntArray())), true)) return false
        backend.flush(channel)
        synchronized(lock) {
            cleanupIds = emptySet()
            submitted = emptyMap()
            submittedPassengers = emptyMap()
        }
        return true
    }

    /** Passenger packets are full replacement lists, so read the latest native baseline at write time. */
    private fun writePassengerSets(
        channel: Any,
        desired: Map<Int, List<Int>>,
        fallbackNative: Map<Int, List<Int>>,
        cleanup: Boolean,
    ): Boolean {
        if (desired.isEmpty()) return true
        if (passengerState.isDisconnected()) {
            desired.keys.forEach { vehicleId -> submittedPassengers = submittedPassengers - vehicleId }
            return true
        }
        val liveDesired = desired.filterKeys { !passengerState.isDead(it) }
        val deadDesired = desired.keys - liveDesired.keys
        deadDesired.forEach { vehicleId ->
            submittedPassengers = submittedPassengers - vehicleId
        }
        if (liveDesired.isEmpty()) return true
        val packets = liveDesired.map { (vehicleId, fakePassengers) ->
            val native = passengerState.nativePassengers(vehicleId, fallbackNative[vehicleId].orEmpty())
            WrapperPlayServerSetPassengers(vehicleId, (native + fakePassengers).distinct().toIntArray())
        }
        val priorPassengers = liveDesired.keys.associateWith { submittedPassengers[it].orEmpty() }
        liveDesired.keys.forEach { vehicleId ->
            val possible = (priorPassengers.getValue(vehicleId) + liveDesired.getValue(vehicleId)).distinct().sorted()
            setSubmittedPassengers(vehicleId, possible)
        }
        val admitted = try {
            passengerState.duringOwnPassengerWrites(liveDesired.keys) {
                backend.tryWrite(channel, packets, cleanup)
            }
        } catch (failure: Throwable) {
            // A write may fail after a prefix of its buffers entered the channel.
            // Keep the possible relation until cleanup can safely clear it.
            throw failure
        }
        if (!admitted) {
            priorPassengers.forEach { (vehicleId, passengers) -> setSubmittedPassengers(vehicleId, passengers) }
            return false
        }
        backend.flush(channel)
        liveDesired.forEach { (vehicleId, fakePassengers) ->
            setSubmittedPassengers(vehicleId, fakePassengers.distinct().sorted())
        }
        return true
    }

    private fun setSubmittedPassengers(vehicleId: Int, passengers: List<Int>) {
        submittedPassengers = if (passengers.isEmpty()) submittedPassengers - vehicleId
        else submittedPassengers + (vehicleId to passengers.toList())
        passengerState.setMounted(vehicleId, passengers)
    }

    private fun spawnPacket(frame: PacketDisplayFrame) = WrapperPlayServerSpawnEntity(
        frame.entityId,
        Optional.of(frame.uuid),
        entityType(frame),
        Vector3d(frame.x, frame.y, frame.z),
        frame.pitch,
        frame.yaw,
        frame.yaw,
        0,
        Optional.empty(),
    )

    private fun teleportPacket(frame: PacketDisplayFrame) = WrapperPlayServerEntityTeleport(
        frame.entityId,
        Vector3d(frame.x, frame.y, frame.z),
        frame.yaw,
        frame.pitch,
        false,
    )

    private fun metadataPacket(frame: PacketDisplayFrame, values: List<EntityData<*>>) =
        WrapperPlayServerEntityMetadata(frame.entityId, values)

    private fun metadataFor(frame: PacketDisplayFrame): List<EntityData<*>> =
        metadataValues(null, frame)

    private fun metadataDelta(old: PacketDisplayFrame, current: PacketDisplayFrame): List<EntityData<*>> =
        metadataValues(old, current)

    private fun metadataValues(old: PacketDisplayFrame?, current: PacketDisplayFrame): List<EntityData<*>> {
        val result = mutableListOf<EntityData<*>>()
        val oldMetadata = old?.metadata
        val newMetadata = current.metadata
        val transformChanged = oldMetadata == null || oldMetadata.transform != newMetadata.transform
        addIf(result, oldMetadata?.let { it.glowing != newMetadata.glowing } ?: true, 0, EntityDataTypes.BYTE,
            if (newMetadata.glowing) 0x40.toByte() else 0.toByte())
        /* PE 2.12.1 / MC 1.21.11 display indices: 8 delay, 9 duration, 10 teleport. */
        addIf(result, oldMetadata == null || oldMetadata.interpolationDelay != newMetadata.interpolationDelay || transformChanged,
            8, EntityDataTypes.INT, newMetadata.interpolationDelay)
        addIf(result, oldMetadata == null || oldMetadata.interpolationDuration != newMetadata.interpolationDuration,
            9, EntityDataTypes.INT, newMetadata.interpolationDuration)
        addIf(result, oldMetadata == null || oldMetadata.teleportDuration != newMetadata.teleportDuration,
            10, EntityDataTypes.INT, newMetadata.teleportDuration)
        addIf(result, oldMetadata == null || oldMetadata.transform.translation != newMetadata.transform.translation,
            11, EntityDataTypes.VECTOR3F, newMetadata.transform.translation.packet())
        addIf(result, oldMetadata == null || oldMetadata.transform.scale != newMetadata.transform.scale,
            12, EntityDataTypes.VECTOR3F, newMetadata.transform.scale.packet())
        addIf(result, oldMetadata == null || oldMetadata.transform.leftRotation != newMetadata.transform.leftRotation,
            13, EntityDataTypes.QUATERNION, newMetadata.transform.leftRotation.packet())
        addIf(result, oldMetadata == null || oldMetadata.transform.rightRotation != newMetadata.transform.rightRotation,
            14, EntityDataTypes.QUATERNION, newMetadata.transform.rightRotation.packet())
        addIf(result, oldMetadata == null || oldMetadata.billboard != newMetadata.billboard,
            15, EntityDataTypes.BYTE, newMetadata.billboard)
        addIf(result, oldMetadata == null || oldMetadata.brightness != newMetadata.brightness,
            16, EntityDataTypes.INT, newMetadata.brightness)
        addIf(result, oldMetadata == null || oldMetadata.viewRange != newMetadata.viewRange,
            17, EntityDataTypes.FLOAT, newMetadata.viewRange)
        addIf(result, oldMetadata == null || oldMetadata.shadowRadius != newMetadata.shadowRadius,
            18, EntityDataTypes.FLOAT, newMetadata.shadowRadius)
        addIf(result, oldMetadata == null || oldMetadata.shadowStrength != newMetadata.shadowStrength,
            19, EntityDataTypes.FLOAT, newMetadata.shadowStrength)
        addIf(result, oldMetadata == null || oldMetadata.displayWidth != newMetadata.displayWidth,
            20, EntityDataTypes.FLOAT, newMetadata.displayWidth)
        addIf(result, oldMetadata == null || oldMetadata.displayHeight != newMetadata.displayHeight,
            21, EntityDataTypes.FLOAT, newMetadata.displayHeight)
        addIf(result, oldMetadata == null || oldMetadata.glowRgb != newMetadata.glowRgb,
            22, EntityDataTypes.INT, newMetadata.glowRgb)

        val oldContent = oldMetadata?.content
        when (val content = newMetadata.content) {
            is PacketDisplayContent.Block -> {
                val oldBlock = oldContent as? PacketDisplayContent.Block
                addIf(result, oldBlock == null || oldBlock.stateId != content.stateId,
                    23, EntityDataTypes.BLOCK_STATE, content.stateId)
            }
            is PacketDisplayContent.Item -> {
                val oldItem = oldContent as? PacketDisplayContent.Item
                if (oldItem == null || oldItem.item != content.item) {
                    result += EntityData(23, EntityDataTypes.ITEMSTACK, content.item.copy())
                }
                addIf(result, oldItem == null || oldItem.transform != content.transform,
                    24, EntityDataTypes.BYTE, content.transform)
            }
            is PacketDisplayContent.Text -> {
                val oldText = oldContent as? PacketDisplayContent.Text
                addIf(result, oldText == null || oldText.text != content.text,
                    23, EntityDataTypes.ADV_COMPONENT, content.text)
                addIf(result, oldText == null || oldText.lineWidth != content.lineWidth,
                    24, EntityDataTypes.INT, content.lineWidth)
                addIf(result, oldText == null || oldText.backgroundColor != content.backgroundColor,
                    25, EntityDataTypes.INT, content.backgroundColor)
                addIf(result, oldText == null || oldText.textOpacity != content.textOpacity,
                    26, EntityDataTypes.BYTE, content.textOpacity)
                addIf(result, oldText == null || oldText.flags != content.flags,
                    27, EntityDataTypes.BYTE, content.flags)
            }
        }
        return result
    }

    private fun fail(failure: Throwable) {
        synchronized(lock) {
            cleanupIds = cleanupIds + submitted.keys
            valid = false
            if (failureLogged) return
            failureLogged = true
        }
        logger.log(Level.WARNING, "Packet display batch failed; a new attachment will replay the scene", failure)
    }

    private data class Pending(
        val frames: Map<Int, PacketDisplayFrame>,
        val resetChunks: Set<Long>,
        val resetAll: Boolean,
        val snapTargets: Set<DisplayGeneration> = emptySet(),
        val desiredPassengers: Map<Int, List<Int>> = emptyMap(),
        val nativePassengerSnapshots: Map<Int, List<Int>> = emptyMap(),
        val liveVehicleIds: Set<Int>? = null,
        val resyncVehicles: Set<Int> = emptySet(),
    ) {
        fun merge(next: Pending): Pending {
            val mergedChunks = resetChunks + next.resetChunks
            val generations = next.frames.values.map(::generation).toSet()
            val snaps = (snapTargets + next.snapTargets).intersect(generations)
            return if (resetAll || next.resetAll || mergedChunks.size > MAX_RESET_CHUNKS) {
                Pending(
                    next.frames, emptySet(), true, snaps, next.desiredPassengers,
                    next.nativePassengerSnapshots, next.liveVehicleIds, resyncVehicles + next.resyncVehicles,
                )
            } else {
                Pending(
                    next.frames, mergedChunks, false, snaps, next.desiredPassengers,
                    next.nativePassengerSnapshots, next.liveVehicleIds, resyncVehicles + next.resyncVehicles,
                )
            }
        }

        fun bounded(): Pending = when {
            resetAll -> copy(resetChunks = emptySet())
            resetChunks.size <= MAX_RESET_CHUNKS -> this
            else -> copy(resetChunks = emptySet(), resetAll = true)
        }
    }

    private data class DeliveryBatch(
        val pending: Pending,
        val previous: Map<Int, PacketDisplayFrame>,
        val desired: Map<Int, PacketDisplayFrame>,
    )

    private data class DisplayGeneration(
        val entityId: Int,
        val uuid: UUID,
        val kind: Class<*>,
    )

    private companion object {
        const val MAX_RESET_CHUNKS = 256

        fun captureFrames(frames: List<PacketDisplayFrame>): Map<Int, PacketDisplayFrame> =
            frames.associateBy(PacketDisplayFrame::entityId)

        fun displayKind(frame: PacketDisplayFrame): Class<*> = frame.metadata.content::class.java

        fun generation(frame: PacketDisplayFrame): DisplayGeneration =
            DisplayGeneration(frame.entityId, frame.uuid, displayKind(frame))

        fun sameGeneration(old: PacketDisplayFrame, current: PacketDisplayFrame): Boolean =
            old.entityId == current.entityId && old.uuid == current.uuid &&
                old.worldId == current.worldId && displayKind(old) == displayKind(current)

        fun visualStateChanged(old: PacketDisplayFrame, current: PacketDisplayFrame): Boolean =
            old.metadata.transform != current.metadata.transform || old.metadata.content != current.metadata.content

        fun positionChanged(old: PacketDisplayFrame, current: PacketDisplayFrame): Boolean =
            if (old.attachmentVehicleId == current.attachmentVehicleId && current.attachmentVehicleId != null) false
            else old.attachmentVehicleId != current.attachmentVehicleId ||
                old.x != current.x || old.y != current.y || old.z != current.z ||
                old.yaw != current.yaw || old.pitch != current.pitch

        fun DisplayVector.packet() = Vector3f(x, y, z)
        fun DisplayRotation.packet() = Quaternion4f(x, y, z, w)

        fun <T> addIf(
            values: MutableList<EntityData<*>>,
            changed: Boolean,
            index: Int,
            type: com.github.retrooper.packetevents.protocol.entity.data.EntityDataType<T>,
            value: T,
            enabled: Boolean = true,
        ) {
            if (changed && enabled) values += EntityData(index, type, value)
        }

        fun entityType(frame: PacketDisplayFrame): EntityType = when (frame.metadata.content) {
            is PacketDisplayContent.Block -> EntityTypes.BLOCK_DISPLAY
            is PacketDisplayContent.Item -> EntityTypes.ITEM_DISPLAY
            is PacketDisplayContent.Text -> EntityTypes.TEXT_DISPLAY
        }
    }
}
