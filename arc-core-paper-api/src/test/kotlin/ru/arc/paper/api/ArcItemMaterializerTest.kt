package ru.arc.paper.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime

class ArcItemMaterializerTest : FreeSpec({
    val request = ArcItemMaterializationRequest("loot", "diamond")

    "rejects blank, oversized and control-character request identifiers" {
        val invalid = listOf("", "   ", "loot\nentry", "loot\u0000entry", "x".repeat(257))

        invalid.forEach { value ->
            shouldThrow<IllegalArgumentException> {
                ArcItemMaterializationRequest(value, "entry")
            }
            shouldThrow<IllegalArgumentException> {
                ArcItemMaterializationRequest("category", value)
            }
        }

        ArcItemMaterializationRequest("x".repeat(256), "entry")
        ArcItemMaterializationRequest("category", "x".repeat(256))
    }

    "requires a catalog fingerprint when the capability is available" {
        shouldThrow<IllegalArgumentException> {
            ArcItemMaterializerCapabilitySnapshot(available = true, catalogFingerprint = null)
        }
        shouldThrow<IllegalArgumentException> {
            ArcItemMaterializerCapabilitySnapshot(available = true, catalogFingerprint = "")
        }

        ArcItemMaterializerCapabilitySnapshot(available = false, catalogFingerprint = null)
        ArcItemMaterializerCapabilitySnapshot(available = true, catalogFingerprint = "fingerprint")
    }

    "rejects malformed frozen item references" {
        MockBukkitTestRuntime.open().use {
            shouldThrow<IllegalArgumentException> {
                ArcItemMaterializationReference.FrozenItems(request, "", listOf(diamond()))
            }
            shouldThrow<IllegalArgumentException> {
                ArcItemMaterializationReference.FrozenItems(request, "fingerprint", emptyList())
            }
            shouldThrow<IllegalArgumentException> {
                ArcItemMaterializationReference.FrozenItems(
                    request,
                    "fingerprint",
                    List(65) { diamond() },
                )
            }
            shouldThrow<IllegalArgumentException> {
                ArcItemMaterializationReference.FrozenItems(
                    request,
                    "fingerprint",
                    listOf(ItemStack(Material.AIR)),
                )
            }
        }
    }

    "rejects blank, oversized and control-character voucher source keys" {
        val invalid = listOf("", "   ", "voucher\nkey", "voucher\u0000key", "x".repeat(257))

        invalid.forEach { sourceKey ->
            shouldThrow<IllegalArgumentException> {
                ArcItemMaterializationReference.FreshVoucher(request, "fingerprint", sourceKey)
            }
        }

        ArcItemMaterializationReference.FreshVoucher(request, "fingerprint", "x".repeat(256))
    }

    "snapshots input items and returns fresh clones from every accessor" {
        MockBukkitTestRuntime.open().use {
            val sourceItem = diamond()
            val sourceTemplates = mutableListOf(sourceItem)
            val reference = ArcItemMaterializationReference.FrozenItems(
                request,
                "fingerprint",
                sourceTemplates,
            )

            sourceItem.amount = 7
            sourceTemplates.clear()

            val firstRead = reference.templates.single()
            firstRead.amount shouldBe 2
            (firstRead === sourceItem) shouldBe false

            firstRead.amount = 9
            val secondRead = reference.templates.single()
            secondRead.amount shouldBe 2
            (secondRead === firstRead) shouldBe false
        }
    }
}) {
    companion object {
        private fun diamond() = ItemStack(Material.DIAMOND, 2)
    }
}
