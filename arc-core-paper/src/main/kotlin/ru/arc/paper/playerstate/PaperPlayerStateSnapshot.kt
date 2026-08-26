package ru.arc.paper.playerstate

import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import java.util.UUID

data class PaperLocationSnapshot(
    val worldId: UUID,
    val worldName: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
) {
    fun validated(): PaperLocationSnapshot = apply {
        require(worldName.length in 1..128 && worldName.none(Char::isISOControl)) { "Player-state world name is unsafe" }
        require(listOf(x, y, z).all(Double::isFinite)) { "Player-state location coordinates must be finite" }
        require(yaw.isFinite() && pitch.isFinite()) { "Player-state location angles must be finite" }
    }
}

data class PaperVelocitySnapshot(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    fun validated(): PaperVelocitySnapshot = apply {
        require(listOf(x, y, z).all(Double::isFinite)) { "Player-state velocity must be finite" }
        require(listOf(x, y, z).all { it in -1_000.0..1_000.0 }) { "Player-state velocity is outside its safety bound" }
    }
}

data class PaperPotionEffectSnapshot(
    val typeKey: String,
    val durationTicks: Int,
    val amplifier: Int,
    val ambient: Boolean,
    val particles: Boolean,
    val icon: Boolean,
    val hidden: PaperPotionEffectSnapshot? = null,
) {
    fun validated(depth: Int = 0): PaperPotionEffectSnapshot = apply {
        require(depth <= MAX_HIDDEN_DEPTH) { "Player-state potion chain is too deep" }
        require(NamespacedKey.fromString(typeKey) != null) { "Player-state potion type key is invalid" }
        require(durationTicks == PotionEffect.INFINITE_DURATION || durationTicks in 1..MAX_DURATION_TICKS) {
            "Player-state potion duration is invalid"
        }
        require(amplifier in 0..255) { "Player-state potion amplifier is invalid" }
        hidden?.validated(depth + 1)
    }

    fun toPotionEffect(): PotionEffect {
        validated()
        val key = requireNotNull(NamespacedKey.fromString(typeKey))
        val type = requireNotNull(Registry.EFFECT.get(key)) { "Potion effect type '$typeKey' is not registered" }
        return PotionEffect(type, durationTicks, amplifier, ambient, particles, icon, hidden?.toPotionEffect())
    }

    companion object {
        const val MAX_HIDDEN_DEPTH = 8
        const val MAX_DURATION_TICKS = 1_000_000

        fun capture(effect: PotionEffect): PaperPotionEffectSnapshot = PaperPotionEffectSnapshot(
            typeKey = effect.type.key.toString(),
            durationTicks = effect.duration,
            amplifier = effect.amplifier,
            ambient = effect.isAmbient,
            particles = effect.hasParticles(),
            icon = effect.hasIcon(),
            hidden = effect.hiddenPotionEffect?.let(::capture),
        ).validated()
    }
}

/**
 * Complete mutable Paper player state required for crash-safe gameplay escrow.
 * Domain identifiers such as match id and return backend intentionally remain
 * in the owning plugin's persistence envelope.
 */
data class PaperPlayerStateSnapshot(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val playerId: UUID,
    val capturedAtMillis: Long,
    val location: PaperLocationSnapshot,
    val compassTarget: PaperLocationSnapshot,
    val storage: List<ItemStack?>,
    val armor: List<ItemStack?>,
    val offHand: ItemStack?,
    val cursor: ItemStack?,
    val selectedSlot: Int,
    val health: Double,
    val absorption: Double,
    val foodLevel: Int,
    val saturation: Float,
    val exhaustion: Float,
    val level: Int,
    val experienceProgress: Float,
    val totalExperience: Int,
    val gameMode: GameMode,
    val allowFlight: Boolean,
    val flying: Boolean,
    val flySpeed: Float,
    val walkSpeed: Float,
    val velocity: PaperVelocitySnapshot,
    val fireTicks: Int,
    val fallDistance: Float,
    val remainingAir: Int,
    val maximumAir: Int,
    val noDamageTicks: Int,
    val freezeTicks: Int,
    val gliding: Boolean,
    val swimming: Boolean,
    val sprinting: Boolean,
    val potionEffects: List<PaperPotionEffectSnapshot>,
) {
    fun validated(): PaperPlayerStateSnapshot = apply {
        require(formatVersion == CURRENT_FORMAT_VERSION) { "Unsupported in-memory player-state format" }
        require(capturedAtMillis > 0L) { "Player-state capture time must be positive" }
        location.validated()
        compassTarget.validated()
        require(storage.size == STORAGE_SLOT_COUNT) { "Player-state storage must contain exactly $STORAGE_SLOT_COUNT slots" }
        require(armor.size == ARMOR_SLOT_COUNT) { "Player-state armor must contain exactly $ARMOR_SLOT_COUNT slots" }
        require(selectedSlot in 0..8) { "Player-state selected slot is invalid" }
        require(health.isFinite() && health > 0.0 && health <= 10_000.0) { "Player-state health is invalid" }
        require(absorption.isFinite() && absorption in 0.0..10_000.0) { "Player-state absorption is invalid" }
        require(foodLevel in 0..20) { "Player-state food level is invalid" }
        require(saturation.isFinite() && saturation in 0f..1_000f) { "Player-state saturation is invalid" }
        require(exhaustion.isFinite() && exhaustion in 0f..1_000f) { "Player-state exhaustion is invalid" }
        require(level in 0..1_000_000 && totalExperience >= 0) { "Player-state experience is invalid" }
        require(experienceProgress.isFinite() && experienceProgress in 0f..1f) { "Player-state experience progress is invalid" }
        require(flySpeed.isFinite() && flySpeed in -1f..1f) { "Player-state fly speed is invalid" }
        require(walkSpeed.isFinite() && walkSpeed in -1f..1f) { "Player-state walk speed is invalid" }
        require(!flying || allowFlight) { "Player-state cannot be flying when flight is not allowed" }
        velocity.validated()
        require(fireTicks in -1_000_000..1_000_000) { "Player-state fire ticks are invalid" }
        require(fallDistance.isFinite() && fallDistance in -1_000_000f..1_000_000f) { "Player-state fall distance is invalid" }
        require(remainingAir in -1_000_000..1_000_000) { "Player-state remaining air is invalid" }
        require(maximumAir in 0..1_000_000) { "Player-state maximum air is invalid" }
        require(noDamageTicks in 0..1_000_000) { "Player-state no-damage ticks are invalid" }
        require(freezeTicks in 0..1_000_000) { "Player-state freeze ticks are invalid" }
        require(potionEffects.size <= MAX_POTION_EFFECTS) { "Player-state contains too many potion effects" }
        require(potionEffects.map(PaperPotionEffectSnapshot::typeKey).distinct().size == potionEffects.size) {
            "Player-state contains duplicate potion effect types"
        }
        potionEffects.forEach { it.validated() }
    }

    companion object {
        const val CURRENT_FORMAT_VERSION = 1
        const val STORAGE_SLOT_COUNT = 36
        const val ARMOR_SLOT_COUNT = 4
        const val MAX_POTION_EFFECTS = 64
    }
}

/** JSON/database-friendly authenticated container for a binary snapshot. */
data class PaperPlayerStateEnvelope(
    val formatVersion: Int = PaperPlayerStateSnapshot.CURRENT_FORMAT_VERSION,
    val payloadBase64: String,
    val sha256: String,
)
