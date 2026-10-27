package ru.arc.paper.npc

import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItem
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot as PacketSlot
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.paper.api.ArcSceneEquipment
import ru.arc.paper.scene.PaperSceneSnapshots
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.round

/** Immutable, already-resolved skin. Core never performs a profile or skin network lookup. */
data class PacketNpcProfile(val uuid: UUID, val name: String, val texture: String?, val signature: String?) {
    init {
        require(name.matches(Regex("[A-Za-z0-9_]{1,16}"))) { "NPC profile name must be 1–16 ASCII name characters" }
        require(texture == null || texture.length <= 16_384) { "NPC texture property is too large" }
        require(signature == null || (texture != null && signature.length <= 4_096)) { "Invalid NPC texture signature" }
    }
}

enum class PacketNpcPose { STANDING, CROUCHING, SLEEPING }
enum class PacketNpcInteraction { ATTACK, INTERACT }

/**
 * One client-only player actor. All handle reads/mutations belong to Paper's owning thread.
 * No Bukkit entity, AI, collision, inventory, persistence or skin resolver is created.
 * The consumer owns movement, activity, combat and audience policy.
 */
class PacketPlayerNpc internal constructor(
    internal val owner: PaperPacketNpcs,
    val entityId: Int,
    val profile: PacketNpcProfile,
    location: Location,
) {
    private var position = location.clone()
    private var lifetime = AtomicBoolean(true)
    private var active = true
    private var bed: Location? = null
    private var seat: PacketNpcSeat? = null
    var isSeated: Boolean = false
        private set
    private var equipment = emptyMap<PacketSlot, PacketItem>()
    private var observedEquipment = emptyMap<EquipmentSlot, ArcSceneEquipment>()
    private val hidden = mutableSetOf<UUID>()
    private val shown = mutableSetOf<UUID>()
    private var swing = 0L
    private var hurt = 0L
    val uniqueId: UUID get() = profile.uuid
    /** Public observer label; profile names remain the immutable packet identity. */
    var sceneLabel: String = profile.name
        set(value) { owner.checkThread(); require(value.length <= 128); field = value }
    val location: Location get() { owner.checkThread(); return position.clone().also { it.yaw = bodyYaw; it.pitch = pitch } }
    val world: World get() { owner.checkThread(); return requireNotNull(position.world) }
    val isValid: Boolean get() = active && !owner.closed
    // Native 1.21.11 keeps SLEEPING_DIMENSIONS fixed; other poses scale their dimensions.
    val height: Double get() = when (pose) { PacketNpcPose.STANDING -> 1.8 * scale; PacketNpcPose.CROUCHING -> 1.5 * scale; PacketNpcPose.SLEEPING -> 0.2 }
    val eyeHeight: Double get() = when (pose) { PacketNpcPose.STANDING -> 1.62 * scale; PacketNpcPose.CROUCHING -> 1.27 * scale; PacketNpcPose.SLEEPING -> 0.2 }
    /** Native minecraft:scale; no display proxy or server entity is created. */
    var scale: Double = 1.0
        set(value) {
            owner.checkThread()
            require(value.isFinite() && value in 0.25..2.0) { "NPC scale must be finite and between 0.25 and 2.0" }
            field = value
        }
    var bodyYaw: Float = location.yaw
        set(value) { owner.checkThread(); require(value.isFinite()); field = value }
    var headYaw: Float = location.yaw
        set(value) { owner.checkThread(); require(value.isFinite()); field = value }
    var pitch: Float = location.pitch
        set(value) { owner.checkThread(); require(value.isFinite()); field = value.coerceIn(-90f, 90f) }
    var pose: PacketNpcPose = PacketNpcPose.STANDING
        set(value) { owner.checkThread(); if (value != PacketNpcPose.STANDING) stand(); field = value }
    var sleepingBed: Location?
        get() = bed?.clone()
        set(value) {
            owner.checkThread()
            require(value == null || value.world?.uid == position.world?.uid) { "NPC bed must be in the actor world" }
            bed = value?.clone()
        }
    var viewRange: Double = 48.0
        set(value) {
            owner.checkThread(); require(value.isFinite() && value in 0.0..256.0)
            if (field != value) { field = value; owner.invalidateAudience(this) }
        }
    var isVisibleByDefault: Boolean = true
        set(value) { owner.checkThread(); field = value; owner.invalidateAudience(this) }

    /** Continuous small moves use relative move/look packets; a world change starts a new generation. */
    fun teleport(location: Location) {
        owner.checkThread()
        requireNotNull(location.world)
        require(listOf(location.x, location.y, location.z).all(Double::isFinite))
        stand()
        if (position.world?.uid != location.world?.uid) {
            lifetime.set(false)
            lifetime = AtomicBoolean(true)
            bed = null
            owner.invalidateAudience(this)
        }
        position = location.clone()
        bodyYaw = location.yaw
        pitch = location.pitch
    }

    /**
     * Sit at an authoritative player-feet anchor using a client-only marker carrier.
     * The consumer must first reach and validate its seat; this does not find safe terrain.
     * [location] keeps the same feet-origin contract as [teleport], including native scaled
     * dimensions and attached displays. No entity, task or collision is added to the server.
     * Teleport, a non-standing pose and removal end the mount. Call [stand] before resuming
     * movement, then supply the consumer's safe exit position through [teleport].
     */
    fun sit(location: Location) {
        owner.checkThread()
        check(isValid) { "NPC is removed" }
        require(location.world?.uid == position.world?.uid) { "NPC seat must be in the actor world" }
        teleport(location)
        pose = PacketNpcPose.STANDING
        bed = null
        if (seat == null) seat = PacketNpcSeat(owner.nextEntityId(), UUID.randomUUID())
        isSeated = true
    }

    /** End the native riding pose at the current feet position; does not choose or teleport to an exit. */
    fun stand() { owner.checkThread(); isSeated = false }

    fun setEquipment(slot: EquipmentSlot, item: ItemStack) {
        owner.checkThread()
        val target = when (slot) {
            EquipmentSlot.HAND -> PacketSlot.MAIN_HAND
            EquipmentSlot.OFF_HAND -> PacketSlot.OFF_HAND
            EquipmentSlot.FEET -> PacketSlot.BOOTS
            EquipmentSlot.LEGS -> PacketSlot.LEGGINGS
            EquipmentSlot.CHEST -> PacketSlot.CHEST_PLATE
            EquipmentSlot.HEAD -> PacketSlot.HELMET
            else -> error("Player NPCs support the six player equipment slots")
        }
        val captured = owner.itemSnapshot(item)
        if (equipment[target] != captured) equipment = equipment + (target to captured)
        val observed = PaperSceneSnapshots.equipment(slot, item)
        observedEquipment = if (observed == null) observedEquipment - slot else observedEquipment + (slot to observed)
    }

    internal fun sceneEquipment(): List<ArcSceneEquipment> = observedEquipment.values.toList()

    fun swingMainHand() { owner.checkThread(); swing++ }
    fun playHurtAnimation() { owner.checkThread(); hurt++ }
    fun showTo(player: Player) = showTo(player.uniqueId)
    fun showTo(playerId: UUID) { owner.checkThread(); hidden.remove(playerId); shown.add(playerId) }
    fun hideFrom(player: Player) = hideFrom(player.uniqueId)
    fun hideFrom(playerId: UUID) {
        owner.checkThread(); shown.remove(playerId); hidden.add(playerId); owner.invalidateAudience(this, playerId)
    }

    fun remove() {
        owner.checkThread()
        if (!active) return
        stand()
        active = false
        lifetime.set(false)
        owner.remove(this)
    }

    internal fun audienceAllows(viewerId: UUID): Boolean =
        isValid && viewerId !in hidden && (isVisibleByDefault || viewerId in shown)

    internal fun frame(): PacketNpcFrame = PacketNpcFrame(
        entityId, profile, requireNotNull(position.world).uid,
        quantize(position.x), quantize(position.y), quantize(position.z),
        angle(bodyYaw), angle(headYaw), angle(pitch), pose,
        bed?.takeIf { pose == PacketNpcPose.SLEEPING }?.let { PacketNpcBed(it.blockX, it.blockY, it.blockZ) },
        equipment, swing, hurt, lifetime, scale = scale, seat = seat, seated = isSeated,
    )

    internal fun lease(viewerId: UUID): PacketNpcLease? = owner.lease(viewerId, entityId)

    private fun quantize(value: Double): Double = round(value * 4096.0) / 4096.0
    private fun angle(value: Float): Float = floor(value * 256.0 / 360.0).toInt().toByte() * 360f / 256f
}

