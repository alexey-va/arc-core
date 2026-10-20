package ru.arc.paper.npc

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.InteractionHand
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.paper.display.PacketDisplayChannelBackend
import ru.arc.paper.display.PacketEventsChannelBackend
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/** Native capture/listener boundary. Packet callbacks never read a Player or actor handle. */
internal class PacketNpcTransport(
    plugin: Plugin,
    source: String,
    private val input: (UUID, PacketNpcConnection, Int, PacketNpcLease, PacketNpcInteraction) -> Unit,
) : AutoCloseable {
    private val api = requireNotNull(PacketEvents.getAPI()) { "PacketEvents is unavailable" }
    private val backend = PacketEventsChannelBackend(plugin, source)
    private val logger = plugin.logger
    private val connections = ConcurrentHashMap<UUID, PacketNpcConnection>()
    val ownedIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val listener = object : PacketListenerAbstract(PacketListenerPriority.HIGH) {
        override fun onPacketReceive(event: PacketReceiveEvent) {
            if (event.packetType != PacketType.Play.Client.INTERACT_ENTITY || event.isCancelled) return
            val packet = WrapperPlayClientInteractEntity(event)
            if (packet.entityId !in ownedIds) return
            event.isCancelled = true
            val id = event.user.uuid ?: return
            val connection = connections[id] ?: return
            if (!connection.matches(event.channel)) return
            val action = when (packet.action) {
                WrapperPlayClientInteractEntity.InteractAction.ATTACK -> PacketNpcInteraction.ATTACK
                WrapperPlayClientInteractEntity.InteractAction.INTERACT -> {
                    if (packet.hand != InteractionHand.MAIN_HAND) return
                    PacketNpcInteraction.INTERACT
                }
                // The client emits INTERACT_AT followed by INTERACT; act exactly once.
                else -> return
            }
            val admittedLease = connection.admitInput(packet.entityId) ?: return
            input(id, connection, packet.entityId, admittedLease, action)
        }

        override fun onUserDisconnect(event: UserDisconnectEvent) {
            val id = event.user.uuid ?: return
            val connection = connections[id] ?: return
            if (connection.matches(event.user.channel) && connections.remove(id, connection)) connection.disconnect()
        }
    }

    init { api.eventManager.registerListener(listener) }

    @Suppress("DEPRECATION")
    fun nextEntityId(): Int = Bukkit.getUnsafe().nextEntityId()
    fun itemSnapshot(item: ItemStack) = SpigotConversionUtil.fromBukkitItemStack(item).copy()

    fun connection(player: Player): PacketNpcConnection? {
        val channel = api.playerManager.getChannel(player) ?: return null
        if (!backend.isOpen(channel)) return null
        val old = connections[player.uniqueId]
        if (old != null && old.matches(channel) && old.isUsable) return old
        if (old != null) {
            if (old.matches(channel)) {
                old.closeAsync()
                if (!old.isDrained) return null
            }
            if (!old.matches(channel)) old.disconnect()
        }
        return PacketNpcConnection(channel, logger, backend).also { connections[player.uniqueId] = it }
    }

    fun forget(id: UUID) { connections.remove(id)?.disconnect() }
    override fun close() {
        api.eventManager.unregisterListener(listener)
        connections.clear()
        ownedIds.clear()
    }
}

/**
 * One weakly-held connection and one latest desired frame. Short synchronized admission
 * only swaps detached values; all packet construction, encoding, deltas and writes use Netty.
 */
