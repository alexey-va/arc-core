package ru.arc.paper.display

import com.github.retrooper.packetevents.protocol.item.ItemStack
import net.kyori.adventure.text.Component
import java.util.UUID
import kotlin.math.floor

/** Values captured on the server thread; no Bukkit object may reach the connection queue. */
internal data class PacketDisplayFrame(
    val entityId: Int,
    val uuid: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val metadata: PacketDisplayMetadata,
    val worldId: UUID = UUID(0L, 0L),
    /** Native player entity ID when this client-only display is mounted. */
    val attachmentVehicleId: Int? = null,
) {
    val chunkKey: Long get() = (floor(x).toInt().shr(4).toLong() and 0xffffffffL) or
        (floor(z).toInt().shr(4).toLong() shl 32)
}

internal data class DisplayVector(val x: Float, val y: Float, val z: Float)
internal data class DisplayRotation(val x: Float, val y: Float, val z: Float, val w: Float)
internal data class DisplayTransform(
    val translation: DisplayVector = DisplayVector(0f, 0f, 0f),
    val scale: DisplayVector = DisplayVector(1f, 1f, 1f),
    val leftRotation: DisplayRotation = DisplayRotation(0f, 0f, 0f, 1f),
    val rightRotation: DisplayRotation = DisplayRotation(0f, 0f, 0f, 1f),
)

internal data class PacketDisplayMetadata(
    val content: PacketDisplayContent,
    val transform: DisplayTransform = DisplayTransform(),
    val interpolationDelay: Int = 0,
    val interpolationDuration: Int = 0,
    val teleportDuration: Int = 0,
    val billboard: Byte = 0,
    val brightness: Int = -1,
    val viewRange: Float = 1f,
    val shadowRadius: Float = 0f,
    val shadowStrength: Float = 1f,
    val displayWidth: Float = 0f,
    val displayHeight: Float = 0f,
    val glowRgb: Int = -1,
    val glowing: Boolean = false,
)

internal sealed interface PacketDisplayContent {
    data class Block(val stateId: Int) : PacketDisplayContent
    // The item is copied on capture and treated as read-only thereafter. The encoder copies it for each send.
    data class Item(val item: ItemStack, val transform: Byte) : PacketDisplayContent
    data class Text(
        val text: Component,
        val lineWidth: Int = 200,
        val backgroundColor: Int = 0x40000000,
        val textOpacity: Byte = -1,
        val flags: Byte = 0,
    ) : PacketDisplayContent
}
