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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.function.Consumer

enum class PaperMenuSessionResult {
    RENDERED,
    QUEUED,
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
    private var renderedTitle: net.kyori.adventure.text.Component? = null
    private var renderedBackground: ItemStack? = null
    private var renderedSlots: Map<Int, RenderedSlot> = emptyMap()
    private var preparedOnce = false
    private var refreshQueued = false
    private var fullRenderCount = 0
    private var slotUpdateCount = 0
    private var open = true
    private var lastRevision: String = ""
    private val visitId = java.util.UUID.randomUUID().toString()

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
        observe("open")
    }

    internal fun discardUnopened() {
        if (!open) return
        open = false
        feedback.state.invalidateForRender()
        tasks.close()
    }

    fun refresh(): PaperMenuSessionResult = render(requireCurrent = true)

    /** Coalesces frequent refresh requests and renders once on the next tick. */
    fun requestRefresh(): PaperMenuSessionResult {
        requirePrimaryThread()
        if (!open) return PaperMenuSessionResult.CLOSED
        if (!isCurrent()) return PaperMenuSessionResult.STALE_GENERATION
        if (refreshQueued) return PaperMenuSessionResult.UNCHANGED
        refreshQueued = true
        tasks.runLater(1) {
            refreshQueued = false
            refresh()
        }
        return PaperMenuSessionResult.QUEUED
    }

    internal fun renderStats(): PaperMenuRenderStats = PaperMenuRenderStats(fullRenderCount, slotUpdateCount)

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
        val feedbackSlot = RenderedSlot(item.clone(), null, null)
        renderedSlots = renderedSlots + (slot.index to feedbackSlot)
        updateSlot(slot.index, feedbackSlot)
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

    fun close(): PaperMenuSessionResult = close(PaperMenuCloseReason.CENSORED)

    internal fun close(reason: PaperMenuCloseReason): PaperMenuSessionResult {
        requirePrimaryThread()
        if (!open) return PaperMenuSessionResult.CLOSED
        open = false
        feedback.state.invalidateForRender()
        tasks.close()
        onClosed(this)
        observe(PaperMenuObservationEvent.Kind.CLOSE, mapOf("reason" to reason.wire))
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
        val desiredSlots = desiredSlots(prepared)
        val pageChanged = prepared.pageState != renderedPageState
        val requiresFullRender =
            !preparedOnce || content.title != renderedTitle || !sameItem(content.background, renderedBackground)
        val changedSlots = if (requiresFullRender) {
            desiredSlots.keys
        } else {
            (renderedSlots.keys + desiredSlots.keys).filterTo(linkedSetOf()) { index ->
                !sameItem(renderedSlots[index]?.item, desiredSlots[index]?.item)
            }
        }

        feedback.state.invalidateForRender()
        renderedTitle = content.title
        renderedBackground = content.background?.clone()
        renderedSlots = desiredSlots
        renderedPageState = prepared.pageState
        lastRevision = revision(prepared)

        if (requiresFullRender) {
            gui.setTitle(ComponentHolder.of(content.title))
            backgroundPane.clear()
            contentPane.clear()
            content.background?.let { background ->
                backgroundPane.fillWith(background.clone(), Consumer { }, plugin)
            }
            desiredSlots.forEach { (index, slot) -> addPaneItem(index, slot) }
            gui.update()
            preparedOnce = true
            fullRenderCount++
            observe("render")
            return PaperMenuSessionResult.RENDERED
        }

        changedSlots.forEach { index -> updateSlot(index, desiredSlots[index]) }
        slotUpdateCount += changedSlots.size
        if (changedSlots.isEmpty() && !pageChanged) return PaperMenuSessionResult.UNCHANGED
        observe("render")
        return PaperMenuSessionResult.RENDERED
    }

    private fun desiredSlots(prepared: PreparedContent): Map<Int, RenderedSlot> = buildMap {
        prepared.fixed.forEach { (element, entry) ->
            layout.elements.getValue(element).slots.forEach { slot ->
                val clickable = layout.elements.getValue(element).kind == MenuElementKind.BUTTON
                put(
                    slot.index,
                    RenderedSlot(entry.item.clone(), entry.takeIf { clickable }, PaperMenuClickTarget.Element(element)),
                )
            }
        }
        prepared.regions.forEach { (region, entries) ->
            val slots = layout.regions.getValue(region).slots
            entries.forEachIndexed { visibleIndex, indexed ->
                put(
                    slots[visibleIndex].index,
                    RenderedSlot(indexed.value.item.clone(), indexed.value, PaperMenuClickTarget.RegionEntry(region, indexed.index)),
                )
            }
        }
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

    private fun addPaneItem(index: Int, slot: RenderedSlot): GuiItem {
        val action = if (slot.entry?.enabled == true && slot.target != null) {
            Consumer<InventoryClickEvent> { event -> dispatch(index, event) }
        } else null
        val guiItem = if (action == null) GuiItem(slot.item.clone(), plugin) else GuiItem(slot.item.clone(), action, plugin)
        contentPane.addItem(guiItem, index % 9, index / 9)
        return guiItem
    }

    private fun updateSlot(index: Int, slot: RenderedSlot?) {
        contentPane.removeItem(index % 9, index / 9)
        if (slot == null) {
            inventory.setItem(index, renderedBackground?.clone())
            return
        }
        val guiItem = addPaneItem(index, slot)
        val renderedItem = guiItem.copy().also { it.applyUUID() }
        inventory.setItem(index, renderedItem.item.clone())
    }

    private fun dispatch(index: Int, event: InventoryClickEvent) {
        val slot = renderedSlots[index] ?: return
        val entry = slot.entry ?: return
        val target = slot.target ?: return
        if (event.whoClicked.uniqueId != player.uniqueId || !isCurrent() || !processedEvents.add(event)) return
        val button = targetKey(target)
        if (!entry.enabled || event.click !in entry.acceptedClicks) {
            observe("blocked", mapOf("button" to button))
            return
        }
        val context = PaperMenuClickContext(this, player, target, event)
        val transfer = entry.transfer
        observe("click", mapOf("button" to button))
        if (transfer == null) {
            entry.onClick.handle(context)
        } else if (event.action in SAFE_MENU_TRANSFER_ACTIONS &&
            transfer.handle(context) == PaperMenuTransferDecision.ALLOW
        ) {
            event.isCancelled = false
        }
    }

    private fun restoreFeedback(token: MenuFeedbackToken) {
        requirePrimaryThread()
        if (!open || !isCurrent()) return
        val element = feedback.state.expire(token, token.expiresAtTick) ?: return
        val entry = contentProvider().elements[element] ?: return
        val layoutElement = layout.elements[element] ?: return
        if (layoutElement.slots.size != 1) return
        val index = layoutElement.slots.single().index
        val restored = RenderedSlot(
            entry.item.clone(),
            entry.takeIf { layoutElement.kind == MenuElementKind.BUTTON },
            PaperMenuClickTarget.Element(element),
        )
        renderedSlots = renderedSlots + (index to restored)
        updateSlot(index, restored)
    }

    private fun isCurrent(): Boolean = catalogs.current().generation == generation

    private fun observe(phase: String, extra: Map<String, Any> = emptyMap()) {
        val payload = linkedMapOf<String, Any>(
            "protocol" to 1,
            "owner" to plugin.name,
            "surface" to menuId.value,
            "visitId" to visitId,
            "playerId" to player.uniqueId.toString(),
            "revision" to lastRevision,
            "phase" to phase,
        )
        val buttons = linkedMapOf<String, Int>()
        renderedSlots.entries.sortedBy { it.key }.forEach { (slot, rendered) ->
            if (rendered.entry?.enabled != true || rendered.target == null) return@forEach
            buttons.putIfAbsent(targetKey(rendered.target), slot)
        }
        payload["buttons"] = buttons
        payload.putAll(extra)
        plugin.server.pluginManager.callEvent(PaperMenuObservationEvent(PaperMenuObservationEvent.Kind.fromPhase(phase), payload))
    }

    private fun revision(prepared: PreparedContent): String {
        val canonical = buildString {
            append(layout.id).append('|').append(layout.rows)
            layout.elements.toSortedMap(compareBy<MenuElementId> { it.value }).forEach { (id, element) ->
                append("|e:").append(id).append(':').append(element.kind)
                element.slots.forEach { append(',').append(it.index) }
            }
            layout.regions.toSortedMap(compareBy<MenuRegionId> { it.value }).forEach { (id, region) ->
                append("|r:").append(id).append(':').append(region.kind)
                region.slots.forEach { append(',').append(it.index) }
            }
            prepared.fixed.keys.filter { layout.elements.getValue(it).kind == MenuElementKind.BUTTON }
                .sortedBy { it.value }.forEach { append("|a:").append(it) }
            prepared.regions.keys.sortedBy { it.value }.forEach { append("|g:").append(it) }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun targetKey(target: PaperMenuClickTarget): String = when (target) {
        is PaperMenuClickTarget.Element -> target.id.value
        is PaperMenuClickTarget.RegionEntry -> "region:${target.region.value}"
    }

    private fun requirePrimaryThread() {
        check(Bukkit.isPrimaryThread()) { "Paper menus must be used on the primary server thread" }
    }

    private data class PreparedContent(
        val fixed: Map<MenuElementId, PaperMenuEntry>,
        val regions: Map<MenuRegionId, List<IndexedValue<PaperMenuEntry>>>,
        val pageState: MenuPageState?,
    )

    private data class RenderedSlot(
        val item: ItemStack,
        val entry: PaperMenuEntry?,
        val target: PaperMenuClickTarget?,
    )

    private fun sameItem(left: ItemStack?, right: ItemStack?): Boolean =
        when {
            left == null || right == null -> left == null && right == null
            else -> left.amount == right.amount && left.isSimilar(right)
        }
}

internal data class PaperMenuRenderStats(val fullRenders: Int, val slotUpdates: Int)
