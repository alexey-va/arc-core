package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperMenuPaginationTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "clamps pages renders stable slices and exposes navigation state" {
        val plugin = paper.createSimplePlugin("MenuPages")
        val player = paper.addPlayer("Viewer")
        val items = listOf(Material.APPLE, Material.BREAD, Material.CARROT, Material.POTATO, Material.COAL)
            .map { PaperMenuEntry(ItemStack.of(it)) }
        val service = PaperMenuService(plugin, repository(paginated = true), BukkitTaskScheduler(plugin))
        val session = service.open(player, MENU) {
            PaperMenuContent(
                Component.text("Страница"),
                elements = mapOf(
                    BUTTON to PaperMenuEntry(ItemStack.of(Material.ARROW)),
                    DECORATION to PaperMenuEntry(ItemStack.of(Material.ARROW)),
                ),
                regions = mapOf(CONTENT to items),
            )
        }

        session.pageState()!!.pageIndex shouldBe 0
        session.inventory.getItem(10)!!.type shouldBe Material.APPLE
        session.inventory.getItem(12)!!.type shouldBe Material.CARROT
        session.nextPage() shouldBe PaperMenuSessionResult.RENDERED
        session.pageState()!!.pageIndex shouldBe 1
        session.inventory.getItem(10)!!.type shouldBe Material.POTATO
        session.inventory.getItem(11)!!.type shouldBe Material.COAL
        session.inventory.getItem(12) shouldBe null
        session.nextPage() shouldBe PaperMenuSessionResult.UNCHANGED
        session.setPage(50) shouldBe PaperMenuSessionResult.UNCHANGED
        session.previousPage() shouldBe PaperMenuSessionResult.RENDERED
        service.close()
    }
})
