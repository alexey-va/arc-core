package ru.arc.paper.display

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.netty.channel.ChannelHelper
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityType
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
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
import org.bukkit.Bukkit
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
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
    )
}

/** Narrow seam used by deterministic tests; production delegates to PacketEvents/Netty. */
internal interface PacketDisplayChannelBackend {
    fun isOpen(channel: Any): Boolean
    fun enqueue(channel: Any, task: () -> Unit)
    fun write(channel: Any, packet: PacketWrapper<*>)
    fun flush(channel: Any)
}

private object PacketEventsChannelBackend : PacketDisplayChannelBackend {
    override fun isOpen(channel: Any): Boolean = ChannelHelper.isOpen(channel)

    override fun enqueue(channel: Any, task: () -> Unit) {
        ChannelHelper.runInEventLoop(channel, Runnable(task))
    }

    override fun write(channel: Any, packet: PacketWrapper<*>) {
        PacketEvents.getAPI().protocolManager.writePacket(channel, packet)
    }

    override fun flush(channel: Any) {
        ChannelHelper.flush(channel)
    }
}

internal class PacketEventsDisplayTransport(
    private val logger: Logger,
    private val backend: PacketDisplayChannelBackend = PacketEventsChannelBackend,
) : PacketDisplayTransport {
    /* Weak keys and weak channel snapshots avoid retaining disconnected players. */
    private val attachments = WeakHashMap<Player, PacketDisplayAttachment>()

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
            val replacement = PacketDisplayAttachment(channel, logger, backend)
            attachments[player] = replacement
            return replacement
        }
    }

    override fun forget(player: Player) {
        synchronized(attachments) { attachments.remove(player) }
    }

    override fun close() {
        /* Queued attachment drains keep their own weak channel and cleanup IDs. */
        synchronized(attachments) { attachments.clear() }
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
) : PacketDisplayConnection {
    private val channelRef = WeakReference(channel)
    private val lock = Any()
    private val attachmentIdentity = Any()
    private var valid = true
    private var scheduled = false
    private var pending: Pending? = null
    private var submitted: Map<Int, PacketDisplayFrame> = emptyMap()
    private var inFlightFrames: Map<Int, PacketDisplayFrame>? = null
    private var cleanupIds: Set<Int> = emptySet()
    private var failureLogged = false

    override val identity: Any get() = attachmentIdentity

    internal fun isUsableFor(channel: Any): Boolean = synchronized(lock) {
        valid && channelRef.get() === channel
    }

    override fun submit(
        desired: List<PacketDisplayFrame>,
        resetChunks: Set<Long>,
        resetAll: Boolean,
    ) {
        val resetSnapshot = resetChunks.toSet()
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
            val next = Pending(frames, resetSnapshot, resetAll, snapTargets).bounded()
            if (valid && !scheduled && previous == null && next.resetChunks.isEmpty() && !next.resetAll && submitted == next.frames) {
                return
            }
            if (scheduled && previous != null && previous.frames == next.frames &&
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
            try {
                process(batch)
            } catch (failure: Throwable) {
                fail(failure)
            } finally {
                synchronized(lock) { inFlightFrames = null }
            }
            synchronized(lock) {
                if (pending == null) {
                    scheduled = false
                    return
                }
            }
        }
    }

    private fun process(batch: DeliveryBatch) {
        val work = batch.pending
        val previous = batch.previous
        val channel = channelRef.get()
        val open = channel?.let { runCatching { backend.isOpen(it) }.getOrElse { failure ->
            fail(failure)
            false
        } } ?: false
        if (channel == null || !open) {
            synchronized(lock) { cleanupIds = cleanupIds + previous.keys }
            fail(IllegalStateException("display channel is closed"))
            return
        }

        val isValid = synchronized(lock) { valid }
        if (!isValid) {
            synchronized(lock) { cleanupIds = cleanupIds + previous.keys }
            cleanup(channel)
            return
        }

        val desired = batch.desired
        val destroys = linkedSetOf<Int>().apply { addAll(synchronized(lock) { cleanupIds }) }
        val spawns = mutableListOf<PacketDisplayFrame>()
        val spawnIds = hashSetOf<Int>()
        val metadata = mutableListOf<Pair<Int, List<EntityData<*>>>>()
        val teleports = mutableListOf<PacketDisplayFrame>()
        val desiredById = desired

        previous.forEach { (id, old) ->
            val current = desiredById[id]
            val forceReplay = work.resetAll || old.chunkKey in work.resetChunks ||
                (current != null && current.chunkKey in work.resetChunks)
            if (current == null || forceReplay || !sameGeneration(old, current)) {
                destroys += id
            }
            if (current != null && (forceReplay || !sameGeneration(old, current))) {
                if (spawnIds.add(id)) spawns += current
            }
        }
        desiredById.forEach { (id, current) ->
            val old = previous[id]
            val forceReplay = work.resetAll || current.chunkKey in work.resetChunks ||
                (old != null && old.chunkKey in work.resetChunks)
            if (old == null || forceReplay || !sameGeneration(old, current)) {
                if (spawnIds.add(id)) spawns += current
            } else {
                val changed = metadataDelta(old, current)
                if (changed.isNotEmpty()) metadata += id to changed
                if (positionChanged(old, current)) teleports += current
            }
        }

        val hasChanges = destroys.isNotEmpty() || spawns.isNotEmpty() ||
            metadata.isNotEmpty() || teleports.isNotEmpty()
        if (!hasChanges) {
            synchronized(lock) { submitted = desiredById }
            return
        }

        /* Record every ID that may have reached the client before the first write. */
        synchronized(lock) { cleanupIds = cleanupIds + previous.keys + desiredById.keys }
        try {
            if (destroys.isNotEmpty()) write(channel, WrapperPlayServerDestroyEntities(*destroys.toIntArray()))
            spawns.forEach { frame ->
                write(channel, spawnPacket(frame))
                write(channel, metadataPacket(frame, metadataFor(frame)))
            }
            metadata.forEach { (id, values) -> write(channel, WrapperPlayServerEntityMetadata(id, values)) }
            teleports.forEach { write(channel, teleportPacket(it)) }
            backend.flush(channel)
            synchronized(lock) {
                submitted = desiredById
                cleanupIds = emptySet()
            }
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    private fun cleanup(channel: Any) {
        val ids = synchronized(lock) { cleanupIds }
        if (ids.isEmpty()) return
        try {
            write(channel, WrapperPlayServerDestroyEntities(*ids.toIntArray()))
            backend.flush(channel)
            synchronized(lock) {
                cleanupIds = emptySet()
                submitted = emptyMap()
            }
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    private fun write(channel: Any, packet: PacketWrapper<*>) = backend.write(channel, packet)

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
    ) {
        fun merge(next: Pending): Pending {
            val mergedChunks = resetChunks + next.resetChunks
            val generations = next.frames.values.map(::generation).toSet()
            val snaps = (snapTargets + next.snapTargets).intersect(generations)
            return if (resetAll || next.resetAll || mergedChunks.size > MAX_RESET_CHUNKS) {
                Pending(next.frames, emptySet(), true, snaps)
            } else {
                Pending(next.frames, mergedChunks, false, snaps)
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
            old.entityId == current.entityId && old.uuid == current.uuid && displayKind(old) == displayKind(current)

        fun visualStateChanged(old: PacketDisplayFrame, current: PacketDisplayFrame): Boolean =
            old.metadata.transform != current.metadata.transform || old.metadata.content != current.metadata.content

        fun positionChanged(old: PacketDisplayFrame, current: PacketDisplayFrame): Boolean =
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
