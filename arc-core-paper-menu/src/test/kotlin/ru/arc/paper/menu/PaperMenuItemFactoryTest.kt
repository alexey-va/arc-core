@file:Suppress("DEPRECATION")

package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperMenuItemFactoryTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "factory applies the final presentation and non-italic roots" {
        val template = PaperMenuItemTemplate(
            source = PaperMenuItemSource.MaterialItem(Material.DIAMOND),
            amount = 3,
            customModelData = 7,
            glint = true,
            hideTooltip = true,
            itemFlags = setOf(ItemFlag.HIDE_ATTRIBUTES),
        )

        val item = PaperMenuItemFactory().create(
            template,
            Component.text("Покупка").decorate(TextDecoration.BOLD),
            listOf(Component.empty(), Component.text("Цена")),
        )

        item.type shouldBe Material.DIAMOND
        item.amount shouldBe 3
        item.itemMeta.customModelData shouldBe 7
        item.itemMeta.enchantmentGlintOverride shouldBe true
        item.itemMeta.isHideTooltip shouldBe true
        item.itemMeta.itemFlags shouldBe setOf(ItemFlag.HIDE_ATTRIBUTES)
        item.itemMeta.displayName()!!.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        item.itemMeta.lore()!!.map { it.decoration(TextDecoration.ITALIC) }
            .shouldContainExactly(TextDecoration.State.FALSE, TextDecoration.State.FALSE)
    }

    "external results are cloned and consumer presentation wins" {
        val original = ItemStack.of(Material.EMERALD, 12).apply {
            editMeta { it.displayName(Component.text("provider")) }
        }
        val resolver = PaperMenuExternalItemResolver { PaperMenuExternalItemResult.Resolved(original) }
        val template = PaperMenuItemTemplate(
            source = PaperMenuItemSource.ExternalItem(NamespacedKey("itemsadder", "coin")),
            amount = 2,
        )

        val first = PaperMenuItemFactory(resolver).create(template, Component.text("Монета"), emptyList())
        val second = PaperMenuItemFactory(resolver).create(template, Component.text("Монета"), emptyList())

        first shouldBe second
        (first === second) shouldBe false
        (first === original) shouldBe false
        first.amount shouldBe 2
        first.itemMeta.displayName() shouldBe Component.text("Монета").decoration(TextDecoration.ITALIC, false)
        original.amount shouldBe 12
        original.itemMeta.displayName() shouldBe Component.text("provider")
    }

    "missing unavailable and failed external sources use a bounded fallback diagnostic" {
        val diagnostics = mutableListOf<String>()
        val template = PaperMenuItemTemplate(
            source = PaperMenuItemSource.ExternalItem(NamespacedKey("itemsadder", "missing")),
            fallbackMaterial = Material.RED_STAINED_GLASS_PANE,
        )

        val noResolver = PaperMenuItemFactory(diagnostics = diagnostics::add)
            .create(template, Component.empty(), listOf(Component.empty()))
        val failed = PaperMenuItemFactory(
            externalItems = PaperMenuExternalItemResolver {
                PaperMenuExternalItemResult.Failed("provider-timeout")
            },
            diagnostics = diagnostics::add,
        ).create(template, Component.empty(), emptyList())

        noResolver.type shouldBe Material.RED_STAINED_GLASS_PANE
        failed.type shouldBe Material.RED_STAINED_GLASS_PANE
        diagnostics.shouldContainExactly("external-resolver-unavailable", "provider-timeout")
        diagnostics.all { it.length <= PaperMenuItemFactory.MAX_DIAGNOSTIC_LENGTH } shouldBe true
        noResolver.itemMeta.displayName()!!.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        noResolver.itemMeta.lore()!!.single().decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
    }

    "template constructors reject unsafe direct values" {
        runCatching { PaperMenuItemTemplate(PaperMenuItemSource.MaterialItem(Material.AIR)) }.isFailure shouldBe true
        runCatching { PaperMenuItemTemplate(PaperMenuItemSource.MaterialItem(Material.STONE), amount = 0) }.isFailure shouldBe true
        runCatching { PaperMenuItemTemplate(PaperMenuItemSource.MaterialItem(Material.STONE), amount = 100) }.isFailure shouldBe true
        runCatching {
            PaperMenuItemTemplate(PaperMenuItemSource.MaterialItem(Material.STONE), customModelData = -1)
        }.isFailure shouldBe true
    }
})
