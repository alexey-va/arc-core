package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.spyk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
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

    "retires replaced sessions without closing the physical window" {
        val plugin = paper.createSimplePlugin("MenuTransition")
        val player = spyk(paper.addPlayer("Viewer"))
        val scheduler = BukkitTaskScheduler(plugin)
        val service = PaperMenuService(plugin, repository(), scheduler)
        val handlers = mutableListOf<String>()

        fun screen(label: String, material: Material) = PaperMenuContent(
            title = Component.text(label),
            elements = mapOf(
                BUTTON to PaperMenuEntry(
                    ItemStack.of(material),
                    onClick = PaperMenuClickHandler { handlers += label },
                ),
                DECORATION to PaperMenuEntry(ItemStack.of(Material.GRAY_STAINED_GLASS_PANE), enabled = false),
            ),
            regions = mapOf(CONTENT to emptyList()),
        )

        val first = service.open(player, MENU) { screen("first", Material.STONE) }
        player.openInventory.title shouldBe "first"
        val firstView = player.openInventory

        // OPEN_NEW means the platform is already transitioning away from this view.
        // Calling session.close() here would re-enter the platform close path.
        clearMocks(player, recordedCalls = true)
        paper.callEvent(InventoryCloseEvent(firstView, InventoryCloseEvent.Reason.OPEN_NEW))
        first.isOpen shouldBe false
        service.session(player.uniqueId) shouldBe null
        verify(exactly = 0) { player.closeInventory() }

        clearMocks(player, recordedCalls = true)
        val second = service.open(player, MENU) { screen("second", Material.DIAMOND) }
        player.openInventory.title shouldBe "second"
        player.openInventory.topInventory.getItem(4)?.type shouldBe Material.DIAMOND
        verify(exactly = 1) { player.closeInventory() }

        second.showFeedback(BUTTON, delayTicks = 1, ItemStack.of(Material.EMERALD))
        clearMocks(player, recordedCalls = true)
        val third = service.open(player, MENU) { screen("third", Material.GOLD_INGOT) }
        second.isOpen shouldBe false
        third.isOpen shouldBe true
        player.openInventory.title shouldBe "third"
        player.openInventory.topInventory.getItem(4)?.type shouldBe Material.GOLD_INGOT
        // MockBukkit's openInventory contributes one close; a retired session must not add another.
        verify(exactly = 1) { player.closeInventory() }

        paper.performTicks(2)
        player.openInventory.topInventory.getItem(4)?.type shouldBe Material.GOLD_INGOT

        paper.callEvent(
            InventoryClickEvent(
                player.openInventory,
                InventoryType.SlotType.CONTAINER,
                4,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL,
            ),
        )
        handlers shouldBe listOf("third")

        clearMocks(player, recordedCalls = true)
        third.close()
        verify(exactly = 1) { player.closeInventory() }

        val reloaded = service.open(player, MENU) { screen("reloaded", Material.IRON_INGOT) }
        clearMocks(player, recordedCalls = true)
        service.closeSessions()
        reloaded.isOpen shouldBe false
        verify(exactly = 1) { player.closeInventory() }
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

    "runtime replaces layout and templates together and closes old viewers" {
        val plugin = paper.createSimplePlugin("MenuRuntime")
        val player = paper.addPlayer("Viewer")
        val first = PaperMenuConfiguration(
            repository().current(),
            mapOf("button" to PaperMenuItemTemplate(PaperMenuItemSource.MaterialItem(Material.STONE))),
        )
        val runtime = PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), first)
        val old = runtime.open(player, MENU) { content(Material.STONE) }
        val second = first.copy(
            catalog = first.catalog.copy(generation = 0),
            templates = mapOf("button" to PaperMenuItemTemplate(PaperMenuItemSource.MaterialItem(Material.DIAMOND))),
        )

        runtime.replace(second)

        old.isOpen shouldBe false
        runtime.current().templates.getValue("button").source shouldBe
            PaperMenuItemSource.MaterialItem(Material.DIAMOND)
        runtime.open(player, MENU) { content(Material.DIAMOND) }.isOpen shouldBe true
        runtime.close()
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
