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
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.Bukkit
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

/**
 * Owns temporary viewer-only glowing for live entities.
 *
 * Construct, call [set] and close on Paper's primary thread after PacketEvents
 * has initialized. The installed PacketEvents plugin is required. No Bukkit
 * entity glow state is changed: selected entity metadata has only its common
 * flags glow bit forced for that viewer, including later native metadata
 * updates. [set] with `false` immediately restores the entity's current native
 * flags. Selections are cleared on viewer disconnect and close; callers should
 * clear a selection with [set] when it is no longer needed.
 */
class PaperViewerEntityGlow internal constructor(
    private val plugin: Plugin,
    private val transport: ViewerEntityGlowPacketTransport,
) : AutoCloseable, Listener {
    /** Creates an owner backed by the already initialized PacketEvents plugin. */
    constructor(plugin: Plugin) : this(plugin, PacketEventsViewerEntityGlowPacketTransport)

    private val selections = ViewerEntityGlowSelections()
    @Volatile private var closed = false

    private val packetListener = object : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
        override fun onPacketSend(event: PacketSendEvent) {
            if (closed || event.isCancelled) return
            val viewerId = event.user.uuid
            when (event.packetType) {
                PacketType.Play.Server.ENTITY_METADATA -> {
                    val metadata = WrapperPlayServerEntityMetadata(event)
                    if (!selections.isSelected(viewerId, metadata.entityId)) return
                    val adjusted = withViewerGlow(metadata.entityMetadata) ?: return
                    metadata.entityMetadata = adjusted
                    event.markForReEncode(true)
                }
                PacketType.Play.Server.DESTROY_ENTITIES -> {
                    forgetEntities(viewerId, WrapperPlayServerDestroyEntities(event).entityIds)
                }
            }
        }

        override fun onUserDisconnect(event: UserDisconnectEvent) {
            selections.forgetViewer(event.user.uuid)
        }
    }

    init {
        checkPrimaryThread()
        transport.register(packetListener)
        try {
            plugin.server.pluginManager.registerEvents(this, plugin)
        } catch (failure: Throwable) {
            HandlerList.unregisterAll(this)
            transport.unregister(packetListener)
            throw failure
        }
    }

    /** Sets or clears this viewer's glow override without changing server entity state. */
    fun set(viewer: Player, entity: LivingEntity, glowing: Boolean) {
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
        transport.send(viewer, entity.entityId, if (glowing) withGlowBit(nativeFlags, true) else nativeFlags)
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
        transport.unregister(packetListener)

        selections.drain().forEach { (key, reference) ->
            val viewer = Bukkit.getPlayer(key.viewerId)?.takeIf(Player::isOnline) ?: return@forEach
            val entity = reference.get()?.takeIf { it.isValid && it.entityId == key.entityId } ?: return@forEach
            transport.send(viewer, key.entityId, transport.nativeFlags(entity))
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
}

internal data class ViewerEntityGlowKey(val viewerId: UUID, val entityId: Int)

/** Concurrent because PacketEvents observes metadata on connection event loops. */
internal class ViewerEntityGlowSelections {
    private val selected = ConcurrentHashMap<ViewerEntityGlowKey, WeakReference<LivingEntity>>()

    val size: Int get() = selected.size

    fun select(key: ViewerEntityGlowKey, entity: LivingEntity) {
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

    fun drain(): List<Pair<ViewerEntityGlowKey, WeakReference<LivingEntity>>> =
        selected.entries.map { it.key to it.value }.also { selected.clear() }
}

/** Exact PacketEvents boundary used by the native implementation and deterministic tests. */
internal interface ViewerEntityGlowPacketTransport {
    fun register(listener: PacketListenerCommon)
    fun unregister(listener: PacketListenerCommon)
    fun send(viewer: Player, entityId: Int, flags: Byte)
    fun nativeFlags(entity: LivingEntity): Byte
}

private object PacketEventsViewerEntityGlowPacketTransport : ViewerEntityGlowPacketTransport {
    override fun nativeFlags(entity: LivingEntity): Byte = nativeEntityFlags(entity)

    override fun register(listener: PacketListenerCommon) {
        PacketEvents.getAPI().eventManager.registerListener(listener)
    }

    override fun unregister(listener: PacketListenerCommon) {
        PacketEvents.getAPI().eventManager.unregisterListener(listener)
    }

    override fun send(viewer: Player, entityId: Int, flags: Byte) {
        PacketEvents.getAPI().playerManager.sendPacket(
            viewer,
            WrapperPlayServerEntityMetadata(
                entityId,
                listOf(EntityData(0, EntityDataTypes.BYTE, flags)),
            ),
        )
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
internal fun nativeEntityFlags(entity: LivingEntity): Byte =
    SpigotConversionUtil.getEntityMetadata(entity)
        .first { it.index == ENTITY_FLAGS_INDEX && it.type == EntityDataTypes.BYTE }.value as Byte

private const val ENTITY_FLAGS_INDEX = 0
private const val ENTITY_GLOWING_FLAG = 0x40