internal class PacketNpcConnection(
    channel: Any,
    private val logger: Logger,
    private val backend: PacketDisplayChannelBackend,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val channel = WeakReference(channel)
    private val lock = Any()
    private val leases = ConcurrentHashMap<Int, PacketNpcLease>()
    private val audienceFences = ConcurrentHashMap<Int, AtomicBoolean>()
    @Volatile private var latest = emptyMap<Int, PacketNpcFrame>()
    private var pending: Pending? = null
    private var scheduled = false
    private var sent = linkedMapOf<Int, PacketNpcFrame>() // Netty only
    private var uncertain = linkedMapOf<Int, PacketNpcFrame>() // possible partial writes
    private var resumeEntityId: Int? = null
    private val closing = AtomicBoolean()
    private val disconnected = AtomicBoolean()
    private val completion = CompletableFuture<Void>()
    private val inputPending = AtomicBoolean()
    private val lastInput = AtomicLong(Long.MIN_VALUE)
    @Volatile private var failure: Throwable? = null
    @Volatile var isDrained = false
        private set
    val isUsable: Boolean get() = !closing.get() && !disconnected.get() && failure == null
    fun matches(channel: Any): Boolean = this.channel.get() === channel
    fun lease(entityId: Int): PacketNpcLease? = leases[entityId]?.takeIf(PacketNpcLease::isActive)
    fun revoke(entityId: Int) { audienceFences[entityId]?.set(false); leases[entityId]?.revoke() }
    fun revokeAll() { audienceFences.values.forEach { it.set(false) }; leases.values.forEach(PacketNpcLease::revoke) }
    fun revokeChunk(chunk: Long) {
        latest.values.filter { it.chunkKey == chunk }.forEach { revoke(it.entityId) }
        leases.values.filter { it.frame.chunkKey == chunk }.forEach { revoke(it.frame.entityId) }
    }

    fun admitInput(entityId: Int): PacketNpcLease? {
        if (!isUsable) return null
        val admittedLease = lease(entityId) ?: return null
        if (!inputPending.compareAndSet(false, true)) return null
        val now = nanoTime()
        val previous = lastInput.get()
        if (previous != Long.MIN_VALUE && now - previous < 100_000_000L) { inputPending.set(false); return null }
        lastInput.set(now)
        return admittedLease
    }
    fun finishInput() { inputPending.set(false) }

    fun submit(frames: List<PacketNpcFrame>, resetChunks: Set<Long> = emptySet(), resetAll: Boolean = false) {
        if (!isUsable) return
        val ids = frames.mapTo(hashSetOf()) { it.entityId }
        audienceFences.keys.filter { it !in ids }.forEach { audienceFences.remove(it)?.set(false) }
        if (resetAll) revokeAll()
        resetChunks.forEach(::revokeChunk)
        val desired = frames.associate { frame ->
            val fence = audienceFences.compute(frame.entityId) { _, existing ->
                existing?.takeIf { it.get() } ?: AtomicBoolean(true)
            }!!
            frame.entityId to frame.copy(audience = fence)
        }
        latest = desired
        leases.forEach { (id, lease) ->
            if (id !in desired || resetAll || lease.frame.chunkKey in resetChunks || desired[id]?.chunkKey in resetChunks) lease.revoke()
        }
        offer(Pending(desired, resetChunks.toSet(), resetAll))
    }

    fun closeAsync(): CompletableFuture<Void> {
        if (closing.compareAndSet(false, true)) {
            revokeAll()
            offer(Pending(emptyMap(), emptySet(), false))
        }
        return completion.copy()
    }

    fun disconnect() {
        disconnected.set(true)
        revokeAll()
        synchronized(lock) { pending = null }
        completion.completeExceptionally(CancellationException("NPC connection disconnected"))
    }

    private fun offer(value: Pending) {
        val enqueue = synchronized(lock) {
            pending = pending?.merge(value) ?: value
            if (scheduled) false else { scheduled = true; true }
        }
        if (enqueue) schedule(false)
    }

    private fun schedule(retry: Boolean) {
        val channel = channel.get()
        if (channel == null || !backend.isOpen(channel)) { disconnect(); return }
        try {
            if (retry) backend.retry(channel, ::drain) else backend.enqueue(channel, ::drain)
        } catch (error: Throwable) {
            fail(error)
            completion.completeExceptionally(error)
        }
    }

    private fun drain() {
        if (disconnected.get()) return
        val channel = channel.get()
        if (channel == null || !backend.isOpen(channel)) { disconnect(); return }
        val work = synchronized(lock) { pending.also { pending = null } }
        if (work == null) { synchronized(lock) { scheduled = false }; return }
        var deferred = false
        var wrote = false
        try {
            val desired = if (closing.get() || failure != null) emptyMap() else work.frames.filterValues { it.lifetime.get() && it.audience.get() }
            val retired = (sent + uncertain).filter { (id, old) ->
                val target = desired[id]
                target == null || target.lifetime !== old.lifetime || target.audience !== old.audience || target.worldId != old.worldId ||
                    work.resetAll || old.chunkKey in work.resetChunks || target.chunkKey in work.resetChunks ||
                    leases[id]?.isActive() == false || id in uncertain
            }
            for ((id, old) in retired) {
                val lease = leases[id]
                lease?.revoke()
                if (!backend.tryWrite(channel, PacketNpcPackets.remove(old, lease), true)) { deferred = true; break }
                wrote = true
                sent.remove(id); uncertain.remove(id); leases.remove(id)
            }
            if (!deferred) {
                val frames = desired.values.toList()
                val start = frames.indexOfFirst { it.entityId == resumeEntityId }.coerceAtLeast(0)
                for (offset in frames.indices) {
                    val frame = frames[(start + offset) % frames.size]
                    val id = frame.entityId
                    if (!frame.lifetime.get() || !frame.audience.get()) continue
                    val old = sent[id]
                    val packets = if (old == null) PacketNpcPackets.spawn(frame) else PacketNpcPackets.update(old, frame)
                    if (packets.isNotEmpty()) {
                        uncertain[id] = frame
                        if (!backend.tryWrite(channel, packets, false)) {
                            uncertain.remove(id); resumeEntityId = id; deferred = true; break
                        }
                        wrote = true
                        uncertain.remove(id)
                    }
                    sent[id] = frame
                    if (old == null) leases[id] = PacketNpcLease(channel, frame) else leases[id]?.frame = frame
                }
                if (!deferred) resumeEntityId = null
            }
        } catch (error: Throwable) {
            fail(error)
            deferred = true // retain possibly-written profiles/IDs until cleanup is admitted
        } finally {
            if (wrote) {
                try { backend.flush(channel) } catch (error: Throwable) { fail(error); deferred = true }
            }
        }
        val next = synchronized(lock) {
            if (deferred) {
                val retry = work.copy(resetChunks = emptySet(), resetAll = false)
                pending = pending?.let(retry::merge) ?: retry
            }
            if (pending == null) scheduled = false
            pending != null
        }
        if (closing.get() && sent.isEmpty() && uncertain.isEmpty() && !next) {
            isDrained = true
            val error = failure
            if (error == null) completion.complete(null) else completion.completeExceptionally(error)
        } else if (next) schedule(deferred)
    }

    private fun fail(error: Throwable) {
        if (failure == null) {
            failure = error
            logger.log(Level.WARNING, "Packet NPC connection failed; retiring its entities and profiles before replacement", error)
        }
        revokeAll()
        closing.set(true)
    }

    private data class Pending(val frames: Map<Int, PacketNpcFrame>, val resetChunks: Set<Long>, val resetAll: Boolean) {
        fun merge(next: Pending): Pending {
            val chunks = resetChunks + next.resetChunks
            return next.copy(resetChunks = if (chunks.size > 256) emptySet() else chunks,
                resetAll = resetAll || next.resetAll || chunks.size > 256)
        }
    }
}
