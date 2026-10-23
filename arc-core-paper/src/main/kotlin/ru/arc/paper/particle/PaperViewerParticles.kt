package ru.arc.paper.particle

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.color.Color as PacketColor
import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItemStack
import com.github.retrooper.packetevents.protocol.particle.Particle as PacketParticle
import com.github.retrooper.packetevents.protocol.particle.data.ParticleBlockStateData
import com.github.retrooper.packetevents.protocol.particle.data.ParticleColorData
import com.github.retrooper.packetevents.protocol.particle.data.ParticleData
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustColorTransitionData
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData
import com.github.retrooper.packetevents.protocol.particle.data.ParticleItemStackData
import com.github.retrooper.packetevents.protocol.particle.type.ParticleType
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import com.github.retrooper.packetevents.netty.channel.ChannelHelper
import io.netty.channel.Channel
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.paper.api.VisualPacketAdmission
import ru.arc.paper.packet.PaperVisualPackets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends one-shot viewer-only cosmetic particles through the shared visual packet budget.
 *
 * Construct and call [dust] or [emit] on Paper's primary thread. Bukkit values
 * are captured as detached packet data before entering Netty. Rejected,
 * unwritable, or closed-channel effects are dropped; this owner keeps no queue
 * and never retries transient particles. [invalidatePending] and [close] also
 * stop effects already handed to an event loop. Audience and cadence remain caller-owned.
 * Do not use this best-effort path for required gameplay warnings or acknowledgements.
 */
class PaperViewerParticles internal constructor(
    private val transport: ViewerParticleTransport,
) : AutoCloseable {
    /** Uses the shared packet budget under the stable `plugin:feature` source. */
    constructor(plugin: Plugin, feature: String) : this(PacketEventsViewerParticleTransport(plugin, feature))

    private val closed = AtomicBoolean()
    private val generation = AtomicLong()

    /** Sends a single dust packet; packet admission failure is intentionally best-effort. */
    fun dust(
        viewer: Player,
        location: Location,
        options: Particle.DustOptions,
        count: Int,
        offsetX: Double = 0.0,
        offsetY: Double = 0.0,
        offsetZ: Double = 0.0,
        extra: Double = 0.0,
    ) = emit(viewer, location, Particle.DUST, count, offsetX, offsetY, offsetZ, extra, options, false)

    /** No-data particle with Paper's default offsets, speed (1.0) and force (false). */
    fun emit(viewer: Player, location: Location, particle: Particle, count: Int) =
        emit(viewer, location, particle, count, 0.0, 0.0, 0.0, 1.0, null, false)

    /** Explicit overloads keep cross-plugin calls off Kotlin synthetic default-argument methods. */
    fun emit(
        viewer: Player, location: Location, particle: Particle, count: Int,
        offsetX: Double, offsetY: Double, offsetZ: Double, extra: Double,
    ) = emit(viewer, location, particle, count, offsetX, offsetY, offsetZ, extra, null, false)

    fun emit(
        viewer: Player, location: Location, particle: Particle, count: Int,
        offsetX: Double, offsetY: Double, offsetZ: Double, extra: Double, data: Any?,
    ) = emit(viewer, location, particle, count, offsetX, offsetY, offsetZ, extra, data, false)

    /**
     * Captures one cosmetic effect, preserving count=0 directional semantics and [force].
     * Supported data: none, DustOptions, DustTransition, Color (ARGB), BlockData and ItemStack.
     * Missing, mismatched or unsupported data fails before dispatch; there is no native fallback.
     * [force] is the packet's long-distance flag, not an audience selection policy.
     */
    fun emit(
        viewer: Player, location: Location, particle: Particle, count: Int,
        offsetX: Double, offsetY: Double, offsetZ: Double, extra: Double, data: Any?, force: Boolean,
    ) {
        check(Bukkit.isPrimaryThread()) { "Viewer particle state must be captured on the server thread" }
        if (closed.get()) return
        require(count >= 0) { "Particle count cannot be negative" }
        require(listOf(location.x, location.y, location.z, offsetX, offsetY, offsetZ, extra).all(Double::isFinite)) {
            "Particle coordinates and offsets must be finite"
        }
        require(offsetX.toFloat().isFinite() && offsetY.toFloat().isFinite() &&
            offsetZ.toFloat().isFinite() && extra.toFloat().isFinite()
        ) { "Particle offsets must fit the packet format" }
        val world = requireNotNull(location.world) { "Particle location must belong to a world" }
        if (!viewer.isOnline || viewer.world.uid != world.uid) return

        val capturedData = captureParticleData(particle, data)
        // PacketEvents 2.12.1 uses the same key lookup for modern Bukkit particle types.
        @Suppress("UNCHECKED_CAST")
        val type = requireNotNull(ParticleTypes.getByName(particle.key.toString())) {
            "PacketEvents does not support particle ${particle.key}"
        } as ParticleType<ParticleData>
        val capturedGeneration = generation.get()
        transport.emit(
            viewer,
            ViewerParticle(
                type, capturedData,
                location.x, location.y, location.z,
                count, offsetX.toFloat(), offsetY.toFloat(), offsetZ.toFloat(), extra.toFloat(), force,
            ),
        ) { !closed.get() && generation.get() == capturedGeneration }
    }

    /** Drops already-enqueued effects from a finished session while allowing later sessions to reuse this owner. */
    fun invalidatePending() {
        check(Bukkit.isPrimaryThread()) { "Viewer particle state must be changed on the server thread" }
        if (!closed.get()) generation.incrementAndGet()
    }

    override fun close() {
        check(Bukkit.isPrimaryThread()) { "Viewer particle owner must close on the server thread" }
        if (closed.compareAndSet(false, true)) generation.incrementAndGet()
    }
}

