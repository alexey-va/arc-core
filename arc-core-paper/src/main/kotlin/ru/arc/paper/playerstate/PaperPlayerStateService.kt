package ru.arc.paper.playerstate

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import ru.arc.paper.teleport.TeleportMatchTolerance
import java.util.UUID
import kotlin.math.abs

data class PlayerStateRestoreReceipt(
    val playerId: UUID,
    val envelopeSha256: String,
)

/**
 * Captures, restores and verifies complete Paper player state on the primary
 * server thread. The owning plugin must durably commit [PaperPlayerStateEnvelope]
 * before any mutation and acknowledge its domain escrow only after this service
 * returns a [PlayerStateRestoreReceipt].
 */
class PaperPlayerStateService(
    private val codec: PaperPlayerStateCodec = PaperPlayerStateCodec(),
    private val primaryThread: () -> Boolean = Bukkit::isPrimaryThread,
    private val persistPlayerData: (Player) -> Unit = Player::saveData,
    private val locationTolerance: TeleportMatchTolerance = TeleportMatchTolerance(),
) {
    fun capture(player: Player, capturedAtMillis: Long): PaperPlayerStateSnapshot {
        requirePrimaryThread()
        require(player.isOnline && !player.isDead) { "Cannot capture an offline or dead player" }
        val location = player.location
        val compass = player.compassTarget
        return PaperPlayerStateSnapshot(
            playerId = player.uniqueId,
            capturedAtMillis = capturedAtMillis,
            location = location.snapshot(),
            compassTarget = compass.snapshot(),
            storage = player.inventory.storageContents.map(::cloneOrNull),
            armor = player.inventory.armorContents.map(::cloneOrNull),
            offHand = cloneOrNull(player.inventory.itemInOffHand),
            cursor = cloneOrNull(player.itemOnCursor),
            selectedSlot = player.inventory.heldItemSlot,
            health = player.health,
            absorption = player.absorptionAmount,
            foodLevel = player.foodLevel,
            saturation = player.saturation,
            exhaustion = player.exhaustion,
            level = player.level,
            experienceProgress = player.exp,
            totalExperience = player.totalExperience,
            gameMode = player.gameMode,
            allowFlight = player.allowFlight,
            flying = player.isFlying,
            flySpeed = player.flySpeed,
            walkSpeed = player.walkSpeed,
            velocity = player.velocity.let { PaperVelocitySnapshot(it.x, it.y, it.z) },
            fireTicks = player.fireTicks,
            fallDistance = player.fallDistance,
            remainingAir = player.remainingAir,
            maximumAir = player.maximumAir,
            noDamageTicks = player.noDamageTicks.coerceAtLeast(0),
            freezeTicks = player.freezeTicks.coerceAtLeast(0),
            gliding = player.isGliding,
            swimming = player.isSwimming,
            sprinting = player.isSprinting,
            potionEffects = player.activePotionEffects.map(PaperPotionEffectSnapshot::capture),
        ).validated()
    }

    fun captureEnvelope(player: Player, capturedAtMillis: Long): PaperPlayerStateEnvelope =
        codec.encode(capture(player, capturedAtMillis))

    fun restoreAndVerify(
        player: Player,
        envelope: PaperPlayerStateEnvelope,
        teleport: (Player, Location) -> Boolean = { target, location -> target.teleport(location) },
    ): PlayerStateRestoreReceipt {
        val snapshot = codec.decode(envelope)
        restoreAndVerify(player, snapshot, teleport)
        return PlayerStateRestoreReceipt(player.uniqueId, envelope.sha256)
    }

    fun restoreAndVerify(
        player: Player,
        snapshot: PaperPlayerStateSnapshot,
        teleport: (Player, Location) -> Boolean = { target, location -> target.teleport(location) },
    ) {
        requirePrimaryThread()
        snapshot.validated()
        require(player.isOnline && !player.isDead) { "Cannot restore an offline or dead player" }
        require(player.uniqueId == snapshot.playerId) { "Player-state snapshot belongs to a different player" }

        val destination = snapshot.location.resolve(player)
        val compass = snapshot.compassTarget.resolve(player)
        player.closeInventory()
        player.inventory.storageContents = snapshot.storage.map(::cloneOrNull).toTypedArray()
        player.inventory.armorContents = snapshot.armor.map(::cloneOrNull).toTypedArray()
        player.inventory.setItemInOffHand(snapshot.offHand?.clone() ?: ItemStack.empty())
        player.setItemOnCursor(snapshot.cursor?.clone() ?: ItemStack.empty())
        player.inventory.heldItemSlot = snapshot.selectedSlot
        check(teleport(player, destination)) { "Player-state recovery teleport was rejected" }

        player.compassTarget = compass
        player.gameMode = snapshot.gameMode
        player.allowFlight = snapshot.allowFlight
        player.isFlying = snapshot.flying && snapshot.allowFlight
        player.flySpeed = snapshot.flySpeed
        player.walkSpeed = snapshot.walkSpeed
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        snapshot.potionEffects.forEach { player.addPotionEffect(it.toPotionEffect()) }
        val maximumHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        require(snapshot.health <= maximumHealth + HEALTH_EPSILON) {
            "Player-state health exceeds the player's current maximum health"
        }
        player.health = snapshot.health
        player.absorptionAmount = snapshot.absorption
        player.foodLevel = snapshot.foodLevel
        player.saturation = snapshot.saturation
        player.exhaustion = snapshot.exhaustion
        player.totalExperience = snapshot.totalExperience
        player.level = snapshot.level
        player.exp = snapshot.experienceProgress
        player.maximumAir = snapshot.maximumAir
        player.remainingAir = snapshot.remainingAir
        player.fireTicks = snapshot.fireTicks
        player.fallDistance = snapshot.fallDistance
        player.noDamageTicks = snapshot.noDamageTicks
        player.freezeTicks = snapshot.freezeTicks
        if (player.isGliding != snapshot.gliding) player.isGliding = snapshot.gliding
        @Suppress("DEPRECATION")
        if (player.isSwimming != snapshot.swimming) player.isSwimming = snapshot.swimming
        if (player.isSprinting != snapshot.sprinting) player.isSprinting = snapshot.sprinting
        player.velocity = Vector(snapshot.velocity.x, snapshot.velocity.y, snapshot.velocity.z)
        player.updateInventory()

        val mismatches = mismatches(player, snapshot)
        check(mismatches.isEmpty()) { "Player-state verification failed: ${mismatches.joinToString(",")}" }
        persistPlayerData(player)
    }

    /** Returns bounded field names only; item contents never enter diagnostics. */
    fun mismatches(player: Player, snapshot: PaperPlayerStateSnapshot): List<String> {
        requirePrimaryThread()
        return buildList {
            if (!sameItems(player.inventory.storageContents.asList(), snapshot.storage)) add("storage")
            if (!sameItems(player.inventory.armorContents.asList(), snapshot.armor)) add("armor")
            if (!sameItem(player.inventory.itemInOffHand, snapshot.offHand)) add("offHand")
            if (!sameItem(player.itemOnCursor, snapshot.cursor)) add("cursor")
            if (player.inventory.heldItemSlot != snapshot.selectedSlot) add("selectedSlot")
            if (!sameLocation(player.location, snapshot.location)) add("location")
            if (!sameLocation(player.compassTarget, snapshot.compassTarget)) add("compassTarget")
            if (player.gameMode != snapshot.gameMode) add("gameMode")
            if (player.allowFlight != snapshot.allowFlight || player.isFlying != (snapshot.flying && snapshot.allowFlight)) add("flight")
            if (abs(player.flySpeed - snapshot.flySpeed) > FLOAT_EPSILON || abs(player.walkSpeed - snapshot.walkSpeed) > FLOAT_EPSILON) {
                add("movementSpeeds")
            }
            if (player.foodLevel != snapshot.foodLevel || abs(player.saturation - snapshot.saturation) > FLOAT_EPSILON ||
                abs(player.exhaustion - snapshot.exhaustion) > FLOAT_EPSILON
            ) add("food")
            if (player.level != snapshot.level || abs(player.exp - snapshot.experienceProgress) > FLOAT_EPSILON ||
                player.totalExperience != snapshot.totalExperience
            ) add("experience")
            if (abs(player.health - snapshot.health) > HEALTH_EPSILON ||
                abs(player.absorptionAmount - snapshot.absorption) > HEALTH_EPSILON
            ) add("health")
            if (player.maximumAir != snapshot.maximumAir || player.remainingAir != snapshot.remainingAir ||
                player.fireTicks != snapshot.fireTicks || abs(player.fallDistance - snapshot.fallDistance) > FLOAT_EPSILON ||
                player.noDamageTicks != snapshot.noDamageTicks || player.freezeTicks != snapshot.freezeTicks
            ) add("timers")
            if (player.isGliding != snapshot.gliding || player.isSwimming != snapshot.swimming || player.isSprinting != snapshot.sprinting) {
                add("movementFlags")
            }
            if (player.velocity.distanceSquared(Vector(snapshot.velocity.x, snapshot.velocity.y, snapshot.velocity.z)) > VELOCITY_EPSILON_SQUARED) {
                add("velocity")
            }
            val currentEffects = player.activePotionEffects.map(PaperPotionEffectSnapshot::capture).sortedBy(PaperPotionEffectSnapshot::typeKey)
            if (currentEffects != snapshot.potionEffects.sortedBy(PaperPotionEffectSnapshot::typeKey)) add("potionEffects")
        }
    }

    private fun requirePrimaryThread() {
        check(primaryThread()) { "Paper player state may only be accessed on the primary server thread" }
    }

    private fun PaperLocationSnapshot.resolve(player: Player): Location {
        val world = requireNotNull(player.server.getWorld(worldId)) {
            "Player-state world '$worldName' ($worldId) is not loaded"
        }
        return Location(world, x, y, z, yaw, pitch)
    }

    private fun Location.snapshot(): PaperLocationSnapshot {
        val currentWorld = requireNotNull(world) { "Player-state location must have a world" }
        return PaperLocationSnapshot(currentWorld.uid, currentWorld.name, x, y, z, yaw, pitch).validated()
    }

    private fun sameLocation(current: Location, expected: PaperLocationSnapshot): Boolean =
        current.world?.uid == expected.worldId &&
            abs(current.x - expected.x) <= locationTolerance.coordinate &&
            abs(current.y - expected.y) <= locationTolerance.coordinate &&
            abs(current.z - expected.z) <= locationTolerance.coordinate &&
            abs(current.yaw - expected.yaw) <= locationTolerance.angle &&
            abs(current.pitch - expected.pitch) <= locationTolerance.angle

    private fun sameItems(first: List<ItemStack?>, second: List<ItemStack?>): Boolean =
        first.size == second.size && first.indices.all { sameItem(first[it], second[it]) }

    private fun sameItem(first: ItemStack?, second: ItemStack?): Boolean {
        val normalizedFirst = first?.takeUnless(ItemStack::isEmpty)
        val normalizedSecond = second?.takeUnless(ItemStack::isEmpty)
        return normalizedFirst == normalizedSecond
    }

    private fun cloneOrNull(item: ItemStack?): ItemStack? = item?.takeUnless(ItemStack::isEmpty)?.clone()

    private companion object {
        const val FLOAT_EPSILON = 0.0001f
        const val HEALTH_EPSILON = 0.001
        const val VELOCITY_EPSILON_SQUARED = 0.000001
    }
}
