package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcTelemetryProvider
import ru.arc.core.BukkitTaskScheduler
import ru.arc.menu.MenuCatalog
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperMenuObservationTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "skips observations when ARC has not registered a provider" {
        val plugin = paper.createSimplePlugin("ObservationAbsent")
        val player = paper.addPlayer("Viewer")
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))

        service.open(player, MENU) { content(Material.STONE) }
        service.close()
    }

    "observes open and actual renders, but suppresses unchanged refresh" {
        val plugin = paper.createSimplePlugin("ObservationRender")
        val player = paper.addPlayer("Viewer")
        val observed = paper.observations(plugin)
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        val session = service.open(player, MENU) { content(Material.DIAMOND) }

        observed.map { it["phase"] } shouldBe listOf("render", "open")
        session.refresh() shouldBe PaperMenuSessionResult.UNCHANGED
        observed.count { it["phase"] == "render" } shouldBe 1
        observed.first()["revision"] shouldBe observed.last()["revision"]
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

        observed.filter { it["phase"] == "render" }.single()["buttons"] shouldBe emptyMap<String, Int>()
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
        observed.count { it["phase"] == "click" } shouldBe 1
        observed.count { it["phase"] == "blocked" } shouldBe 1
        service.close()
    }

    "reports user, quit and shutdown close reasons" {
        val plugin = paper.createSimplePlugin("ObservationClose")
        val user = paper.addPlayer("User")
        val quit = paper.addPlayer("Quit")
        val observed = paper.observations(plugin)
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        service.open(user, MENU) { content(Material.STONE) }
        paper.callEvent(InventoryCloseEvent(user.openInventory, InventoryCloseEvent.Reason.PLAYER))
        observed.last()["reason"] shouldBe "user"
        service.open(user, MENU) { content(Material.STONE) }
        paper.callEvent(InventoryCloseEvent(user.openInventory, InventoryCloseEvent.Reason.OPEN_NEW))
        observed.last()["reason"] shouldBe "censored"
        service.open(quit, MENU) { content(Material.STONE) }
        paper.callEvent(PlayerQuitEvent(quit, net.kyori.adventure.text.Component.empty()))
        observed.last()["reason"] shouldBe "quit"
        val shutdown = paper.addPlayer("Shutdown")
        service.open(shutdown, MENU) { content(Material.STONE) }
        service.close()
        observed.last()["reason"] shouldBe "shutdown"
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

        observed.count { it["phase"] == "click" } shouldBe 0
        observed.count { it["phase"] == "blocked" } shouldBe 1
        service.close()
    }

    "revision ignores generation but changes when layout changes" {
        val plugin = paper.createSimplePlugin("ObservationRevision")
        val player = paper.addPlayer("Viewer")
        val observed = paper.observations(plugin)
        val catalogs = repository()
        val service = PaperMenuService(plugin, catalogs, BukkitTaskScheduler(plugin))
        service.open(player, MENU) { content(Material.STONE) }
        val first = observed.last { it["phase"] == "render" }["revision"]
        service.closeSessions()
        catalogs.replace(catalogs.current())
        service.open(player, MENU) { content(Material.STONE) }
        val same = observed.last { it["phase"] == "render" }["revision"]
        same shouldBe first
        service.closeSessions()
        val layout = catalogs.current().require(MENU).copy(rows = 3)
        catalogs.replace(MenuCatalog(layouts = mapOf(MENU to layout)))
        service.open(player, MENU) { content(Material.STONE) }
        observed.last { it["phase"] == "render" }["revision"] shouldNotBe first
        service.close()
    }
})

private class ObservationCollector : ArcTelemetryProvider {
    val events = mutableListOf<Map<String, Any>>()

    override fun observeUi(payload: Map<String, Any>) {
        events += payload
    }
}

private fun MockBukkitTestRuntime.observations(plugin: org.bukkit.plugin.Plugin): MutableList<Map<String, Any>> {
    val collector = ObservationCollector()
    plugin.server.servicesManager.register(ArcTelemetryProvider::class.java, collector, plugin, ServicePriority.Normal)
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
