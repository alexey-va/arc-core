package ru.arc.paper.packet

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper
import com.github.retrooper.packetevents.util.PacketTransformationUtil
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import io.netty.channel.Channel
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcVisualPacketBudget
import ru.arc.paper.api.VisualPacketAdmission
import java.util.concurrent.TimeUnit
import java.util.Locale

/**
 * Opt-in transport for replaceable plugin visuals, never vanilla gameplay packets.
 *
 * Construct on the server thread after the host registers [ArcVisualPacketBudget].
 * Use a stable feature name, without player IDs, positions or session identifiers.
 * [write] belongs on the channel's event loop; it encodes each transaction once,
 * budgets its real uncompressed payload size, then transfers buffer ownership to
 * PacketEvents. Deferred buffers are released immediately. The caller must retain
 * the latest desired state and retry; this gateway never queues packet history.
 * Spawn + initial metadata must be one transaction. Removal/restoration is cleanup.
 * A successful return means handed to Netty, not acknowledged by the client.
 */
class PaperVisualPackets internal constructor(
    private val source: String,
    private val budget: ArcVisualPacketBudget,
) {
    constructor(plugin: Plugin, feature: String) : this(
        "${plugin.name.lowercase(Locale.ROOT)}:$feature", resolveBudget(plugin, feature),
    )

    /** Does not flush. A rejection leaves the entire transaction unsent. */
    fun write(channel: Any, packets: List<PacketWrapper<*>>, cleanup: Boolean = false): VisualPacketAdmission {
        val netty = channel as Channel
        check(netty.eventLoop().inEventLoop()) { "Visual packets must be encoded on the connection event loop" }
        if (!netty.isWritable) return budget.acquire(source, channel, 0, packets.size, cleanup, false)
        val buffers = ArrayDeque<Any>(packets.size)
        try {
            // Match PacketEvents 2.12.1's transformWrappers path, retaining failed
            // encodings here so a rejected/failed transaction releases every buffer.
            packets.forEach { packet ->
                PacketTransformationUtil.transform(packet).forEach { transformed ->
                    synchronized(transformed.bufferLock) {
                        try {
                            transformed.prepareForSend(channel, true)
                            buffers += requireNotNull(transformed.buffer)
                        } catch (failure: Throwable) {
                            transformed.buffer?.let(ByteBufHelper::release)
                            throw failure
                        } finally {
                            transformed.buffer = null
                        }
                    }
                }
            }
            val bytes = Math.toIntExact(buffers.sumOf { ByteBufHelper.readableBytes(it).toLong() })
            val admission = budget.acquire(source, channel, bytes, buffers.size, cleanup, netty.isWritable)
            if (admission != VisualPacketAdmission.ALLOWED) return admission
            val protocol = PacketEvents.getAPI().protocolManager
            while (buffers.isNotEmpty()) {
                val buffer = buffers.removeFirst()
                val size = ByteBufHelper.readableBytes(buffer)
                // PacketEvents takes ownership when writePacket is called, including
                // failed futures. Do not release an already transferred buffer twice.
                protocol.writePacket(channel, buffer)
                budget.recordSent(source, size, 1, cleanup)
            }
            return VisualPacketAdmission.ALLOWED
        } finally {
            buffers.forEach(ByteBufHelper::release)
        }
    }

    companion object {
        private fun resolveBudget(plugin: Plugin, feature: String): ArcVisualPacketBudget {
            check(Bukkit.isPrimaryThread()) { "Visual packet sources must be constructed on the server thread" }
            require(feature.matches(Regex("[a-z0-9][a-z0-9_.-]{0,63}"))) { "Use a stable visual packet feature name" }
            return requireNotNull(plugin.server.servicesManager.load(ArcVisualPacketBudget::class.java)) {
                "ARC visual packet budget is unavailable; install PaperVisualPacketRuntime before visual owners"
            }
        }

        /** One retry per owning latest-state queue; never one task per packet. */
        @JvmStatic
        fun retry(channel: Any, task: () -> Unit) {
            (channel as Channel).eventLoop().schedule(task, 50L, TimeUnit.MILLISECONDS)
        }
    }
}
