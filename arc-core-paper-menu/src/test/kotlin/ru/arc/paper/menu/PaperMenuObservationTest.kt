package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
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
import java.util.UUID

class PaperMenuObservationTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "skips observations when ARC has not registered a provider" {
        val plugin = paper.createSimplePlugin("ObservationAbsent")
        val player = paper.addPlayer("Viewer")
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        var clicks = 0

        service.open(player, MENU) { content(Material.STONE) { clicks++ } }
        paper.callEvent(click(player, 4, ClickType.LEFT, InventoryAction.PICKUP_ALL))
        clicks shouldBe 1
        service.close()
    }

    "nested dispatch closes each scope on its original sink even when observation and action fail" {
        val plugin = paper.createSimplePlugin("ObservationScope")
        val player = paper.addPlayer("Viewer")
        val observed = mutableListOf<Map<String, Any>>()
        val sink = object : ArcTelemetryProvider {
            override fun observeUi(payload: Map<String, Any>) {
                observed += payload
                if ((payload["phase"] as String).startsWith("dispatch_")) error("optional sink failure")
            }
        }
        val services = plugin.server.servicesManager
        services.register(ArcTelemetryProvider::class.java, sink, plugin, ServicePriority.Normal)
        val fields = mapOf("protocol" to 1, "playerId" to player.uniqueId.toString(), "visitId" to UUID.randomUUID().toString(),
            "owner" to plugin.name, "surface" to "scope", "revision" to "revision", "button" to "action")
        val outer = requireNotNull(PaperMenuObservationPublisher.attempt(plugin) { fields })
        val inner = requireNotNull(PaperMenuObservationPublisher.attempt(plugin) { fields })
        val failure = IllegalStateException("action failure")
        var calls = 0
        outer.start()
        val result: String? = PaperMenuObservationPublisher.dispatch(outer) {
            calls++
            shouldThrow<IllegalStateException> {
                PaperMenuObservationPublisher.dispatch(inner) {
                    calls++
                    throw failure
                }
            } shouldBe failure
            PaperMenuObservationPublisher.currentAttempt() shouldBe outer
            services.unregister(ArcTelemetryProvider::class.java, sink)
            null
        }

        result shouldBe null
        calls shouldBe 2
        PaperMenuObservationPublisher.currentAttempt() shouldBe null
        val scopes = observed.filter { (it["phase"] as String).startsWith("dispatch_") }
        scopes shouldBe listOf(
            fields + mapOf("phase" to "dispatch_start", "attemptId" to outer.id, "started" to true),
            fields + mapOf("phase" to "dispatch_start", "attemptId" to inner.id, "started" to false),
            fields + mapOf("phase" to "dispatch_end", "attemptId" to inner.id, "started" to false),
            fields + mapOf("phase" to "dispatch_end", "attemptId" to outer.id, "started" to true),
        )
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

        val rendered = observed.single { it["phase"] == "render" }
        rendered["buttons"] shouldBe emptyMap<String, Int>()
        rendered["visibleSlotButtons"] shouldBe mapOf(4 to BUTTON.value)
        rendered["disabledSlots"] shouldBe listOf(4)
        val event = click(player, 4, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        paper.callEvent(event)
        event.isCancelled shouldBe true
        observed.single { it["phase"] == "blocked" }["reason"] shouldBe "disabled"
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
        val acceptedObservation = observed.single { it["phase"] == "click" }
        val blockedObservation = observed.single { it["phase"] == "blocked" }
        val rejected = observed.single { it["phase"] == "result" }
        val attempt = acceptedObservation["attemptId"] as String
        UUID.fromString(attempt).toString() shouldBe attempt
        blockedObservation["attemptId"] shouldNotBe attempt
        rejected["attemptId"] shouldBe blockedObservation["attemptId"]
        rejected["outcome"] shouldBe "rejected"
        rejected["reason"] shouldBe "unsupported_click"
        service.close()
    }

    "includes safe row, page, click and material fields for a paginated entry" {
        val plugin = paper.createSimplePlugin("ObservationRow")
        val player = paper.addPlayer("Viewer")
        val observed = paper.observations(plugin)
        val service = PaperMenuService(plugin, repository(paginated = true), BukkitTaskScheduler(plugin))
        lateinit var clicked: PaperMenuClickContext
        val entries = listOf(
            PaperMenuEntry(org.bukkit.inventory.ItemStack.of(Material.STONE)),
            PaperMenuEntry(org.bukkit.inventory.ItemStack.of(Material.DIRT)),
            PaperMenuEntry(org.bukkit.inventory.ItemStack.of(Material.COPPER_INGOT)),
            PaperMenuEntry(org.bukkit.inventory.ItemStack.of(Material.EMERALD, 7), onClick = { clicked = it }),
        )
        val session = service.open(player, MENU) {
            content(Material.DIAMOND).copy(regions = mapOf(CONTENT to entries))
        }
        val firstPage = observed.last()
        firstPage["buttons"] shouldBe mapOf(BUTTON.value to 4, "region:content" to 10)
        firstPage["visibleSlotButtons"] shouldBe mapOf(
            4 to BUTTON.value, 10 to "region:content", 11 to "region:content", 12 to "region:content",
        )
        session.setPage(1)

        paper.callEvent(click(player, 10, ClickType.LEFT, InventoryAction.PICKUP_ALL))

        val click = observed.last { it["phase"] == "click" }
        click["button"] shouldBe "region:content"
        click["rawSlot"] shouldBe 10
        click["regionIndex"] shouldBe 3
        click["pageIndex"] shouldBe 1
        click["pageCount"] shouldBe 2
        click["clickType"] shouldBe "LEFT"
        click["action"] shouldBe "PICKUP_ALL"
        click["material"] shouldBe "minecraft:emerald"
        click["amount"] shouldBe 7
        (click.keys intersect setOf("lore", "nbt", "displayText")) shouldBe emptySet()
        session.setPage(0)
        clicked.reportOutcome("succeeded", "entry_selected", "catalog")
        observed.last() shouldBe click + mapOf(
            "phase" to "result", "outcome" to "succeeded", "reason" to "entry_selected", "target" to "catalog",
        )
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

    "reports denied and failed transfers without accepting the click" {
        val plugin = paper.createSimplePlugin("ObservationTransfer")
        val player = paper.addPlayer("Viewer")
        val observed = paper.observations(plugin)
        val failure = IllegalStateException("private transfer details")
        var failTransfer = false
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        service.open(player, MENU) {
            PaperMenuContent(
                title = net.kyori.adventure.text.Component.text("Menu"),
                regions = mapOf(
                    CONTENT to listOf(
                        PaperMenuEntry(
                            org.bukkit.inventory.ItemStack.of(Material.DIAMOND),
                            acceptedClicks = setOf(ClickType.LEFT),
                            transfer = PaperMenuTransferHandler {
                                if (failTransfer) throw failure
                                PaperMenuTransferDecision.DENY
                            },
                        ),
                    ),
                ),
            )
        }
        paper.callEvent(click(player, 10, ClickType.LEFT, InventoryAction.PICKUP_ALL))

        observed.count { it["phase"] == "click" } shouldBe 0
        val denied = observed.single { it["phase"] == "blocked" }
        denied["reason"] shouldBe "transfer_denied"
        observed.single { it["phase"] == "result" }["attemptId"] shouldBe denied["attemptId"]

        failTransfer = true
        val failing = click(player, 10, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        // Inventory Framework logs the propagated exception at its callback boundary.
        service.onClick(failing)
        failing.isCancelled shouldBe true
        observed.takeLast(2).map { it["phase"] } shouldBe listOf("blocked", "result")
        observed.last()["outcome"] shouldBe "failed"
        observed.last()["reason"] shouldBe "handler_exception"
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

internal fun MockBukkitTestRuntime.observations(plugin: org.bukkit.plugin.Plugin): MutableList<Map<String, Any>> {
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
