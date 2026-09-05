package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.NamespacedKey
import ru.arc.core.BukkitTaskScheduler
import ru.arc.menu.MenuCatalog
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperCloudStorageTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "cursor takes all and half, then bottom placement remains native" {
        val plugin = paper.createSimplePlugin("CloudCursor")
        val player = paper.addPlayer("Viewer")
        val storage = TestStorage(listOf(stack(Material.DIAMOND, 8), null, null))
        val runtime = runtime(plugin)
        val session = runtime.openStorage(player, MENU, CONTENT, storage, cloudContent())

        val takeHalf = click(player.openInventory, 10, ClickType.RIGHT, InventoryAction.PICKUP_HALF)
        paper.callEvent(takeHalf)
        takeHalf.isCancelled shouldBe true
        storage.snapshot()[0]?.amount shouldBe 4
        player.openInventory.cursor.amount shouldBe 4

        player.openInventory.setCursor(ItemStack.empty())
        val takeAll = click(player.openInventory, 10, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        paper.callEvent(takeAll)
        storage.snapshot()[0] shouldBe null
        player.openInventory.cursor.amount shouldBe 4

        val hotbarRaw = player.openInventory.topInventory.size + 27
        val hotbarSlot = player.openInventory.convertSlot(hotbarRaw)
        player.inventory.setItem(hotbarSlot, stack(Material.EMERALD, 2))
        player.inventory.getItem(hotbarSlot)?.amount shouldBe 2
        val nativeBottom = click(player.openInventory, hotbarRaw, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        paper.callEvent(nativeBottom)
        nativeBottom.isCancelled shouldBe false
        session.isOpen shouldBe true
        runtime.close()
    }

    "top deposit is read only while allowed bottom deposits merge and leave remainder" {
        val plugin = paper.createSimplePlugin("CloudDeposit")
        val player = paper.addPlayer("Depositor")
        val storage = TestStorage(listOf(stack(Material.DIAMOND, 63), null, null))
        val failures = mutableListOf<PaperCloudStorageFailure>()
        val runtime = runtime(plugin)
        runtime.openStorage(player, MENU, CONTENT, storage, cloudContent(onFailure = { _, failure -> failures += failure }))
        player.openInventory.setCursor(stack(Material.DIAMOND, 4))

        val deposit = click(player.openInventory, 10, ClickType.LEFT, InventoryAction.PLACE_ALL)
        paper.callEvent(deposit)
        deposit.isCancelled shouldBe true
        storage.snapshot()[0]?.amount shouldBe 64
        player.openInventory.cursor.amount shouldBe 3

        val readOnlyStorage = TestStorage(listOf(stack(Material.EMERALD, 4), null, null))
        runtime.openStorage(player, MENU, CONTENT, readOnlyStorage, cloudContent(allowDeposits = false))
        player.openInventory.setCursor(stack(Material.DIAMOND, 3))
        val readOnly = click(player.openInventory, 10, ClickType.LEFT, InventoryAction.PLACE_ALL)
        paper.callEvent(readOnly)
        readOnlyStorage.snapshot()[0]?.amount shouldBe 4
        readOnly.isCancelled shouldBe true
        runtime.close()
    }

    "shift takes use reverse hotbar order" {
        val plugin = paper.createSimplePlugin("CloudShift")
        val player = paper.addPlayer("Shifter")
        val storage = TestStorage(listOf(stack(Material.DIAMOND, 63), null, null))
        val runtime = runtime(plugin)
        runtime.openStorage(player, MENU, CONTENT, storage, cloudContent())
        val take = click(player.openInventory, 10, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY)
        paper.callEvent(take)
        storage.snapshot()[0] shouldBe null
        player.inventory.getItem(8) shouldBe stack(Material.DIAMOND, 63)
        runtime.close()
    }

    "rejected compare and set leaves backing and player state unchanged" {
        val plugin = paper.createSimplePlugin("CloudReject")
        val player = paper.addPlayer("Racer")
        val storage = TestStorage(listOf(stack(Material.DIAMOND, 2), null, null), rejectNext = true)
        val failures = mutableListOf<PaperCloudStorageFailure>()
        val runtime = runtime(plugin)
        runtime.openStorage(player, MENU, CONTENT, storage, cloudContent(onFailure = { _, failure -> failures += failure }))
        val event = click(player.openInventory, 10, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        paper.callEvent(event)
        event.isCancelled shouldBe true
        storage.snapshot()[0]?.amount shouldBe 2
        player.openInventory.cursor shouldBe ItemStack.empty()
        failures shouldBe listOf(PaperCloudStorageFailure.REJECTED)
        runtime.close()
    }

    "partial shift deposit preserves the remainder in the bottom event" {
        val plugin = paper.createSimplePlugin("CloudPartialShift")
        val player = paper.addPlayer("Partial")
        val storage = TestStorage(listOf(stack(Material.DIAMOND, 63), stack(Material.STONE, 64), stack(Material.STONE, 64)))
        val runtime = runtime(plugin)
        runtime.openStorage(player, MENU, CONTENT, storage, cloudContent())
        val view = player.openInventory
        val raw = view.topInventory.size + 27
        view.convertSlot(raw) shouldBe 0
        // MockBukkit 4.116.3 getItem/setItem use raw-offset mapping even though
        // convertSlot implements vanilla order. Exercise the event's own view.
        view.setItem(raw, stack(Material.DIAMOND, 4))
        val event = click(view, raw, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY)
        event.currentItem shouldBe stack(Material.DIAMOND, 4)
        paper.callEvent(event)
        event.isCancelled shouldBe true
        storage.snapshot()[0]?.amount shouldBe 64
        event.currentItem shouldBe stack(Material.DIAMOND, 3)
        storage.snapshot().filterNotNull().sumOf { it.amount } + event.currentItem!!.amount shouldBe 195
        runtime.close()
    }

    "shift out leaves a backend remainder when the player inventory is full" {
        val plugin = paper.createSimplePlugin("CloudShiftOut")
        val player = paper.addPlayer("Outbound")
        val storage = TestStorage(listOf(stack(Material.DIAMOND, 4), null, null))
        val runtime = runtime(plugin)
        runtime.openStorage(player, MENU, CONTENT, storage, cloudContent())
        player.inventory.storageContents.indices.forEach { player.inventory.setItem(it, stack(Material.STONE, 64)) }
        player.inventory.setItem(8, stack(Material.DIAMOND, 63))
        val event = click(player.openInventory, 10, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY)
        paper.callEvent(event)
        player.inventory.getItem(8)?.amount shouldBe 64
        storage.snapshot()[0]?.amount shouldBe 3
        runtime.close()
    }

    "two viewers cannot commit a stale rendered snapshot" {
        val plugin = paper.createSimplePlugin("CloudStale")
        val first = paper.addPlayer("First")
        val second = paper.addPlayer("Second")
        val storage = TestStorage(listOf(stack(Material.DIAMOND, 2), null, null))
        val failures = mutableListOf<PaperCloudStorageFailure>()
        val runtime = runtime(plugin)
        runtime.openStorage(first, MENU, CONTENT, storage, cloudContent(onFailure = { _, failure -> failures += failure }))
        runtime.openStorage(second, MENU, CONTENT, storage, cloudContent(onFailure = { _, failure -> failures += failure }))
        paper.callEvent(click(first.openInventory, 10, ClickType.LEFT, InventoryAction.PICKUP_ALL))
        val stale = click(second.openInventory, 10, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        paper.callEvent(stale)
        stale.isCancelled shouldBe true
        second.openInventory.cursor shouldBe ItemStack.empty()
        failures shouldBe listOf(PaperCloudStorageFailure.STALE)
        runtime.close()
    }

    "metadata survives refresh, slot order maps logical storage and decorations pad region" {
        val plugin = paper.createSimplePlugin("CloudShape")
        val player = paper.addPlayer("Reader")
        val named = stack(Material.DIAMOND, 1).also { it.editMeta { meta -> meta.displayName(Component.text("Named")) } }
        val storage = TestStorage(listOf(named, null))
        val runtime = runtime(plugin)
        val session = runtime.openStorage(
            player, MENU, CONTENT, storage,
            cloudContent(slotOrder = listOf(1, 0, 2), decorations = mapOf(2 to stack(Material.GLASS, 1))),
        )
        player.openInventory.topInventory.getItem(10) shouldBe null
        player.openInventory.topInventory.getItem(11)?.itemMeta?.displayName() shouldBe Component.text("Named")
        session.refresh()
        player.openInventory.topInventory.getItem(11)?.itemMeta?.displayName() shouldBe Component.text("Named")
        runtime.close()
    }

    "cursor claim preserves item metadata and decoration clicks are denied" {
        val plugin = paper.createSimplePlugin("CloudMeta")
        val player = paper.addPlayer("Meta")
        val key = NamespacedKey(plugin, "marker")
        val named = stack(Material.DIAMOND, 2).also { it.editMeta { meta -> meta.displayName(Component.text("Named")); meta.persistentDataContainer.set(key, org.bukkit.persistence.PersistentDataType.STRING, "yes") } }
        val storage = TestStorage(listOf(named, null))
        val runtime = runtime(plugin)
        runtime.openStorage(player, MENU, CONTENT, storage, cloudContent(decorations = mapOf(2 to stack(Material.GLASS, 1))))
        val claim = click(player.openInventory, 10, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        paper.callEvent(claim)
        player.openInventory.cursor.itemMeta.displayName() shouldBe Component.text("Named")
        player.openInventory.cursor.itemMeta.persistentDataContainer.get(key, org.bukkit.persistence.PersistentDataType.STRING) shouldBe "yes"
        val decoration = click(player.openInventory, 12, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        paper.callEvent(decoration)
        decoration.isCancelled shouldBe true
        runtime.close()
    }

    "top drags cancel, bottom drags stay native, and close and quit clean sessions" {
        val plugin = paper.createSimplePlugin("CloudLifecycle")
        val player = paper.addPlayer("Lifecycle")
        val storage = TestStorage(listOf(stack(Material.DIAMOND, 1), null, null))
        val runtime = runtime(plugin)
        val session = runtime.openStorage(player, MENU, CONTENT, storage, cloudContent())
        val topDrag = InventoryDragEvent(player.openInventory, ItemStack.empty(), stack(Material.STONE, 1), false, mapOf(10 to stack(Material.STONE, 1)))
        paper.callEvent(topDrag)
        topDrag.isCancelled shouldBe true
        val bottomDrag = InventoryDragEvent(player.openInventory, ItemStack.empty(), stack(Material.STONE, 1), false, mapOf(player.openInventory.topInventory.size to stack(Material.STONE, 1)))
        paper.callEvent(bottomDrag)
        bottomDrag.isCancelled shouldBe false
        paper.callEvent(InventoryCloseEvent(player.openInventory, InventoryCloseEvent.Reason.PLAYER))
        session.isOpen shouldBe false
        val reopened = runtime.openStorage(player, MENU, CONTENT, storage, cloudContent())
        paper.callEvent(PlayerQuitEvent(player, Component.empty()))
        reopened.isOpen shouldBe false
        runtime.close()
    }

    "reload closes the session and replaying a consumed event cannot take again" {
        val plugin = paper.createSimplePlugin("CloudReload")
        val player = paper.addPlayer("Reload")
        val storage = TestStorage(listOf(stack(Material.DIAMOND, 2), null, null))
        val runtime = runtime(plugin)
        val session = runtime.openStorage(player, MENU, CONTENT, storage, cloudContent())
        val event = click(player.openInventory, 10, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        paper.callEvent(event)
        storage.snapshot()[0] shouldBe null
        player.openInventory.cursor.amount shouldBe 2
        event.isCancelled = false
        paper.callEvent(event)
        player.openInventory.cursor.amount shouldBe 2
        storage.snapshot()[0] shouldBe null
        runtime.replace(runtime.current())
        session.isOpen shouldBe false
        player.openInventory.cursor shouldBe ItemStack.empty()
        event.isCancelled = false
        paper.callEvent(event)
        event.isCancelled shouldBe true
        player.openInventory.cursor shouldBe ItemStack.empty()
        storage.snapshot()[0] shouldBe null
        runtime.close()
    }
})

private class TestStorage(initial: List<ItemStack?>, var rejectNext: Boolean = false) : PaperCloudStorage {
    var external: List<ItemStack?> = initial
    override fun snapshot(): List<ItemStack?> = external.map { it?.clone() }
    override fun compareAndSet(expected: List<ItemStack?>, replacement: List<ItemStack?>): Boolean {
        if (rejectNext) { rejectNext = false; return false }
        if (external != expected) return false
        external = replacement.map { it?.clone() }
        return true
    }
}

private fun runtime(plugin: org.bukkit.plugin.Plugin) = PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), PaperMenuConfiguration(MenuCatalog(layouts = mapOf(MENU to repository().current().require(MENU))), emptyMap()))
private fun cloudContent(
    allowDeposits: Boolean = true,
    slotOrder: List<Int>? = null,
    decorations: Map<Int, ItemStack> = emptyMap(),
    onFailure: (org.bukkit.entity.Player, PaperCloudStorageFailure) -> Unit = { _, _ -> },
) = PaperCloudStorageContent(Component.text("Storage"), allowDeposits, slotOrder, decorations, onFailure = onFailure)
private fun stack(material: Material, amount: Int) = ItemStack.of(material, amount)
private fun click(view: org.bukkit.inventory.InventoryView, rawSlot: Int, type: ClickType, action: InventoryAction) =
    InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, type, action).also {
        require(view.convertSlot(rawSlot) >= 0)
    }
