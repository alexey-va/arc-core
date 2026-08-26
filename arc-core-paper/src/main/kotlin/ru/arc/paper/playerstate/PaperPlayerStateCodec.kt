package ru.arc.paper.playerstate

import org.bukkit.GameMode
import org.bukkit.inventory.ItemStack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Injectable seam around Paper's versioned native item NBT APIs. */
interface PaperItemStackBinaryCodec {
    fun encodeItems(items: List<ItemStack?>): ByteArray

    fun decodeItems(payload: ByteArray): List<ItemStack?>

    fun encodeItem(item: ItemStack): ByteArray

    fun decodeItem(payload: ByteArray): ItemStack
}

object NativePaperItemStackBinaryCodec : PaperItemStackBinaryCodec {
    override fun encodeItems(items: List<ItemStack?>): ByteArray = ItemStack.serializeItemsAsBytes(items.toTypedArray())

    override fun decodeItems(payload: ByteArray): List<ItemStack?> =
        ItemStack.deserializeItemsFromBytes(payload).map { it.takeUnless(ItemStack::isEmpty) }

    override fun encodeItem(item: ItemStack): ByteArray = item.serializeAsBytes()

    override fun decodeItem(payload: ByteArray): ItemStack = ItemStack.deserializeBytes(payload)
}

/**
 * Versioned binary codec with strict bounds, trailing-byte rejection and an
 * authenticated SHA-256 envelope. The checksum is corruption evidence, not a
 * substitute for message authentication on an untrusted transport.
 */
