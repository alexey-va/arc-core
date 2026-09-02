package ru.arc.paper.menu

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuElementKind
import ru.arc.menu.MenuElementLayout
import ru.arc.menu.MenuId
import ru.arc.menu.MenuLayout
import ru.arc.menu.MenuRegionId
import ru.arc.menu.MenuRegionLayout
import ru.arc.menu.MenuSlot
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperMenuFrameTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    val button = MenuElementId.of("button")
    val content = MenuRegionId.of("content")
    val layout = MenuLayout(
        id = MenuId.of("test"),
        rows = 3,
        elements = mapOf(
            button to MenuElementLayout(button, listOf(MenuSlot.of(4)), MenuElementKind.BUTTON),
        ),
        regions = mapOf(
            content to MenuRegionLayout(content, listOf(MenuSlot.of(10), MenuSlot.of(12))),
        ),
    )

    "physical frame projects configured slots and preserves their click identity" {
        val frame = PaperMenuFrame.physical(layout, Component.text("Test"), ItemStack.of(Material.GRAY_STAINED_GLASS_PANE))
        frame.setItem(4, ItemStack.of(Material.EMERALD))
        frame.setItem(12, ItemStack.of(Material.DIAMOND))
        val rendered = frame.content { _, _ -> }

        rendered.elements.getValue(button).item.type shouldBe Material.EMERALD
        rendered.regions.getValue(content).map { it.item.type } shouldBe
            listOf(Material.GRAY_STAINED_GLASS_PANE, Material.DIAMOND)
        rendered.elements.getValue(button).enabled shouldBe true
        rendered.regions.getValue(content).map(PaperMenuEntry::enabled) shouldBe listOf(false, true)
    }

    "logical region frame maps logical indexes without exposing physical layout" {
        val frame = PaperMenuFrame.region(layout, content, Component.text("Grid"), ItemStack.of(Material.BLACK_STAINED_GLASS_PANE))
        frame.setItem(0, ItemStack.of(Material.APPLE))
        frame.setItem(1, ItemStack.of(Material.BREAD))
        val rendered = frame.content { _, _ -> }

        rendered.elements shouldBe emptyMap()
        rendered.regions.getValue(content).map { it.item.type } shouldBe listOf(Material.APPLE, Material.BREAD)
        rendered.regions.getValue(content).map(PaperMenuEntry::enabled) shouldBe listOf(true, true)
    }

    "frame rejects slots outside its declared address space" {
        val physical = PaperMenuFrame.physical(layout, Component.text("Test"), null)
        shouldThrow<IllegalArgumentException> { physical.setItem(11, ItemStack.of(Material.STONE)) }

        val logical = PaperMenuFrame.region(layout, content, Component.text("Grid"), null)
        shouldThrow<IllegalArgumentException> { logical.setItem(2, ItemStack.of(Material.STONE)) }
    }

    "sparse content without a background is rejected instead of shifting items" {
        val frame = PaperMenuFrame.physical(layout, Component.text("Test"), null)
        frame.setItem(12, ItemStack.of(Material.DIAMOND))

        shouldThrow<PaperMenuContentException> { frame.content { _, _ -> } }
    }
})
