package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperMenuInteractionTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "dispatches configured semantic button once and never dispatches decoration" {
        val plugin = paper.createSimplePlugin("MenuClick")
        val player = paper.addPlayer("Viewer")
        val clicks = mutableListOf<PaperMenuClickContext>()
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        service.open(player, MENU) { content(Material.DIAMOND, PaperMenuClickHandler(clicks::add)) }

        val button = click(player.openInventory, 4, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        paper.callEvent(button)
        service.session(player.uniqueId)!!.handleClick(button)
        val decoration = click(player.openInventory, 8, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        paper.callEvent(decoration)

        button.isCancelled shouldBe true
        decoration.isCancelled shouldBe true
        clicks.size shouldBe 1
        clicks.single().target shouldBe PaperMenuClickTarget.Element(BUTTON)
        clicks.single().player shouldBe player
        service.close()
    }

    "refresh removes stale handlers and top-touching drags are cancelled" {
        val plugin = paper.createSimplePlugin("MenuRefresh")
        val player = paper.addPlayer("Viewer")
        var useNew = false
        var oldCalls = 0
        var newCalls = 0
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        val session = service.open(player, MENU) {
            content(
                Material.DIAMOND,
                if (useNew) PaperMenuClickHandler { newCalls++ } else PaperMenuClickHandler { oldCalls++ },
            )
        }
        useNew = true
        session.refresh()
        val click = click(player.openInventory, 4, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        paper.callEvent(click)
        val drag = InventoryDragEvent(
            player.openInventory,
            org.bukkit.inventory.ItemStack.empty(),
            org.bukkit.inventory.ItemStack.of(Material.STONE, 2),
            true,
            mapOf(0 to org.bukkit.inventory.ItemStack.of(Material.STONE), session.inventory.size to org.bukkit.inventory.ItemStack.of(Material.STONE)),
        )
        paper.callEvent(drag)

        oldCalls shouldBe 0
        newCalls shouldBe 1
        drag.isCancelled shouldBe true
        service.close()
    }

    "no-op refresh preserves rendered slots while publishing the latest handler" {
        val plugin = paper.createSimplePlugin("MenuNoOpRefresh")
        val player = paper.addPlayer("Viewer")
        var generation = 1
        var invokedGeneration = 0
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        val session = service.open(player, MENU) {
            val captured = generation
            content(Material.DIAMOND, PaperMenuClickHandler { invokedGeneration = captured })
        }

        session.renderStats() shouldBe PaperMenuRenderStats(fullRenders = 1, slotUpdates = 0)
        generation = 2
        session.refresh() shouldBe PaperMenuSessionResult.UNCHANGED
        session.renderStats() shouldBe PaperMenuRenderStats(fullRenders = 1, slotUpdates = 0)

        paper.callEvent(click(player.openInventory, 4, ClickType.LEFT, InventoryAction.PICKUP_ALL))
        invokedGeneration shouldBe 2
        service.close()
    }

    "refresh from inside a click atomically keeps the action and patches one slot" {
        val plugin = paper.createSimplePlugin("MenuClickRefresh")
        val player = paper.addPlayer("Builder")
        var paused = false
        var calls = 0
        lateinit var session: PaperMenuSession
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        session = service.open(player, MENU) {
            content(
                if (paused) Material.RED_DYE else Material.LIME_DYE,
                PaperMenuClickHandler {
                    calls++
                    paused = true
                    session.refresh()
                },
            )
        }

        paper.callEvent(click(player.openInventory, 4, ClickType.LEFT, InventoryAction.PICKUP_ALL))

        calls shouldBe 1
        player.openInventory.topInventory.getItem(4)?.type shouldBe Material.RED_DYE
        session.renderStats() shouldBe PaperMenuRenderStats(fullRenders = 1, slotUpdates = 1)
        service.close()
    }

    "queued refresh requests coalesce into one next-tick render" {
        val plugin = paper.createSimplePlugin("MenuQueuedRefresh")
        val player = paper.addPlayer("Builder")
        var material = Material.DIAMOND
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        val session = service.open(player, MENU) { content(material) }
        material = Material.EMERALD

        session.requestRefresh() shouldBe PaperMenuSessionResult.QUEUED
        session.requestRefresh() shouldBe PaperMenuSessionResult.UNCHANGED
        session.requestRefresh() shouldBe PaperMenuSessionResult.UNCHANGED
        paper.server.scheduler.performOneTick()

        player.openInventory.topInventory.getItem(4)?.type shouldBe Material.EMERALD
        session.renderStats() shouldBe PaperMenuRenderStats(fullRenders = 1, slotUpdates = 1)
        service.close()
    }

    "cancels bottom and unsafe click types while honoring an accepted right click" {
        val plugin = paper.createSimplePlugin("MenuSafety")
        val owner = paper.addPlayer("Owner")
        val stranger = paper.addPlayer("Stranger")
        var calls = 0
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        service.open(owner, MENU) {
            content(Material.DIAMOND).copy(
                elements = mapOf(
                    BUTTON to PaperMenuEntry(
                        org.bukkit.inventory.ItemStack.of(Material.DIAMOND),
                        acceptedClicks = setOf(ClickType.RIGHT),
                        onClick = PaperMenuClickHandler { calls++ },
                    ),
                    DECORATION to PaperMenuEntry(org.bukkit.inventory.ItemStack.of(Material.STONE), enabled = false),
                ),
            )
        }

        listOf(ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK, ClickType.DROP, ClickType.SWAP_OFFHAND)
            .forEach { type ->
                val event = click(owner.openInventory, 4, type, InventoryAction.NOTHING)
                paper.callEvent(event)
                event.isCancelled shouldBe true
            }
        val right = click(owner.openInventory, 4, ClickType.RIGHT, InventoryAction.PICKUP_HALF)
        paper.callEvent(right)
        val bottom = click(owner.openInventory, owner.openInventory.topInventory.size, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY)
        paper.callEvent(bottom)

        calls shouldBe 1
        bottom.isCancelled shouldBe true
        service.session(stranger.uniqueId) shouldBe null
        service.close()
    }

    "allows explicitly declared shift actions while still cancelling movement" {
        val plugin = paper.createSimplePlugin("MenuShift")
        val owner = paper.addPlayer("Owner")
        var invoked = 0
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        service.open(owner, MENU) {
            content(
                Material.DIAMOND,
                PaperMenuClickHandler { invoked++ },
            ).copy(
                elements = content(Material.DIAMOND).elements + (
                    BUTTON to PaperMenuEntry(
                        ItemStack.of(Material.DIAMOND),
                        acceptedClicks = setOf(ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT),
                        onClick = PaperMenuClickHandler { invoked++ },
                    )
                ),
            )
        }

        click(owner.openInventory, 4, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY).also {
            paper.callEvent(it)
            it.isCancelled shouldBe true
        }
        invoked shouldBe 1
        service.close()
    }

    "stale catalog generations cancel but do not invoke handlers" {
        val plugin = paper.createSimplePlugin("MenuGeneration")
        val player = paper.addPlayer("Viewer")
        val repository = repository()
        var calls = 0
        val service = PaperMenuService(plugin, repository, BukkitTaskScheduler(plugin))
        val session = service.open(player, MENU) { content(Material.DIAMOND, PaperMenuClickHandler { calls++ }) }
        repository.replace(repository.current())

        val event = click(player.openInventory, 4, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        paper.callEvent(event)

        event.isCancelled shouldBe true
        calls shouldBe 0
        session.refresh() shouldBe PaperMenuSessionResult.STALE_GENERATION
        service.close()
    }

    "allows only an explicitly approved top-slot take and keeps placement blocked" {
        val plugin = paper.createSimplePlugin("MenuTransfer")
        val player = paper.addPlayer("Collector")
        var approvals = 0
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        service.open(player, MENU) {
            PaperMenuContent(
                title = net.kyori.adventure.text.Component.text("Loot"),
                regions = mapOf(
                    CONTENT to listOf(
                        PaperMenuEntry(
                            ItemStack.of(Material.DIAMOND),
                            transfer = PaperMenuTransferHandler {
                                approvals++
                                PaperMenuTransferDecision.ALLOW
                            },
                        ),
                    ),
                ),
            )
        }

        val take = click(player.openInventory, 10, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        paper.callEvent(take)
        take.isCancelled shouldBe false
        approvals shouldBe 1

        val place = click(player.openInventory, 10, ClickType.LEFT, InventoryAction.PLACE_ALL)
        paper.callEvent(place)
        place.isCancelled shouldBe true
        approvals shouldBe 1

        val bottomShift = click(
            player.openInventory,
            player.openInventory.topInventory.size,
            ClickType.SHIFT_LEFT,
            InventoryAction.MOVE_TO_OTHER_INVENTORY,
        )
        paper.callEvent(bottomShift)
        bottomShift.isCancelled shouldBe true
        approvals shouldBe 1
        service.close()
    }

    "a transfer handler can atomically deny a stale item take" {
        val plugin = paper.createSimplePlugin("MenuTransferDeny")
        val player = paper.addPlayer("Collector")
        var attempts = 0
        val service = PaperMenuService(plugin, repository(), BukkitTaskScheduler(plugin))
        service.open(player, MENU) {
            PaperMenuContent(
                title = net.kyori.adventure.text.Component.text("Loot"),
                regions = mapOf(
                    CONTENT to listOf(
                        PaperMenuEntry(
                            ItemStack.of(Material.DIAMOND),
                            acceptedClicks = setOf(ClickType.SHIFT_LEFT),
                            transfer = PaperMenuTransferHandler {
                                attempts++
                                PaperMenuTransferDecision.DENY
                            },
                        ),
                    ),
                ),
            )
        }

        val take = click(player.openInventory, 10, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY)
        paper.callEvent(take)

        take.isCancelled shouldBe true
        attempts shouldBe 1
        service.close()
    }
})

private fun click(
    view: org.bukkit.inventory.InventoryView,
    rawSlot: Int,
    type: ClickType,
    action: InventoryAction,
): InventoryClickEvent = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, type, action)
