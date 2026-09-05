package ru.arc.paper.menu

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.menu.MenuLayout
import ru.arc.menu.MenuRegionId
import java.util.Collections
import java.util.WeakHashMap

/**
 * A native chest backed by conditional domain mutations, without GUI item tags.
 * Normal bottom-inventory clicks stay native. Top clicks commit storage, slots
 * and cursor within the cancelled event, with no reopen or deferred cursor write.
 * Close/quit returns the cursor through Paper's normal inventory-close lifecycle.
 */
class PaperCloudStorageSession internal constructor(
    val player: Player,
    layout: MenuLayout,
    region: MenuRegionId,
    private val storage: PaperCloudStorage,
    private val content: PaperCloudStorageContent,
    scheduler: TaskScheduler,
) : InventoryHolder {
    private val tasks = LifecycleTaskScope(scheduler)
    private val top = Bukkit.createInventory(this, layout.rows * 9, content.title)
    private val regionSlots = layout.region(region).map { it.index }
    private val order = content.slotOrder ?: regionSlots.indices.toList()
    private val slots: List<Int>
    private val buttons = content.buttons.flatMap { (id, button) ->
        layout.elements.getValue(id).slots.map { it.index to button }
    }.toMap()
    private var rendered: List<ItemStack?> = emptyList()
    private val processedEvents = Collections.newSetFromMap(WeakHashMap<InventoryClickEvent, Boolean>())
    var isOpen: Boolean = true
        private set

    init {
        require(layout.pagination == null) { "Cloud storage does not paginate backing slots" }
        require(order.sorted() == regionSlots.indices.toList()) { "Cloud storage slot order must be a region permutation" }
        slots = order.map(regionSlots::get)
        content.background?.let { background ->
            (0 until top.size).filterNot(regionSlots::contains).forEach { top.setItem(it, background.clone()) }
        }
        buttons.forEach { (slot, button) -> top.setItem(slot, button.item.clone()) }
        refresh()
    }

    override fun getInventory(): Inventory = top

    /** Refresh after external domain changes; touched slots only, cursor unchanged. */
    fun refresh() {
        check(Bukkit.isPrimaryThread())
        if (!isOpen) return
        val snapshot = storage.snapshot().map { it?.takeUnless { item -> item.type.isAir }?.clone() }
        render(snapshot)
    }

    private fun render(snapshot: List<ItemStack?>) {
        require(snapshot.size <= slots.size) { "Cloud storage exceeds its configured region" }
        require(content.decorations.keys.all { it in snapshot.size until slots.size }) { "Decorations overlap backing storage" }
        slots.forEachIndexed { index, slot ->
            val desired = snapshot.getOrNull(index) ?: content.decorations[index]
            if (top.getItem(slot) != desired) top.setItem(slot, desired?.clone())
        }
        rendered = snapshot.map { it?.clone() }
    }

    internal fun closed() { isOpen = false; tasks.close() }

    fun close() {
        check(Bukkit.isPrimaryThread())
        closed()
        if (player.openInventory.topInventory === top) player.closeInventory()
    }

    internal fun click(event: InventoryClickEvent) {
        if (!isOpen) { event.isCancelled = true; return }
        val topClick = event.rawSlot in 0 until top.size
        // Double-click can collect from the protected top even when clicked below.
        if (!topClick && !event.isShiftClick && event.click != ClickType.DOUBLE_CLICK) return
        event.isCancelled = true
        if (!processedEvents.add(event)) return
        if (event.click !in setOf(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT)) return
        if (topClick && event.rawSlot in buttons) {
            if (!event.isShiftClick) tasks.runLater(1) {
                if (isOpen && player.openInventory.topInventory === top) buttons.getValue(event.rawSlot).action(player)
            }
            return
        }
        val index = slots.indexOf(event.rawSlot)
        if (topClick && index !in rendered.indices) return
        if (!topClick && (!content.allowDeposits || event.clickedInventory !== player.inventory)) return
        val expected = storage.snapshot()
        if (expected != rendered) {
            refresh()
            content.onFailure(player, PaperCloudStorageFailure.STALE)
            return
        }
        if (topClick && event.currentItem?.takeUnless { it.type.isAir } != expected[index]) {
            refresh()
            return
        }
        val replacement = expected.map { it?.clone() }.toMutableList()
        val cursor = event.cursor.takeUnless { it.type.isAir }
        var nextCursor = cursor?.clone()
        var playerMove: CloudStorageMove? = null
        var bottomRemainder: ItemStack? = null
        if (!topClick) {
            val source = event.currentItem?.takeUnless { it.type.isAir } ?: return
            val move = planCloudStorageMove(expected, source, expected.indices.toList())
            if (move.moved == 0) { content.onFailure(player, PaperCloudStorageFailure.FULL); return }
            replacement.clear()
            replacement.addAll(move.contents)
            bottomRemainder = remainder(source, move.moved)
        } else {
            val item = expected[index]
            when {
                event.isShiftClick -> {
                    if (item == null) return
                    playerMove = planCloudStorageMove(player.inventory.storageContents.toList(), item, (8 downTo 0) + (35 downTo 9))
                    if (playerMove.moved == 0) { content.onFailure(player, PaperCloudStorageFailure.FULL); return }
                    replacement[index] = remainder(item, playerMove.moved)
                }
                cursor == null -> {
                    if (item == null) return
                    val amount = minOf(item.maxStackSize, if (event.isRightClick) (item.amount + 1) / 2 else item.amount)
                    nextCursor = item.clone().also { it.amount = amount }
                    replacement[index] = remainder(item, amount)
                }
                !content.allowDeposits -> return
                item == null || item.isSimilar(cursor) -> {
                    val room = ((item?.maxStackSize ?: cursor.maxStackSize) - (item?.amount ?: 0)).coerceAtLeast(0)
                    val amount = minOf(room, if (event.isRightClick) 1 else cursor.amount)
                    if (amount == 0) return
                    replacement[index] = cursor.clone().also { it.amount = (item?.amount ?: 0) + amount }
                    nextCursor = remainder(cursor, amount)
                }
                else -> {
                    if (cursor.amount > cursor.maxStackSize || item.amount > item.maxStackSize) return
                    replacement[index] = cursor.clone()
                    nextCursor = item.clone()
                }
            }
        }
        if (!storage.compareAndSet(expected, replacement)) {
            refresh()
            content.onFailure(player, PaperCloudStorageFailure.REJECTED)
            return
        }
        // No asynchronous work between successful storage commit and player commit.
        if (!topClick) event.currentItem = bottomRemainder
        playerMove?.contents?.forEachIndexed { slot, item ->
            if (player.inventory.getItem(slot) != item) player.inventory.setItem(slot, item?.clone())
        }
        if (topClick && !event.isShiftClick) event.view.setCursor(nextCursor)
        render(replacement)
    }

    private fun remainder(item: ItemStack, removed: Int): ItemStack? =
        (item.amount - removed).takeIf { it > 0 }?.let { amount -> item.clone().also { it.amount = amount } }
}
