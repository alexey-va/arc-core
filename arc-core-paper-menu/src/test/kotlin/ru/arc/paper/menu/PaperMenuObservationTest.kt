package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.core.BukkitTaskScheduler
import ru.arc.menu.MenuCatalog
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperMenuObservationTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "observes open and actual renders, but suppresses unchanged refresh" {
        val plugin = paper.createSimplePlugin("ObservationRender")
        val player = paper.addPlayer("Viewer")
        val observed = paper.observations(plugin)
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        val session = service.open(player, MENU) { content(Material.DIAMOND) }

        observed.map { it.getPayload()["phase"] } shouldBe listOf("render", "open")
        session.refresh() shouldBe PaperMenuSessionResult.UNCHANGED
        observed.count { it.getPayload()["phase"] == "render" } shouldBe 1
        observed.first().getPayload()["revision"] shouldBe observed.last().getPayload()["revision"]
        service.close()
    }

    "impressions omit disabled buttons, decorations and region entities" {
        val plugin = paper.createSimplePlugin("ObservationVisible")
        val player = paper.addPlayer("Viewer")
        val observed = paper.observations(plugin)
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        service.open(player, MENU) {
            content(Material.DIAMOND).copy(
                elements = content(Material.DIAMOND).elements + (
                    BUTTON to PaperMenuEntry(
                        org.bukkit.inventory.ItemStack.of(Material.DIAMOND),
                        enabled = false,
                    )
                ),
            )
        }

        observed.filter { it.getPayload()["phase"] == "render" }.single().getPayload()["buttons"] shouldBe emptyMap<String, Int>()
        service.close()
    }

    "emits one accepted click and one blocked click per real event" {
        val plugin = paper.createSimplePlugin("ObservationClick")
        val player = paper.addPlayer("Viewer")
        val observed = paper.observations(plugin)
        var calls = 0
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        service.open(player, MENU) {
            content(Material.DIAMOND, PaperMenuClickHandler { calls++ }).copy(
                elements = content(Material.DIAMOND, PaperMenuClickHandler { calls++ }).elements +
                    (BUTTON to PaperMenuEntry(
                        org.bukkit.inventory.ItemStack.of(Material.DIAMOND),
                        acceptedClicks = setOf(ClickType.LEFT),
                        onClick = PaperMenuClickHandler { calls++ },
                    )) + (
                    DECORATION to PaperMenuEntry(org.bukkit.inventory.ItemStack.of(Material.STONE), enabled = false)
                ),
            )
        }
        val accepted = click(player, 4, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        paper.callEvent(accepted)
        paper.callEvent(accepted)
        val blocked = click(player, 4, ClickType.RIGHT, InventoryAction.PICKUP_ALL)
        paper.callEvent(blocked)
        val decoration = click(player, 8, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        paper.callEvent(decoration)

        calls shouldBe 1
        observed.count { it.getPayload()["phase"] == "click" } shouldBe 1
        observed.count { it.getPayload()["phase"] == "blocked" } shouldBe 1
        service.close()
    }

    "reports user, quit and shutdown close reasons" {
        val plugin = paper.createSimplePlugin("ObservationClose")
        val user = paper.addPlayer("User")
        val quit = paper.addPlayer("Quit")
        val observed = paper.observations(plugin)
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        service.open(user, MENU) { content(Material.STONE) }
        user.closeInventory()
        observed.last().getPayload()["reason"] shouldBe "user"
        service.open(quit, MENU) { content(Material.STONE) }
        paper.callEvent(PlayerQuitEvent(quit, net.kyori.adventure.text.Component.empty()))
        observed.last().getPayload()["reason"] shouldBe "quit"
        val shutdown = paper.addPlayer("Shutdown")
        service.open(shutdown, MENU) { content(Material.STONE) }
        service.close()
        observed.last().getPayload()["reason"] shouldBe "shutdown"
    }

    "reports denied transfer as blocked without a click action" {
        val plugin = paper.createSimplePlugin("ObservationTransfer")
        val player = paper.addPlayer("Viewer")
        val observed = paper.observations(plugin)
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        service.open(player, MENU) {
            PaperMenuContent(
                title = net.kyori.adventure.text.Component.text("Menu"),
                regions = mapOf(
                    CONTENT to listOf(
                        PaperMenuEntry(
                            org.bukkit.inventory.ItemStack.of(Material.DIAMOND),
                            acceptedClicks = setOf(ClickType.LEFT),
                            transfer = PaperMenuTransferHandler { PaperMenuTransferDecision.DENY },
                        ),
                    ),
                ),
            )
        }
        paper.callEvent(click(player, 10, ClickType.LEFT, InventoryAction.PICKUP_ALL))

        observed.count { it.getPayload()["phase"] == "click" } shouldBe 0
        observed.count { it.getPayload()["phase"] == "blocked" } shouldBe 1
        service.close()
    }

    "revision ignores generation but changes when layout changes" {
        val plugin = paper.createSimplePlugin("ObservationRevision")
        val player = paper.addPlayer("Viewer")
        val observed = paper.observations(plugin)
        val catalogs = repository()
        val service = PaperMenuService(plugin, catalogs, BukkitTaskScheduler(plugin))
        service.open(player, MENU) { content(Material.STONE) }
        val first = observed.last { it.getPayload()["phase"] == "render" }.getPayload()["revision"]
        service.closeSessions()
        catalogs.replace(catalogs.current())
        service.open(player, MENU) { content(Material.STONE) }
        val same = observed.last { it.getPayload()["phase"] == "render" }.getPayload()["revision"]
        same shouldBe first
        service.closeSessions()
        val layout = catalogs.current().require(MENU).copy(rows = 3)
        catalogs.replace(MenuCatalog(layouts = mapOf(MENU to layout)))
        service.open(player, MENU) { content(Material.STONE) }
        observed.last { it.getPayload()["phase"] == "render" }.getPayload()["revision"] shouldNotBe first
        service.close()
    }
})

private class ObservationCollector : Listener {
    val events = mutableListOf<PaperMenuObservationEvent>()

    @EventHandler
    fun on(event: PaperMenuObservationEvent) {
        events += event
    }
}

private fun MockBukkitTestRuntime.observations(plugin: org.bukkit.plugin.Plugin): MutableList<PaperMenuObservationEvent> {
    val collector = ObservationCollector()
    plugin.server.pluginManager.registerEvents(collector, plugin)
    return collector.events
}

private fun click(player: org.bukkit.entity.Player, slot: Int, type: ClickType, action: InventoryAction) =
    org.bukkit.event.inventory.InventoryClickEvent(
        player.openInventory,
        org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
        slot,
        type,
        action,
    )
