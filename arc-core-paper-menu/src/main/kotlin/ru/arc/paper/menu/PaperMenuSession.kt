package ru.arc.paper.menu

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.menu.MenuCatalogRepository
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuElementKind
import ru.arc.menu.MenuFeedbackToken
import ru.arc.menu.MenuId
import ru.arc.menu.MenuLayout
import ru.arc.menu.MenuPageState
import ru.arc.menu.MenuRegionId
import ru.arc.menu.MenuRegionKind
import java.util.Collections
import java.util.WeakHashMap
import java.util.function.Consumer

enum class PaperMenuSessionResult {
    RENDERED,
    UNCHANGED,
    NO_PAGINATION,
    STALE_GENERATION,
    CLOSED,
}

class PaperMenuSession internal constructor(
    private val plugin: Plugin,
    private val catalogs: MenuCatalogRepository,
    val menuId: MenuId,
    val player: Player,
    private val generation: Long,
    private val layout: MenuLayout,
    private val contentProvider: () -> PaperMenuContent,
    scheduler: TaskScheduler,
    private val onClosed: (PaperMenuSession) -> Unit,
) {
    private val gui = ChestGui(layout.rows, ComponentHolder.of(net.kyori.adventure.text.Component.empty()), plugin)
    private val backgroundPane = StaticPane(9, layout.rows, Pane.Priority.LOWEST)
    private val contentPane = StaticPane(9, layout.rows, Pane.Priority.NORMAL)
    private val tasks = LifecycleTaskScope(scheduler)
    private val feedback = PaperMenuFeedback()
    private val processedEvents = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<InventoryClickEvent, Boolean>()))
    private var requestedPage = 0
    private var renderedPageState: MenuPageState? = null
    private var open = true

    val isOpen: Boolean get() = open
    val inventory: Inventory get() = gui.inventory

    init {
        gui.addPane(Slot.fromXY(0, 0), backgroundPane)
        gui.addPane(Slot.fromXY(0, 0), contentPane)
    }

    internal fun prepare() {
        render(requireCurrent = false)
    }

    internal fun show() {
        requirePrimaryThread()
        check(open) { "Cannot show a closed menu session" }
        gui.show(player)
    }

    internal fun discardUnopened() {
        if (!open) return
        open = false
        feedback.state.invalidateForRender()
        tasks.close()
    }

    fun refresh(): PaperMenuSessionResult = render(requireCurrent = true)

    fun pageState(): MenuPageState? = renderedPageState

    fun setPage(index: Int): PaperMenuSessionResult {
        require(index >= 0) { "Menu page index must be non-negative" }
        val state = renderedPageState ?: return PaperMenuSessionResult.NO_PAGINATION
        val target = state.at(index).pageIndex
        if (target == state.pageIndex) return PaperMenuSessionResult.UNCHANGED
        requestedPage = target
        return render(requireCurrent = true)
    }

    fun nextPage(): PaperMenuSessionResult {
        val state = renderedPageState ?: return PaperMenuSessionResult.NO_PAGINATION
        if (!state.hasNext) return PaperMenuSessionResult.UNCHANGED
        requestedPage = state.next().pageIndex
        return render(requireCurrent = true)
    }

    fun previousPage(): PaperMenuSessionResult {
        val state = renderedPageState ?: return PaperMenuSessionResult.NO_PAGINATION
        if (!state.hasPrevious) return PaperMenuSessionResult.UNCHANGED
        requestedPage = state.previous().pageIndex
        return render(requireCurrent = true)
    }

    fun showFeedback(
        element: MenuElementId,
        delayTicks: Long,
        item: ItemStack,
    ): PaperMenuSessionResult {
        requirePrimaryThread()
        require(delayTicks >= 1) { "Feedback delay must be positive" }
        require(!item.type.isAir) { "Feedback item cannot be air" }
        if (!open) return PaperMenuSessionResult.CLOSED
        if (!isCurrent()) return PaperMenuSessionResult.STALE_GENERATION
        val slot = layout.slot(element)
        val token = feedback.state.show(element, delayTicks)
        replace(slot.index, item, null, null)
        gui.update()
        tasks.runLater(delayTicks) { restoreFeedback(token) }
        return PaperMenuSessionResult.RENDERED
    }

    internal fun handleClick(event: InventoryClickEvent) {
        requirePrimaryThread()
        if (!open) return
        if (!isCurrent()) {
            feedback.state.invalidateForGeneration(catalogs.current().generation)
            return
        }
        if (event.rawSlot !in 0 until inventory.size) return
        gui.click(event)
    }

    fun close(): PaperMenuSessionResult {
        requirePrimaryThread()
        if (!open) return PaperMenuSessionResult.CLOSED
        open = false
        feedback.state.invalidateForRender()
        tasks.close()
        onClosed(this)
        if (player.openInventory.topInventory === inventory) player.closeInventory()
        return PaperMenuSessionResult.CLOSED
    }

    private fun render(requireCurrent: Boolean): PaperMenuSessionResult {
        requirePrimaryThread()
        if (!open) return PaperMenuSessionResult.CLOSED
        if (requireCurrent && !isCurrent()) {
            feedback.state.invalidateForGeneration(catalogs.current().generation)
            return PaperMenuSessionResult.STALE_GENERATION
        }
        val content = contentProvider()
        val prepared = prepareContent(content)
        feedback.state.invalidateForRender()
        gui.setTitle(ComponentHolder.of(content.title))
        backgroundPane.clear()
        contentPane.clear()
        content.background?.let { background ->
            backgroundPane.fillWith(background.clone(), Consumer { }, plugin)
        }
        prepared.fixed.forEach { (element, entry) ->
            layout.elements.getValue(element).slots.forEach { slot ->
                val target = PaperMenuClickTarget.Element(element)
                val clickable = layout.elements.getValue(element).kind == MenuElementKind.BUTTON
                replace(slot.index, entry.item, entry.takeIf { clickable }, target)
            }
        }
        prepared.regions.forEach { (region, entries) ->
            val slots = layout.regions.getValue(region).slots
            entries.forEachIndexed { visibleIndex, indexed ->
                val target = PaperMenuClickTarget.RegionEntry(region, indexed.index)
                replace(slots[visibleIndex].index, indexed.value.item, indexed.value, target)
            }
        }
        renderedPageState = prepared.pageState
        gui.update()
        return PaperMenuSessionResult.RENDERED
    }

    private fun prepareContent(content: PaperMenuContent): PreparedContent {
        val unknownElements = content.elements.keys - layout.elements.keys
        if (unknownElements.isNotEmpty()) throw PaperMenuContentException("Menu '$menuId' has unknown elements: $unknownElements")
        val unknownRegions = content.regions.keys - layout.regions.keys
        if (unknownRegions.isNotEmpty()) throw PaperMenuContentException("Menu '$menuId' has unknown regions: $unknownRegions")

        var page: MenuPageState? = null
        val pagination = layout.pagination
        val preparedRegions = linkedMapOf<MenuRegionId, List<IndexedValue<PaperMenuEntry>>>()
        content.regions.forEach { (id, entries) ->
            val regionLayout = layout.regions.getValue(id)
            if (regionLayout.kind != MenuRegionKind.CONTENT && entries.isNotEmpty()) {
                throw PaperMenuContentException("Menu '$menuId' group region '$id' cannot receive dynamic entries")
            }
            val capacity = regionLayout.slots.size
            if (pagination?.region == id) {
                val currentPage = pageState(entries.size, capacity, requestedPage)
                page = currentPage
                requestedPage = currentPage.pageIndex
                val offset = currentPage.pageIndex * capacity
                preparedRegions[id] = currentPage.slice(entries).mapIndexed { index, entry -> IndexedValue(offset + index, entry) }
            } else {
                if (entries.size > capacity) {
                    throw PaperMenuContentException("Menu '$menuId' region '$id' has ${entries.size} entries for $capacity slots")
                }
                preparedRegions[id] = entries.mapIndexed(::IndexedValue)
            }
        }
        if (pagination != null && pagination.region !in content.regions) {
            val currentPage = pageState(0, layout.regions.getValue(pagination.region).slots.size, requestedPage)
            page = currentPage
            requestedPage = currentPage.pageIndex
            preparedRegions[pagination.region] = emptyList()
        }
        return PreparedContent(content.elements, preparedRegions, page)
    }

    private fun replace(
        index: Int,
        item: ItemStack,
        entry: PaperMenuEntry?,
        target: PaperMenuClickTarget?,
    ) {
        val action = if (entry?.enabled == true && target != null) {
            Consumer<InventoryClickEvent> { event ->
                if (!processedEvents.add(event) || event.click !in entry.acceptedClicks ||
                    event.whoClicked.uniqueId != player.uniqueId || !isCurrent()
                ) return@Consumer
                entry.onClick.handle(PaperMenuClickContext(this, player, target, event))
            }
        } else {
            null
        }
        val guiItem = if (action == null) GuiItem(item.clone(), plugin) else GuiItem(item.clone(), action, plugin)
        contentPane.addItem(guiItem, index % 9, index / 9)
    }

    private fun restoreFeedback(token: MenuFeedbackToken) {
        requirePrimaryThread()
        if (!open || !isCurrent()) return
        val element = feedback.state.expire(token, token.expiresAtTick) ?: return
        val entry = contentProvider().elements[element] ?: return
        val layoutElement = layout.elements[element] ?: return
        if (layoutElement.slots.size != 1) return
        replace(layoutElement.slots.single().index, entry.item, entry.takeIf { layoutElement.kind == MenuElementKind.BUTTON }, PaperMenuClickTarget.Element(element))
        gui.update()
    }

    private fun isCurrent(): Boolean = catalogs.current().generation == generation

    private fun requirePrimaryThread() {
        check(Bukkit.isPrimaryThread()) { "Paper menus must be used on the primary server thread" }
    }

    private data class PreparedContent(
        val fixed: Map<MenuElementId, PaperMenuEntry>,
        val regions: Map<MenuRegionId, List<IndexedValue<PaperMenuEntry>>>,
        val pageState: MenuPageState?,
    )
}
