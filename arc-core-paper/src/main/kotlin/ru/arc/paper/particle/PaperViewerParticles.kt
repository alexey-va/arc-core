package ru.arc.paper.particle

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.particle.Particle as PacketParticle
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData
import com.github.retrooper.packetevents.protocol.particle.type.ParticleType
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import com.github.retrooper.packetevents.netty.channel.ChannelHelper
import io.netty.channel.Channel
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.api.VisualPacketAdmission
import ru.arc.paper.packet.PaperVisualPackets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends one viewer-only dust effect through the shared visual packet budget.
 *
 * Construct and call [dust] on Paper's primary thread. The viewer and location
 * are reduced to immutable packet values before entering Netty. Rejected,
 * unwritable, or closed-channel effects are dropped; this owner keeps no queue
 * and never retries transient particles. [invalidatePending] and [close] also
 * stop effects already handed to an event loop. Audience and cadence remain caller-owned.
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
        require(options.size.isFinite() && options.size >= 0f) { "Dust size must be finite and non-negative" }
        val world = requireNotNull(location.world) { "Particle location must belong to a world" }
        if (!viewer.isOnline || viewer.world.uid != world.uid) return

        val capturedGeneration = generation.get()
        transport.emit(
            viewer,
            ViewerDustParticle(
                location.x, location.y, location.z,
                options.color.red, options.color.green, options.color.blue, options.size,
                count, offsetX.toFloat(), offsetY.toFloat(), offsetZ.toFloat(), extra.toFloat(),
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

internal data class ViewerDustParticle(
    val x: Double,
    val y: Double,
    val z: Double,
    val red: Int,
    val green: Int,
    val blue: Int,
    val size: Float,
    val count: Int,
    val offsetX: Float,
    val offsetY: Float,
    val offsetZ: Float,
    val extra: Float,
)

internal fun interface ViewerParticleTransport {
    fun emit(viewer: Player, particle: ViewerDustParticle, isCurrent: () -> Boolean)
}

private class PacketEventsViewerParticleTransport(plugin: Plugin, feature: String) : ViewerParticleTransport {
    private val packets = PaperVisualPackets(plugin, feature)

    @Suppress("UNCHECKED_CAST")
    private val dustType = SpigotConversionUtil.fromBukkitParticle(Particle.DUST) as ParticleType<ParticleDustData>

    override fun emit(viewer: Player, particle: ViewerDustParticle, isCurrent: () -> Boolean) {
        val channel = runCatching { PacketEvents.getAPI().playerManager.getChannel(viewer) as? Channel }.getOrNull()
            ?: return
        if (!ChannelHelper.isOpen(channel) || !channel.isWritable) return

        ChannelHelper.runInEventLoop(channel, Runnable {
            if (!isCurrent() || !ChannelHelper.isOpen(channel) || !channel.isWritable) return@Runnable
            val payload = PacketParticle(
                dustType,
                ParticleDustData(particle.size, particle.red, particle.green, particle.blue),
            )
            val packet = WrapperPlayServerParticle(
                payload,
                false,
                Vector3d(particle.x, particle.y, particle.z),
                Vector3f(particle.offsetX, particle.offsetY, particle.offsetZ),
                particle.extra,
                particle.count,
                false,
            )
            if (packets.write(channel, listOf(packet)) == VisualPacketAdmission.ALLOWED) {
                ChannelHelper.flush(channel)
            }
        })
    }
}
