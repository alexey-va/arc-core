package ru.arc.paper.playerstate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class PaperPlayerStateCodecTest : FreeSpec({
    beforeSpec { MockBukkit.mock() }
    afterSpec { MockBukkit.unmock() }

    "native Paper item bytes preserve type, amount and component metadata" {
        val item = ItemStack.of(Material.DIAMOND_SWORD).apply {
            editMeta { it.displayName(Component.text("Stateful blade")) }
        }
        val decoded = NativePaperItemStackBinaryCodec.decodeItem(NativePaperItemStackBinaryCodec.encodeItem(item))
        decoded shouldBe item

        val items = listOf<ItemStack?>(item, null, ItemStack.of(Material.GOLDEN_APPLE, 3))
        NativePaperItemStackBinaryCodec.decodeItems(NativePaperItemStackBinaryCodec.encodeItems(items)) shouldBe items
    }

    "round-trips every player-state field through a checksummed envelope" {
        val codec = PaperPlayerStateCodec()
        val snapshot = snapshot()
        val envelope = codec.encode(snapshot)
        envelope.formatVersion shouldBe 1
        envelope.sha256.length shouldBe 64
        codec.decode(envelope) shouldBe snapshot
    }

    "rejects checksum mismatch, malformed Base64 and unsupported envelope versions" {
        val codec = PaperPlayerStateCodec()
        val envelope = codec.encode(snapshot())
        shouldThrow<IllegalArgumentException> { codec.decode(envelope.copy(sha256 = "0".repeat(64))) }
        shouldThrow<IllegalArgumentException> { codec.decode(envelope.copy(payloadBase64 = "%%%")) }
        shouldThrow<IllegalArgumentException> { codec.decode(envelope.copy(formatVersion = 2)) }
    }

    "rejects authenticated trailing bytes instead of accepting a prefix" {
        val codec = PaperPlayerStateCodec()
        val envelope = codec.encode(snapshot())
        val changed = Base64.getDecoder().decode(envelope.payloadBase64) + byteArrayOf(1)
        val authenticated = envelope.copy(
            payloadBase64 = Base64.getEncoder().encodeToString(changed),
            sha256 = sha256(changed),
        )
        shouldThrow<IllegalArgumentException> { codec.decode(authenticated) }
    }

    "rejects authenticated malformed UTF-8 instead of replacing bytes" {
        val codec = PaperPlayerStateCodec()
        val envelope = codec.encode(snapshot())
        val changed = Base64.getDecoder().decode(envelope.payloadBase64)
        changed[52] = 0xc3.toByte()
        val authenticated = envelope.copy(
            payloadBase64 = Base64.getEncoder().encodeToString(changed),
            sha256 = sha256(changed),
        )
        shouldThrow<IllegalArgumentException> { codec.decode(authenticated) }
    }

    "validates field, slot, item and nested-potion bounds before persistence" {
        val base = snapshot()
        shouldThrow<IllegalArgumentException> { PaperPlayerStateCodec().encode(base.copy(storage = base.storage.dropLast(1))) }
        shouldThrow<IllegalArgumentException> { PaperPlayerStateCodec().encode(base.copy(health = Double.NaN)) }
        shouldThrow<IllegalArgumentException> { PaperPlayerStateCodec().encode(base.copy(allowFlight = false, flying = true)) }
        shouldThrow<IllegalArgumentException> {
            PaperPlayerStateCodec(maxItemBlobBytes = 16).encode(base)
        }
        var hidden: PaperPotionEffectSnapshot? = null
        repeat(10) {
            hidden = PaperPotionEffectSnapshot("minecraft:speed", 20, 0, false, true, true, hidden)
        }
        val tooDeep = requireNotNull(hidden)
        shouldThrow<IllegalArgumentException> { PaperPlayerStateCodec().encode(base.copy(potionEffects = listOf(tooDeep))) }
    }
}) {
    companion object {
        private fun snapshot(): PaperPlayerStateSnapshot {
            val world = UUID.randomUUID()
            return PaperPlayerStateSnapshot(
                playerId = UUID.randomUUID(),
                capturedAtMillis = 1_787_730_000_000,
                location = PaperLocationSnapshot(world, "arena", 1.25, 64.0, -3.5, 90f, -10f),
                compassTarget = PaperLocationSnapshot(world, "arena", 10.0, 70.0, 10.0, 0f, 0f),
                storage = List(PaperPlayerStateSnapshot.STORAGE_SLOT_COUNT) { index ->
                    if (index == 0) ItemStack.of(Material.DIAMOND, 2) else null
                },
                armor = List(PaperPlayerStateSnapshot.ARMOR_SLOT_COUNT) { index ->
                    if (index == 3) ItemStack.of(Material.IRON_HELMET) else null
                },
                offHand = ItemStack.of(Material.TORCH, 12),
                cursor = ItemStack.of(Material.APPLE),
                selectedSlot = 4,
                health = 17.0,
                absorption = 2.0,
                foodLevel = 14,
                saturation = 3.5f,
                exhaustion = 1.25f,
                level = 7,
                experienceProgress = 0.4f,
                totalExperience = 140,
                gameMode = GameMode.ADVENTURE,
                allowFlight = true,
                flying = true,
                flySpeed = 0.2f,
                walkSpeed = 0.25f,
                velocity = PaperVelocitySnapshot(0.1, 0.2, -0.3),
                fireTicks = 8,
                fallDistance = 1.5f,
                remainingAir = 240,
                maximumAir = 300,
                noDamageTicks = 4,
                freezeTicks = 2,
                gliding = false,
                swimming = false,
                sprinting = true,
                potionEffects = listOf(
                    PaperPotionEffectSnapshot(
                        typeKey = "minecraft:speed",
                        durationTicks = org.bukkit.potion.PotionEffect.INFINITE_DURATION,
                        amplifier = 1,
                        ambient = false,
                        particles = true,
                        icon = true,
                        hidden = PaperPotionEffectSnapshot("minecraft:slowness", 40, 0, true, false, false),
                    ),
                ),
            ).validated()
        }

        private fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
    }
}