internal data class PacketNpcBed(val x: Int, val y: Int, val z: Int)
internal data class PacketNpcSeat(val entityId: Int, val uuid: UUID)

/** Detached values only; [lifetime] is a cancellation fence, never a Bukkit callback. */
internal data class PacketNpcFrame(
    val entityId: Int, val profile: PacketNpcProfile, val worldId: UUID,
    val x: Double, val y: Double, val z: Double,
    val bodyYaw: Float, val headYaw: Float, val pitch: Float,
    val pose: PacketNpcPose, val bed: PacketNpcBed?,
    val equipment: Map<PacketSlot, PacketItem>,
    val swing: Long, val hurt: Long, val lifetime: AtomicBoolean,
    val audience: AtomicBoolean = AtomicBoolean(true),
    val scale: Double = 1.0,
    // Retain the allocated identity after dismount: an interrupted update may still have
    // written the old carrier, so retirement must clean it even when the newest pose stands.
    val seat: PacketNpcSeat? = null,
    val seated: Boolean = false,
) {
    val chunkKey: Long get() = (floor(x).toInt().shr(4).toLong() and 0xffffffffL) or (floor(z).toInt().shr(4).toLong() shl 32)
    val teamName: String get() = "arcnpc${entityId.toString(36)}"
}

/**
 * A per-viewer admitted-spawn generation. Displays recheck it on the same Netty loop
 * before spawning/mounting; NPC cleanup destroys registered children before their carrier.
 */
internal class PacketNpcLease(channel: Any, @Volatile var frame: PacketNpcFrame) {
    private val channel = WeakReference(channel)
    private val active = AtomicBoolean(true)
    private val children = ConcurrentHashMap.newKeySet<Int>()
    fun isActive(): Boolean = active.get() && frame.lifetime.get() && frame.audience.get()
    fun isActive(channel: Any): Boolean = isActive() && this.channel.get() === channel
    fun revoke() { active.set(false) }
    fun hasChild(entityId: Int): Boolean = entityId in children
    fun registerChild(entityId: Int, channel: Any): Boolean {
        if (!isActive(channel)) return false
        val added = children.add(entityId)
        if (isActive(channel)) return true
        if (added) children.remove(entityId)
        return false
    }
    fun forgetChild(entityId: Int) { children.remove(entityId) }
    fun childIds(): IntArray = children.toIntArray()
}
