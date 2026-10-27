package ru.arc.paper.scene

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperSceneSnapshotsTest : FreeSpec({
    "safe text keeps inherited formatting and never retains click or hover payloads" {
        val captured = PaperSceneSnapshots.textContent(Component.empty().color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
            .append(Component.text("Ключ ")).append(Component.text("×3", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, false))
            .clickEvent(ClickEvent.runCommand("/private-command")).hoverEvent(HoverEvent.showText(Component.text("private-hover"))))
        captured.text shouldBe "Ключ ×3"
        requireNotNull(captured.runs).map { Triple(it.text, it.color, it.bold) } shouldBe
            listOf(Triple("Ключ ", NamedTextColor.GOLD.value(), true), Triple("×3", NamedTextColor.GREEN.value(), false))
        captured.javaClass.declaredFields.map { it.name }.toSet() shouldBe setOf("text", "runs")
    }

    "text codepoint node and style budgets preserve a bounded literal fallback" {
        val text = PaperSceneSnapshots.textContent(Component.text("😀".repeat(300)))
        text.text.codePointCount(0, text.text.length) shouldBe 256
        var fragmented = Component.empty()
        repeat(70) { fragmented = fragmented.append(Component.text("x", TextColor.color(it))) }
        PaperSceneSnapshots.textContent(fragmented).runs shouldBe null
        var empty = Component.empty()
        repeat(600) { empty = empty.append(Component.empty().color(NamedTextColor.RED)) }
        PaperSceneSnapshots.textContent(empty.append(Component.text("outside-budget"))).text shouldBe ""
    }

    "native item capture keeps rendering identity while discarding lore and metadata" {
        MockBukkitTestRuntime.open().use {
            // MockBukkit 4.116.3 drops itemModel during setItemMeta's clone. Exercise the exact
            // typed Paper getter directly; do not weaken capture to match that incomplete clone.
            val native = mockk<ItemStack>()
            val meta = mockk<ItemMeta>()
            every { native.type } returns Material.PAPER
            every { native.amount } returns 1
            every { native.itemMeta } returns meta
            every { meta.hasCustomModelData() } returns true
            @Suppress("DEPRECATION")
            every { meta.customModelData } returns 501
            every { meta.itemModel } returns NamespacedKey("ruscrafting", "crate_reward")
            every { meta.lore() } returns listOf(Component.text("private-lore"))
            val captured = requireNotNull(PaperSceneSnapshots.item(native))
            captured.material shouldBe "minecraft:paper"
            captured.customModelData shouldBe 501
            captured.itemModel shouldBe "ruscrafting:crate_reward"
            captured.javaClass.declaredFields.map { it.name }.toSet() shouldBe setOf("material", "amount", "customModelData", "tint", "itemModel")
            verify(exactly = 0) { meta.lore() }
            PaperSceneSnapshots.item(ItemStack(Material.AIR)) shouldBe null
        }
    }
})
