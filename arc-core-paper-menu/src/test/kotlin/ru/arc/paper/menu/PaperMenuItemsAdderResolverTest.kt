package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperMenuItemsAdderResolverTest : FreeSpec({
    "foreign namespaces and unavailable providers never call the optional API" {
        val foreign = PaperMenuItemsAdderResolver({ error("foreign availability") }, { error("foreign lookup") })
        foreign.resolve(NamespacedKey("other", "pack/item")) shouldBe PaperMenuExternalItemResult.Missing
        val absent = PaperMenuItemsAdderResolver({ false }, { error("absent API") })
        absent.resolve(NamespacedKey("itemsadder", "pack/item")) shouldBe PaperMenuExternalItemResult.Missing
    }

    "converts the first separator and preserves legacy bare ids" {
        val lookedUp = mutableListOf<String>()
        val resolver = PaperMenuItemsAdderResolver({ true }) { lookedUp += it; null }
        listOf("pack/item", "legacy", "pack/folder/item").forEach {
            resolver.resolve(NamespacedKey("itemsadder", it)) shouldBe PaperMenuExternalItemResult.Missing
        }
        lookedUp shouldBe listOf("pack:item", "legacy", "pack:folder/item")
    }

    "availability is live and an item is detached by the factory before decoration" {
        MockBukkitTestRuntime.open().use {
            var available = true
            var calls = 0
            val original = ItemStack.of(Material.EMERALD, 8)
            val resolver = PaperMenuItemsAdderResolver({ available }) { calls++; original }
            val id = NamespacedKey("itemsadder", "pack/item")
            val template = PaperMenuItemTemplate(PaperMenuItemSource.ExternalItem(id), amount = 2)
            val rendered = PaperMenuItemFactory(resolver).create(template, Component.text("display"), emptyList())
            rendered.type shouldBe Material.EMERALD
            rendered.amount shouldBe 2
            (rendered === original) shouldBe false
            original.amount shouldBe 8
            available = false
            resolver.resolve(id) shouldBe PaperMenuExternalItemResult.Missing
            calls shouldBe 1
            available = true
            resolver.resolve(id) shouldBe PaperMenuExternalItemResult.Resolved(original)
            calls shouldBe 2
        }
    }

    "provider exceptions use the existing factory fallback and diagnostic" {
        MockBukkitTestRuntime.open().use {
            val diagnostics = mutableListOf<String>()
            val resolver = PaperMenuItemsAdderResolver({ true }) { error("provider failure") }
            val template = PaperMenuItemTemplate(
                PaperMenuItemSource.ExternalItem(NamespacedKey("itemsadder", "pack/item")),
                fallbackMaterial = Material.BARRIER,
            )
            val item = PaperMenuItemFactory(resolver, diagnostics::add)
                .create(template, Component.empty(), emptyList())
            item.type shouldBe Material.BARRIER
            diagnostics shouldBe listOf("external-resolver-failed")
        }
    }
})