class PaperPlayerStateCodec(
    private val itemCodec: PaperItemStackBinaryCodec = NativePaperItemStackBinaryCodec,
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
    private val maxItemBlobBytes: Int = DEFAULT_MAX_ITEM_BLOB_BYTES,
) {
    init {
        require(maxPayloadBytes in 1_024..MAX_ALLOWED_PAYLOAD_BYTES) { "Player-state payload limit is invalid" }
        require(maxItemBlobBytes in 1..maxPayloadBytes) { "Player-state item payload limit is invalid" }
    }

    fun encode(snapshot: PaperPlayerStateSnapshot): PaperPlayerStateEnvelope {
        snapshot.validated()
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(PaperPlayerStateSnapshot.CURRENT_FORMAT_VERSION)
                output.writeUuid(snapshot.playerId)
                output.writeLong(snapshot.capturedAtMillis)
                output.writeLocation(snapshot.location)
                output.writeLocation(snapshot.compassTarget)
                output.writeItemList(snapshot.storage)
                output.writeItemList(snapshot.armor)
                output.writeNullableItem(snapshot.offHand)
                output.writeNullableItem(snapshot.cursor)
                output.writeInt(snapshot.selectedSlot)
                output.writeDouble(snapshot.health)
                output.writeDouble(snapshot.absorption)
                output.writeInt(snapshot.foodLevel)
                output.writeFloat(snapshot.saturation)
                output.writeFloat(snapshot.exhaustion)
                output.writeInt(snapshot.level)
                output.writeFloat(snapshot.experienceProgress)
                output.writeInt(snapshot.totalExperience)
                output.writeBoundedString(snapshot.gameMode.name, 32)
                output.writeBoolean(snapshot.allowFlight)
                output.writeBoolean(snapshot.flying)
                output.writeFloat(snapshot.flySpeed)
                output.writeFloat(snapshot.walkSpeed)
                output.writeDouble(snapshot.velocity.x)
                output.writeDouble(snapshot.velocity.y)
                output.writeDouble(snapshot.velocity.z)
                output.writeInt(snapshot.fireTicks)
                output.writeFloat(snapshot.fallDistance)
                output.writeInt(snapshot.remainingAir)
                output.writeInt(snapshot.maximumAir)
                output.writeInt(snapshot.noDamageTicks)
                output.writeInt(snapshot.freezeTicks)
                output.writeBoolean(snapshot.gliding)
                output.writeBoolean(snapshot.swimming)
                output.writeBoolean(snapshot.sprinting)
                output.writeInt(snapshot.potionEffects.size)
                snapshot.potionEffects.forEach { output.writePotion(it, 0) }
            }
            bytes.toByteArray()
        }
        require(payload.size <= maxPayloadBytes) { "Encoded player-state payload exceeds its configured size limit" }
        return PaperPlayerStateEnvelope(
            payloadBase64 = Base64.getEncoder().encodeToString(payload),
            sha256 = sha256(payload),
        )
    }

    fun decode(envelope: PaperPlayerStateEnvelope): PaperPlayerStateSnapshot {
        require(envelope.formatVersion == PaperPlayerStateSnapshot.CURRENT_FORMAT_VERSION) {
            "Unsupported player-state envelope format"
        }
        require(envelope.payloadBase64.length in 1..maxBase64Characters()) { "Player-state Base64 payload has an invalid size" }
        require(envelope.sha256.matches(SHA256_PATTERN)) { "Player-state checksum has an invalid format" }
        val payload = try {
            Base64.getDecoder().decode(envelope.payloadBase64)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Player-state payload is not canonical Base64", failure)
        }
        require(Base64.getEncoder().encodeToString(payload) == envelope.payloadBase64) { "Player-state payload is not canonical Base64" }
        require(payload.size in 1..maxPayloadBytes) { "Player-state payload has an invalid size" }
        require(
            MessageDigest.isEqual(
                sha256(payload).toByteArray(StandardCharsets.US_ASCII),
                envelope.sha256.toByteArray(StandardCharsets.US_ASCII),
            ),
        ) {
            "Player-state checksum mismatch"
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid player-state payload magic" }
            require(input.readInt() == PaperPlayerStateSnapshot.CURRENT_FORMAT_VERSION) {
                "Unsupported player-state payload format"
            }
            val snapshot = PaperPlayerStateSnapshot(
                playerId = input.readUuid(),
                capturedAtMillis = input.readLong(),
                location = input.readLocation(),
                compassTarget = input.readLocation(),
                storage = input.readItemList(PaperPlayerStateSnapshot.STORAGE_SLOT_COUNT),
                armor = input.readItemList(PaperPlayerStateSnapshot.ARMOR_SLOT_COUNT),
                offHand = input.readNullableItem(),
                cursor = input.readNullableItem(),
                selectedSlot = input.readInt(),
                health = input.readDouble(),
                absorption = input.readDouble(),
                foodLevel = input.readInt(),
                saturation = input.readFloat(),
                exhaustion = input.readFloat(),
                level = input.readInt(),
                experienceProgress = input.readFloat(),
                totalExperience = input.readInt(),
                gameMode = runCatching { GameMode.valueOf(input.readBoundedString(32)) }
                    .getOrElse { throw IllegalArgumentException("Player-state game mode is invalid", it) },
                allowFlight = input.readBoolean(),
                flying = input.readBoolean(),
                flySpeed = input.readFloat(),
                walkSpeed = input.readFloat(),
                velocity = PaperVelocitySnapshot(input.readDouble(), input.readDouble(), input.readDouble()),
                fireTicks = input.readInt(),
                fallDistance = input.readFloat(),
                remainingAir = input.readInt(),
                maximumAir = input.readInt(),
                noDamageTicks = input.readInt(),
                freezeTicks = input.readInt(),
                gliding = input.readBoolean(),
                swimming = input.readBoolean(),
                sprinting = input.readBoolean(),
                potionEffects = List(input.readBoundedCount(PaperPlayerStateSnapshot.MAX_POTION_EFFECTS, "potion effects")) {
                    input.readPotion(0)
                },
            ).validated()
            require(input.read() == -1) { "Trailing bytes in player-state payload" }
            snapshot
        }
    }

    private fun DataOutputStream.writeLocation(location: PaperLocationSnapshot) {
        location.validated()
        writeUuid(location.worldId)
        writeBoundedString(location.worldName, 128)
        writeDouble(location.x)
        writeDouble(location.y)
        writeDouble(location.z)
        writeFloat(location.yaw)
        writeFloat(location.pitch)
    }

    private fun DataInputStream.readLocation(): PaperLocationSnapshot = PaperLocationSnapshot(
        worldId = readUuid(),
        worldName = readBoundedString(128),
        x = readDouble(),
        y = readDouble(),
        z = readDouble(),
        yaw = readFloat(),
        pitch = readFloat(),
    ).validated()

    private fun DataOutputStream.writeItemList(items: List<ItemStack?>) = writeBlob(itemCodec.encodeItems(items))

    private fun DataInputStream.readItemList(expectedSize: Int): List<ItemStack?> =
        itemCodec.decodeItems(readBlob()).also { items ->
            require(items.size == expectedSize) { "Player-state item container has an invalid slot count" }
        }

    private fun DataOutputStream.writeNullableItem(item: ItemStack?) {
        val normalized = item?.takeUnless(ItemStack::isEmpty)
        if (normalized == null) writeInt(-1) else writeBlob(itemCodec.encodeItem(normalized))
    }

    private fun DataInputStream.readNullableItem(): ItemStack? {
        val length = readInt()
        if (length == -1) return null
        require(length in 1..maxItemBlobBytes) { "Player-state item payload has an invalid size" }
        return itemCodec.decodeItem(ByteArray(length).also(::readFully)).takeUnless(ItemStack::isEmpty)
    }

    private fun DataOutputStream.writeBlob(payload: ByteArray) {
        require(payload.size in 1..maxItemBlobBytes) { "Player-state item payload has an invalid size" }
        writeInt(payload.size)
        write(payload)
    }

    private fun DataInputStream.readBlob(): ByteArray {
        val length = readInt()
        require(length in 1..maxItemBlobBytes) { "Player-state item payload has an invalid size" }
        return ByteArray(length).also(::readFully)
    }

    private fun DataOutputStream.writePotion(effect: PaperPotionEffectSnapshot, depth: Int) {
        effect.validated(depth)
        writeBoundedString(effect.typeKey, 128)
        writeInt(effect.durationTicks)
        writeInt(effect.amplifier)
        writeBoolean(effect.ambient)
        writeBoolean(effect.particles)
        writeBoolean(effect.icon)
        writeBoolean(effect.hidden != null)
        effect.hidden?.let { writePotion(it, depth + 1) }
    }

    private fun DataInputStream.readPotion(depth: Int): PaperPotionEffectSnapshot {
        require(depth <= PaperPotionEffectSnapshot.MAX_HIDDEN_DEPTH) { "Player-state potion chain is too deep" }
        return PaperPotionEffectSnapshot(
            typeKey = readBoundedString(128),
            durationTicks = readInt(),
            amplifier = readInt(),
            ambient = readBoolean(),
            particles = readBoolean(),
            icon = readBoolean(),
            hidden = if (readBoolean()) readPotion(depth + 1) else null,
        ).validated(depth)
    }

    private fun DataOutputStream.writeUuid(value: UUID) {
        writeLong(value.mostSignificantBits)
        writeLong(value.leastSignificantBits)
    }

    private fun DataInputStream.readUuid(): UUID = UUID(readLong(), readLong())

    private fun DataOutputStream.writeBoundedString(value: String, maxCharacters: Int) {
        require(value.length in 1..maxCharacters && value.none(Char::isISOControl)) { "Player-state string is unsafe" }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maxCharacters * 4) { "Player-state string is too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedString(maxCharacters: Int): String {
        val length = readInt()
        require(length in 1..(maxCharacters * 4)) { "Player-state string byte length is invalid" }
        val bytes = ByteArray(length).also(::readFully)
        val value = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException("Player-state string is not valid UTF-8", failure)
        }
        require(value.length in 1..maxCharacters && value.none(Char::isISOControl)) { "Player-state string is unsafe" }
        return value
    }

    private fun DataInputStream.readBoundedCount(maximum: Int, label: String): Int =
        readInt().also { require(it in 0..maximum) { "Player-state $label count is invalid" } }

    private fun maxBase64Characters(): Int = ((maxPayloadBytes + 2) / 3) * 4

    private fun sha256(payload: ByteArray): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(payload),
    )

    companion object {
        private const val MAGIC = 0x41525053
        const val DEFAULT_MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
        const val DEFAULT_MAX_ITEM_BLOB_BYTES = 4 * 1024 * 1024
        private const val MAX_ALLOWED_PAYLOAD_BYTES = 128 * 1024 * 1024
        private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
    }
}
