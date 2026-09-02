package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperMenuFeedbackTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "latest feedback restores the latest normal item and stale callbacks cannot win" {
        val plugin = paper.createSimplePlugin("MenuFeedback")
        val player = paper.addPlayer("Viewer")
        val scheduler = TestTaskScheduler()
        var normal = Material.DIAMOND
        val service = PaperMenuService(plugin, repository(), scheduler)
        val session = service.open(player, MENU) { content(normal) }

        session.showFeedback(BUTTON, 10, ItemStack.of(Material.LIME_DYE)) shouldBe PaperMenuSessionResult.RENDERED
        session.showFeedback(BUTTON, 20, ItemStack.of(Material.RED_DYE)) shouldBe PaperMenuSessionResult.RENDERED
        session.inventory.getItem(4)!!.type shouldBe Material.RED_DYE
        normal = Material.EMERALD
        scheduler.advanceMs(500)
        session.inventory.getItem(4)!!.type shouldBe Material.RED_DYE
        scheduler.advanceMs(500)
        session.inventory.getItem(4)!!.type shouldBe Material.EMERALD
        service.close()
    }

    "full refresh and close invalidate pending feedback restoration" {
        val plugin = paper.createSimplePlugin("MenuFeedbackClose")
        val player = paper.addPlayer("Viewer")
        val scheduler = TestTaskScheduler()
        var normal = Material.DIAMOND
        val service = PaperMenuService(plugin, repository(), scheduler)
        val session = service.open(player, MENU) { content(normal) }
        session.showFeedback(BUTTON, 10, ItemStack.of(Material.LIME_DYE))
        normal = Material.EMERALD

        session.refresh() shouldBe PaperMenuSessionResult.RENDERED
        scheduler.advanceMs(500)
        session.inventory.getItem(4)!!.type shouldBe Material.EMERALD
        session.showFeedback(BUTTON, 10, ItemStack.of(Material.RED_DYE))
        session.close()
        scheduler.advanceMs(500)
        session.isOpen shouldBe false
        service.close()
    }
})