internal data class ViewerParticle(
    val type: ParticleType<ParticleData>,
    val data: ViewerParticleData,
    val x: Double,
    val y: Double,
    val z: Double,
    val count: Int,
    val offsetX: Float,
    val offsetY: Float,
    val offsetZ: Float,
    val extra: Float,
    val force: Boolean,
) {
    /** Only detached protocol values are read here, on the connection event loop. */
    fun packet() = WrapperPlayServerParticle(
        PacketParticle(type, data.packetData()), force, Vector3d(x, y, z),
        Vector3f(offsetX, offsetY, offsetZ), extra, count, false,
    )
}

/** Packet-library mutable values are privately owned and copied again for each encoding. */
internal sealed interface ViewerParticleData {
    fun packetData(): ParticleData

    data object None : ViewerParticleData {
        override fun packetData(): ParticleData = ParticleData.emptyData()
    }

    data class Dust(val rgb: Int, val size: Float) : ViewerParticleData {
        override fun packetData() = ParticleDustData(size, PacketColor(rgb))
    }

    data class DustTransition(val fromRgb: Int, val toRgb: Int, val size: Float) : ViewerParticleData {
        override fun packetData() = ParticleDustColorTransitionData(size, PacketColor(fromRgb), PacketColor(toRgb))
    }

    data class ColorValue(val argb: Int) : ViewerParticleData {
        override fun packetData() = ParticleColorData(argb)
    }

    data class Block(val stateId: Int) : ViewerParticleData {
        override fun packetData() = ParticleBlockStateData(WrappedBlockState.getByGlobalId(stateId))
    }

    class Item(private val snapshot: PacketItemStack) : ViewerParticleData {
        override fun packetData() = ParticleItemStackData(snapshot.copy())
    }
}

private fun captureParticleData(particle: Particle, data: Any?): ViewerParticleData {
    val expected = particle.dataType
    if (expected == Void::class.java) {
        require(data == null) { "Particle $particle does not accept data" }
        return ViewerParticleData.None
    }
    require(data != null && expected.isInstance(data)) { "Particle $particle requires ${expected.simpleName}" }
    return when (expected) {
        Particle.DustOptions::class.java -> (data as Particle.DustOptions).let {
            requireDustSize(it.size)
            ViewerParticleData.Dust(it.color.asRGB(), it.size)
        }
        Particle.DustTransition::class.java -> (data as Particle.DustTransition).let {
            requireDustSize(it.size)
            ViewerParticleData.DustTransition(it.color.asRGB(), it.toColor.asRGB(), it.size)
        }
        Color::class.java -> ViewerParticleData.ColorValue((data as Color).asARGB())
        BlockData::class.java -> ViewerParticleData.Block(SpigotConversionUtil.fromBukkitBlockData(data as BlockData).globalId)
        ItemStack::class.java -> ViewerParticleData.Item(SpigotConversionUtil.fromBukkitItemStack((data as ItemStack).clone()).copy())
        else -> throw IllegalArgumentException("Particle $particle data ${expected.simpleName} is not supported by cosmetic delivery")
    }
}

private fun requireDustSize(size: Float) {
    require(size.isFinite() && size >= 0f) { "Dust size must be finite and non-negative" }
}

internal fun interface ViewerParticleTransport {
    fun emit(viewer: Player, particle: ViewerParticle, isCurrent: () -> Boolean)
}

private class PacketEventsViewerParticleTransport(plugin: Plugin, feature: String) : ViewerParticleTransport {
    private val packets = PaperVisualPackets(plugin, feature)

    override fun emit(viewer: Player, particle: ViewerParticle, isCurrent: () -> Boolean) {
        val channel = runCatching { PacketEvents.getAPI().playerManager.getChannel(viewer) as? Channel }.getOrNull()
            ?: return
        if (!ChannelHelper.isOpen(channel) || !channel.isWritable) return

        ChannelHelper.runInEventLoop(channel, Runnable {
            if (!isCurrent() || !ChannelHelper.isOpen(channel) || !channel.isWritable) return@Runnable
            if (packets.write(channel, listOf(particle.packet())) == VisualPacketAdmission.ALLOWED) {
                ChannelHelper.flush(channel)
            }
        })
    }
}
