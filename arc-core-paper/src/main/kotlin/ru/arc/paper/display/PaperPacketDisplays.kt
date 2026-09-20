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
import java.util.UUID

/**
 * Owns ephemeral Block/Item/TextDisplay visuals for one plugin/module lifecycle.
 *
 * Construct, mutate handles and close on the server thread after installing core
 * scheduling. The installed PacketEvents 2.12.1 plugin is required. No server
 * entities or chunk tickets are created. Each tick captures immutable frames and
 * current player connections/received chunks; encoding, delta calculation and
 * writes run on each connection's Netty event loop. Slow connections keep only
 * the latest pending frame. Unchanged frames send no packets.
 *
 * Visibility combines the handle's explicit audience, world, range and received
 * chunks. Client chunk eviction/respawn and connection replacement replay the
 * current frame. Close destroys every possibly received ID, including after a
 * partial write failure, and cancels this owner's tasks/listeners. A visual is not
 * persistent state: callers retain their own durable anchor/crate meaning.
 */
class PaperPacketDisplays internal constructor(
    private val plugin: Plugin,
    private val transport: PacketDisplayTransport,
    private val tasks: LifecycleTaskScope,
    private val audience: PacketDisplayAudienceSource,
) : AutoCloseable, Listener {
    constructor(plugin: Plugin) : this(
        plugin, PacketEventsDisplayTransport(plugin.logger), LifecycleTaskScope(), BukkitPacketDisplayAudience,
    )

    private data class ViewerState(val connection: PacketDisplayConnection, val worldId: UUID)
    private val handles = linkedMapOf<Int, PacketDisplay>()
    private val viewers = mutableMapOf<UUID, ViewerState>()
    private val resetChunks = mutableMapOf<UUID, MutableSet<Long>>()
    private val resetViewers = mutableSetOf<UUID>()
    private var closed = false

    init {
        checkThread()
        plugin.server.pluginManager.registerEvents(this, plugin)
        tasks.runTimer(1L, 1L, ::refresh)
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

    internal fun checkThread() = audience.checkThread()
    internal fun blockStateId(block: BlockData): Int = transport.blockStateId(block)
    internal fun itemSnapshot(item: ItemStack) = transport.itemSnapshot(item)
    internal fun remove(display: PacketDisplay) { handles.remove(display.entityId, display) }

    private fun checkSpawn(location: Location) {
        checkThread()
        check(!closed) { "Packet display owner is closed" }
        requireNotNull(location.world) { "A packet display must belong to a world" }
    }

    internal fun refresh() {
        checkThread()
        if (closed) return
        if (handles.isEmpty() && viewers.isEmpty()) {
            resetChunks.clear()
            resetViewers.clear()
            return
        }
        val frames = handles.values.map { it to it.frame() }
        val currentAudience = audience.capture()
        val present = currentAudience.mapTo(hashSetOf(), PacketDisplayViewer::id)
        (viewers.keys - present).forEach(::forgetViewer)
        currentAudience.forEach { viewer ->
            val desired = frames.asSequence()
                .filter { (display, frame) -> frame.chunkKey in viewer.sentChunks && display.visibleTo(viewer) }
                .map { it.second }.toList()
            val previous = viewers[viewer.id]
            if (desired.isEmpty() && previous == null) {
                resetChunks.remove(viewer.id)
                resetViewers.remove(viewer.id)
                return@forEach
            }
            val connection = transport.connection(viewer.player)
            if (connection == null) {
                forgetViewer(viewer.id)
                return@forEach
            }
            if (previous != null && previous.connection.identity !== connection.identity) {
                // Invalid attachments must still receive cleanup for a partially written batch.
                previous.connection.submit(emptyList())
            }
            connection.submit(
                desired,
                resetChunks.remove(viewer.id)?.toSet().orEmpty(),
                resetViewers.remove(viewer.id) || (previous != null && previous.worldId != viewer.worldId),
            )
            // Keep a connected viewer's attachment even for an empty scene so
            // a failed cleanup can be retried on the following tick.
            viewers[viewer.id] = ViewerState(connection, viewer.worldId)
        }
    }

    @EventHandler
    fun onChunkLoad(event: PlayerChunkLoadEvent) = resetChunk(event.player, event.chunk.chunkKey)

    @EventHandler
    fun onChunkUnload(event: PlayerChunkUnloadEvent) = resetChunk(event.player, event.chunk.chunkKey)

    private fun resetChunk(player: Player, chunkKey: Long) {
        if (!closed && player.uniqueId in viewers) resetChunks.getOrPut(player.uniqueId, ::mutableSetOf).add(chunkKey)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) { resetViewers.add(event.player.uniqueId) }

    @EventHandler
    fun onRespawn(event: PlayerPostRespawnEvent) { resetViewers.add(event.player.uniqueId) }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        forgetViewer(event.player.uniqueId)
        transport.forget(event.player)
    }

    private fun forgetViewer(id: UUID) {
        viewers.remove(id)?.connection?.submit(emptyList())
        resetChunks.remove(id)
        resetViewers.remove(id)
    }

    override fun close() {
        checkThread()
        if (closed) return
        closed = true
        tasks.close()
        HandlerList.unregisterAll(this)
        handles.values.toList().forEach(PacketDisplay::remove)
        viewers.keys.toList().forEach(::forgetViewer)
        resetChunks.clear()
        resetViewers.clear()
        transport.close()
    }
}

/** Server-thread-only audience port; the connection queue never receives these Bukkit references. */
internal interface PacketDisplayAudienceSource {
    fun checkThread()
    fun capture(): List<PacketDisplayViewer>
}

internal data class PacketDisplayViewer(
    val player: Player,
    val id: UUID,
    val worldId: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val sentChunks: Set<Long>,
)

private object BukkitPacketDisplayAudience : PacketDisplayAudienceSource {
    override fun checkThread() { check(Bukkit.isPrimaryThread()) { "Packet display state must be accessed on the server thread" } }
    override fun capture(): List<PacketDisplayViewer> = Bukkit.getOnlinePlayers().map { player ->
        val location = player.location
        PacketDisplayViewer(player, player.uniqueId, player.world.uid, location.x, location.y, location.z, player.sentChunkKeys)
    }
}
