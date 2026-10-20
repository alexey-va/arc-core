package ru.arc.paper.display

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.lang.ref.WeakReference

/**
 * A client-only visual owned by [PaperPacketDisplays], never a Bukkit entity.
 * Read and mutate on the server thread. Mutable Bukkit/JOML inputs and outputs are
 * copied; one immutable snapshot is captured per frame and encoded asynchronously.
 * [remove] is idempotent; the owner destroys received IDs on its next tick or close.
 */
sealed class PacketDisplay protected constructor(
    internal val owner: PaperPacketDisplays,
    val entityId: Int,
    location: Location,
) {
    val uniqueId: UUID = UUID.randomUUID()
    private var position = location.clone()
    private var positionWorldId = requireNotNull(location.world) { "A packet display must belong to a world" }.uid
    private var transform = DisplayTransform()
    private var attachedPlayerId: UUID? = null
    private var attachedPlayer: WeakReference<Player>? = null
    private var visibleTo = mutableSetOf<UUID>()
    private var hiddenFrom = mutableSetOf<UUID>()
    private var active = true
    val isValid: Boolean get() = active && !owner.closed
    val location: Location get() = position.clone()
    internal val attachmentTargetId: UUID? get() = attachedPlayerId
    internal val attachmentPlayer: Player? get() = attachedPlayer?.get()
    var isVisibleByDefault: Boolean = true
        set(value) { owner.checkThread(); if (field != value) { field = value; owner.visibilityChanged() } }
    /** Additional shared gate; never grants access denied by this handle's explicit audience. */
    var audienceGroup: PacketDisplayAudienceGroup? = null
        set(value) {
            owner.checkThread()
            require(value == null || value.owner === owner) { "Audience group must belong to the display owner" }
            require(value == null || !value.isClosed) { "Audience group is closed" }
            if (field !== value) { field = value; owner.visibilityChanged() }
        }
    var billboard: Display.Billboard = Display.Billboard.FIXED
    var brightness: Display.Brightness? = null
    var interpolationDelay: Int = 0
    var interpolationDuration: Int = 0
    var teleportDuration: Int = 0
    var viewRange: Float = 1f
    var shadowRadius: Float = 0f
    var shadowStrength: Float = 1f
    var displayWidth: Float = 0f
    var displayHeight: Float = 0f
    var glowColorOverride: Color? = null
    var isGlowing: Boolean = false

    var transformation: Transformation
        get() = transform.let {
            Transformation(
                Vector3f(it.translation.x, it.translation.y, it.translation.z),
                Quaternionf(it.leftRotation.x, it.leftRotation.y, it.leftRotation.z, it.leftRotation.w),
                Vector3f(it.scale.x, it.scale.y, it.scale.z),
                Quaternionf(it.rightRotation.x, it.rightRotation.y, it.rightRotation.z, it.rightRotation.w),
            )
        }
        set(value) {
            owner.checkThread()
            transform = DisplayTransform(
                value.translation.let { DisplayVector(it.x, it.y, it.z) },
                value.scale.let { DisplayVector(it.x, it.y, it.z) },
                value.leftRotation.let { DisplayRotation(it.x, it.y, it.z, it.w) },
                value.rightRotation.let { DisplayRotation(it.x, it.y, it.z, it.w) },
            )
        }

    fun teleport(location: Location) {
        owner.checkThread()
        val worldId = requireNotNull(location.world) { "A packet display must belong to a world" }.uid
        position = location.clone()
        positionWorldId = worldId
    }

    /**
     * Mounts this client-only display on a native [player], including a Citizens player NPC,
     * without creating a server entity. NPC carriers need no online connection. The existing
     * [transformation] becomes the passenger-local pose; the last [teleport] location remains its
     * detached position. Initial spawn uses the native passenger origin above the carrier's
     * feet, so delayed passenger-link admission does not first show the local pose at its feet.
     */
    fun attachTo(player: Player) {
        owner.checkThread()
        check(isValid) { "A removed packet display cannot be attached" }
        if (attachedPlayerId != player.uniqueId) owner.visibilityChanged()
        attachedPlayerId = player.uniqueId
        attachedPlayer = WeakReference(player)
    }

    /** Returns this display to its last [teleport] position. */
    fun detach() {
        owner.checkThread()
        if (attachedPlayerId != null) owner.visibilityChanged()
        attachedPlayerId = null
        attachedPlayer = null
    }

    fun showTo(player: Player) {
        showTo(player.uniqueId)
    }

    fun showTo(playerId: UUID) {
        owner.checkThread()
        val changed = hiddenFrom.remove(playerId)
        if (visibleTo.add(playerId) || changed) owner.visibilityChanged()
    }

    fun hideFrom(player: Player) {
        hideFrom(player.uniqueId)
    }

    fun hideFrom(playerId: UUID) {
        owner.checkThread()
        val changed = visibleTo.remove(playerId)
        if (hiddenFrom.add(playerId) || changed) owner.visibilityChanged()
    }

    internal fun clearAudienceOverrides() { owner.checkThread(); if (visibleTo.isNotEmpty() || hiddenFrom.isNotEmpty()) owner.visibilityChanged(); visibleTo = mutableSetOf(); hiddenFrom = mutableSetOf() }

    fun remove() {
        owner.checkThread()
        if (!active) return
        active = false
        owner.visibilityChanged()
        attachedPlayerId = null
        attachedPlayer = null
        audienceGroup = null
        owner.remove(this)
        visibleTo = mutableSetOf()
        hiddenFrom = mutableSetOf()
    }

    internal fun visibleTo(viewer: PacketDisplayViewer, frame: PacketDisplayFrame): Boolean {
        if (!isValid || frame.worldId != viewer.worldId || viewer.id in hiddenFrom) return false
        if (!isVisibleByDefault && viewer.id !in visibleTo) return false
        val dx = frame.x - viewer.x
        val dy = frame.y - viewer.y
        val dz = frame.z - viewer.z
        // Native Display view_range is a multiple of 64 blocks.
        val range = viewRange.toDouble().coerceAtLeast(0.0) * 64.0
        return dx * dx + dy * dy + dz * dz <= range * range
    }

    internal fun frame(attachedPlayer: PacketDisplayViewer? = null): PacketDisplayFrame? {
        val targetId = attachedPlayerId
        if (targetId != null && attachedPlayer?.id != targetId) return null
        val worldId = attachedPlayer?.worldId ?: positionWorldId
        return PacketDisplayFrame(
            entityId = entityId,
            uuid = uniqueId,
            x = attachedPlayer?.x ?: position.x,
            y = attachedPlayer?.let { it.y + it.height } ?: position.y,
            z = attachedPlayer?.z ?: position.z,
            yaw = position.yaw,
            pitch = position.pitch,
            metadata = PacketDisplayMetadata(
            content = content(), transform = transform,
            interpolationDelay = interpolationDelay,
            interpolationDuration = interpolationDuration.coerceAtLeast(0),
            teleportDuration = teleportDuration.coerceIn(0, 59),
            billboard = billboard.ordinal.toByte(),
            brightness = brightness?.let { (it.blockLight shl 4) or (it.skyLight shl 20) } ?: -1,
            viewRange = viewRange, shadowRadius = shadowRadius, shadowStrength = shadowStrength,
            displayWidth = displayWidth, displayHeight = displayHeight,
            glowRgb = glowColorOverride?.asRGB() ?: -1, glowing = isGlowing,
        ),
            worldId = worldId,
            attachmentVehicleId = attachedPlayer?.entityId.takeIf { targetId != null },
        )
    }

    internal abstract fun content(): PacketDisplayContent
}

