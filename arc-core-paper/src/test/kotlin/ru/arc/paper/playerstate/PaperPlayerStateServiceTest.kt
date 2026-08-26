package ru.arc.paper.playerstate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicInteger

class PaperPlayerStateServiceTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "captures, restores, verifies and persists complete supported player state" {
        val server = paper.server
        val world = server.addSimpleWorld("state-service")
        val player = server.addPlayer("Stateful")
        player.teleport(Location(world, 2.0, 70.0, -4.0, 45f, 5f))
        player.compassTarget = Location(world, 20.0, 80.0, 20.0)
        player.inventory.setItem(0, ItemStack.of(Material.DIAMOND, 3))
        player.inventory.helmet = ItemStack.of(Material.IRON_HELMET)
        player.inventory.setItemInOffHand(ItemStack.of(Material.TORCH, 8))
        player.setItemOnCursor(ItemStack.of(Material.APPLE))
        player.inventory.heldItemSlot = 4
        player.health = 17.0
        player.absorptionAmount = 2.0
        player.foodLevel = 12
        player.saturation = 3.0f
        player.exhaustion = 1.0f
        player.level = 5
        player.exp = 0.25f
        player.totalExperience = 100
        player.gameMode = GameMode.ADVENTURE
        player.allowFlight = true
        player.isFlying = true
        player.flySpeed = 0.2f
        player.walkSpeed = 0.3f
        player.fireTicks = 5
        player.fallDistance = 1.5f
        player.maximumAir = 300
        player.remainingAir = 200
        player.noDamageTicks = 3
        player.freezeTicks = 2
        player.isSprinting = true

        val persisted = AtomicInteger()
        val codec = PaperPlayerStateCodec(SimpleItemCodec)
        val service = PaperPlayerStateService(codec, primaryThread = { true }, persistPlayerData = { persisted.incrementAndGet() })
        val envelope = service.captureEnvelope(player, 1_787_730_000_000)
        val expected = codec.decode(envelope)

        player.inventory.clear()
        player.inventory.helmet = null
        player.inventory.setItemInOffHand(ItemStack.empty())
        player.setItemOnCursor(ItemStack.empty())
        player.inventory.heldItemSlot = 0
        player.teleport(Location(world, 100.0, 100.0, 100.0))
        player.health = 5.0
        player.absorptionAmount = 0.0
        player.foodLevel = 20
        player.gameMode = GameMode.SURVIVAL
        player.allowFlight = false
        player.isSprinting = false

        val receipt = service.restoreAndVerify(player, envelope)
        receipt.playerId shouldBe player.uniqueId
        receipt.envelopeSha256 shouldBe envelope.sha256
        persisted.get() shouldBe 1
        service.mismatches(player, expected) shouldBe emptyList()
    }

    "wrong identity and off-thread access fail before persistence" {
        val server = paper.server
        val first = server.addPlayer("FirstState")
        val second = server.addPlayer("SecondState")
        val persisted = AtomicInteger()
        val service = PaperPlayerStateService(
            PaperPlayerStateCodec(SimpleItemCodec),
            primaryThread = { true },
            persistPlayerData = { persisted.incrementAndGet() },
        )
        val snapshot = service.capture(first, 1_787_730_000_000)
        shouldThrow<IllegalArgumentException> { service.restoreAndVerify(second, snapshot) }
        persisted.get() shouldBe 0

        val offThread = PaperPlayerStateService(primaryThread = { false })
        shouldThrow<IllegalStateException> { offThread.capture(first, 1_787_730_000_000) }
    }

    "failed teleport never reports restoration or persists playerdata" {
        val server = paper.server
        val player = server.addPlayer("TeleportFailure")
        val persisted = AtomicInteger()
        val service = PaperPlayerStateService(
            PaperPlayerStateCodec(SimpleItemCodec),
            primaryThread = { true },
            persistPlayerData = { persisted.incrementAndGet() },
        )
        val snapshot = service.capture(player, 1_787_730_000_000)
        shouldThrow<IllegalStateException> { service.restoreAndVerify(player, snapshot) { _, _ -> false } }
        persisted.get() shouldBe 0
    }

    "unrestorable health fails instead of silently acknowledging a lossy restore" {
        val server = paper.server
        val player = server.addPlayer("HealthMismatch")
        val persisted = AtomicInteger()
        val service = PaperPlayerStateService(
            PaperPlayerStateCodec(SimpleItemCodec),
            primaryThread = { true },
            persistPlayerData = { persisted.incrementAndGet() },
        )
        val snapshot = service.capture(player, 1_787_730_000_000).copy(health = 30.0)
        shouldThrow<IllegalArgumentException> { service.restoreAndVerify(player, snapshot) }
        persisted.get() shouldBe 0
    }

    "mismatch diagnostics expose bounded field names rather than item contents" {
        val server = paper.server
        val player = server.addPlayer("MismatchState")
        val service = PaperPlayerStateService(
            PaperPlayerStateCodec(SimpleItemCodec),
            primaryThread = { true },
            persistPlayerData = {},
        )
        val snapshot = service.capture(player, 1_787_730_000_000)
        player.inventory.setItem(0, ItemStack.of(Material.NETHERITE_SWORD))
        service.mismatches(player, snapshot).shouldContainExactly("storage")
    }
}) {
    companion object {
        private object SimpleItemCodec : PaperItemStackBinaryCodec {
            override fun encodeItems(items: List<ItemStack?>): ByteArray = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(items.size)
                    items.forEach { item ->
                        val normalized = item?.takeUnless(ItemStack::isEmpty)
                        output.writeBoolean(normalized != null)
                        if (normalized != null) {
                            output.writeUTF(normalized.type.name)
                            output.writeInt(normalized.amount)
                        }
                    }
                }
                bytes.toByteArray()
            }

            override fun decodeItems(payload: ByteArray): List<ItemStack?> = DataInputStream(ByteArrayInputStream(payload)).use { input ->
                val count = input.readInt().also { require(it in 0..64) }
                List(count) {
                    if (!input.readBoolean()) null
                    else ItemStack.of(Material.valueOf(input.readUTF()), input.readInt())
                }.also { require(input.read() == -1) }
            }

            override fun encodeItem(item: ItemStack): ByteArray = encodeItems(listOf(item))

            override fun decodeItem(payload: ByteArray): ItemStack = requireNotNull(decodeItems(payload).single())
        }
    }
}
