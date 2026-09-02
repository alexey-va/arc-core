package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.menu.MenuCatalog
import ru.arc.menu.MenuCatalogRepository
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuElementLayout
import ru.arc.menu.MenuId
import ru.arc.menu.MenuLayout
import ru.arc.menu.MenuRegionId
import ru.arc.menu.MenuRegionLayout
import ru.arc.menu.MenuSlot
import ru.arc.menu.validated
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperMenuSessionTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "renders background fixed elements and ordered region entries" {
        val plugin = paper.createSimplePlugin("MenuSession")
        val player = paper.addPlayer("Viewer")
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))

        val session = service.open(player, MENU) {
            PaperMenuContent(
                title = Component.text("Меню"),
                background = ItemStack.of(Material.BLACK_STAINED_GLASS_PANE),
                elements = mapOf(BUTTON to PaperMenuEntry(ItemStack.of(Material.DIAMOND))),
                regions = mapOf(
                    CONTENT to listOf(
                        PaperMenuEntry(ItemStack.of(Material.APPLE)),
                        PaperMenuEntry(ItemStack.of(Material.BREAD)),
                    ),
                ),
            )
        }

        session.inventory.getItem(0)!!.type shouldBe Material.BLACK_STAINED_GLASS_PANE
        session.inventory.getItem(4)!!.type shouldBe Material.DIAMOND
        session.inventory.getItem(10)!!.type shouldBe Material.APPLE
        session.inventory.getItem(11)!!.type shouldBe Material.BREAD
        session.inventory.getItem(12)!!.type shouldBe Material.BLACK_STAINED_GLASS_PANE
        service.session(player.uniqueId) shouldBe session
        service.close()
    }

    "rejects region overflow before changing the visible inventory" {
        val plugin = paper.createSimplePlugin("MenuOverflow")
        val player = paper.addPlayer("Viewer")
        var entries = listOf(PaperMenuEntry(ItemStack.of(Material.APPLE)))
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        val session = service.open(player, MENU) {
            PaperMenuContent(
                Component.text("Меню"),
                elements = mapOf(BUTTON to PaperMenuEntry(ItemStack.of(Material.DIAMOND))),
                regions = mapOf(CONTENT to entries),
            )
        }
        val before = session.inventory.contents.map { it?.clone() }
        entries = List(4) { PaperMenuEntry(ItemStack.of(Material.GOLD_INGOT)) }

        (runCatching { session.refresh() }.exceptionOrNull() is PaperMenuContentException) shouldBe true
        session.inventory.contents.toList() shouldBe before
        service.close()
    }

    "replacement and explicit close are idempotent" {
        val plugin = paper.createSimplePlugin("MenuClose")
        val player = paper.addPlayer("Viewer")
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        val first = service.open(player, MENU) { content(Material.STONE) }
        val second = service.open(player, MENU) { content(Material.DIAMOND) }

        first.isOpen shouldBe false
        second.isOpen shouldBe true
        first.close() shouldBe PaperMenuSessionResult.CLOSED
        second.close() shouldBe PaperMenuSessionResult.CLOSED
        second.close() shouldBe PaperMenuSessionResult.CLOSED
        service.session(player.uniqueId) shouldBe null
        service.close()
    }

    "closing active sessions keeps the service reusable" {
        val plugin = paper.createSimplePlugin("MenuReload")
        val firstPlayer = paper.addPlayer("FirstViewer")
        val secondPlayer = paper.addPlayer("SecondViewer")
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        val first = service.open(firstPlayer, MENU) { content(Material.STONE) }
        val second = service.open(secondPlayer, MENU) { content(Material.DIAMOND) }

        service.closeSessions()

        first.isOpen shouldBe false
        second.isOpen shouldBe false
        service.session(firstPlayer.uniqueId) shouldBe null
        service.session(secondPlayer.uniqueId) shouldBe null
        service.open(firstPlayer, MENU) { content(Material.EMERALD) }.isOpen shouldBe true
        service.close()
    }
})

internal val MENU = MenuId.of("main")
internal val BUTTON = MenuElementId.of("button")
internal val DECORATION = MenuElementId.of("decoration")
internal val CONTENT = MenuRegionId.of("content")

internal fun repository(paginated: Boolean = false): MenuCatalogRepository {
    val elements = linkedMapOf(
        BUTTON to MenuElementLayout.button(BUTTON, MenuSlot.of(4)),
        DECORATION to MenuElementLayout.decoration(DECORATION, listOf(MenuSlot.of(8))),
    )
    val region = MenuRegionLayout(CONTENT, listOf(MenuSlot.of(10), MenuSlot.of(11), MenuSlot.of(12)))
    val layout = MenuLayout(
        id = MENU,
        rows = 2,
        elements = elements,
        regions = mapOf(CONTENT to region),
        pagination = if (paginated) ru.arc.menu.MenuPaginationLayout(CONTENT, BUTTON, DECORATION) else null,
    ).validated(
        MenuContract(
            requiredElements = elements.keys,
            requiredRegions = setOf(CONTENT),
        ),
    )
    return MenuCatalogRepository(MenuCatalog(layouts = mapOf(MENU to layout)))
}

internal fun content(material: Material, handler: PaperMenuClickHandler = PaperMenuClickHandler {}): PaperMenuContent =
    PaperMenuContent(
        title = Component.text("Меню"),
        elements = mapOf(
            BUTTON to PaperMenuEntry(ItemStack.of(material), onClick = handler),
            DECORATION to PaperMenuEntry(ItemStack.of(Material.GRAY_STAINED_GLASS_PANE), enabled = false),
        ),
        regions = mapOf(CONTENT to emptyList()),
    )