class PacketBlockDisplay internal constructor(
    owner: PaperPacketDisplays, entityId: Int, location: Location, block: BlockData,
) : PacketDisplay(owner, entityId, location) {
    private var storedBlock = block.clone()
    private var stateId = owner.blockStateId(block)
    var block: BlockData
        get() = blockData
        set(value) { blockData = value }
    var blockData: BlockData
        get() = storedBlock.clone()
        set(value) {
            owner.checkThread()
            storedBlock = value.clone()
            stateId = owner.blockStateId(storedBlock)
        }
    override fun content(): PacketDisplayContent = PacketDisplayContent.Block(stateId)
}

class PacketItemDisplay internal constructor(
    owner: PaperPacketDisplays, entityId: Int, location: Location, item: ItemStack,
) : PacketDisplay(owner, entityId, location) {
    private var item = item.clone()
    private var snapshot = owner.itemSnapshot(this.item)
    var itemStack: ItemStack
        get() = item.clone()
        set(value) {
            owner.checkThread()
            item = value.clone()
            snapshot = owner.itemSnapshot(item)
        }
    var itemDisplayTransform: ItemDisplay.ItemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
    override fun content(): PacketDisplayContent = PacketDisplayContent.Item(snapshot, itemDisplayTransform.ordinal.toByte())
}

class PacketTextDisplay internal constructor(
    owner: PaperPacketDisplays, entityId: Int, location: Location, text: Component,
) : PacketDisplay(owner, entityId, location) {
    private var text = text
    var lineWidth: Int = 200
    var backgroundColor: Color? = Color.fromARGB(0x40000000)
    var textOpacity: Byte = -1
    var isShadowed: Boolean = false
    var isSeeThrough: Boolean = false
    var isDefaultBackground: Boolean = false
    var alignment: TextDisplay.TextAlignment = TextDisplay.TextAlignment.CENTER
    fun text(): Component = text
    fun text(value: Component) { owner.checkThread(); text = value }
    override fun content(): PacketDisplayContent {
        var flags = 0
        if (isShadowed) flags = flags or 1
        if (isSeeThrough) flags = flags or 2
        if (isDefaultBackground) flags = flags or 4
        flags = flags or when (alignment) {
            TextDisplay.TextAlignment.LEFT -> 8
            TextDisplay.TextAlignment.RIGHT -> 16
            else -> 0
        }
        return PacketDisplayContent.Text(text, lineWidth, backgroundColor?.asARGB() ?: 0x40000000, textOpacity, flags.toByte())
    }
}
