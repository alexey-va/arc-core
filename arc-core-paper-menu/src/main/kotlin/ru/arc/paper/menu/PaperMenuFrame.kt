package ru.arc.paper.menu

import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack
import ru.arc.menu.MenuElementKind
import ru.arc.menu.MenuId
import ru.arc.menu.MenuLayout
import ru.arc.menu.MenuRegionId

/**
 * Mutable render frame for incrementally migrating slot-oriented menu composition.
 *
 * The frame accepts only addresses declared by the validated [MenuLayout] and
 * projects them to semantic [PaperMenuContent]. Inventory Framework and Bukkit
 * inventory ownership remain inside [PaperMenuRuntime]. A frame is render-local:
 * create a new instance whenever the runtime asks its content provider to render.
 */
class PaperMenuFrame private constructor(
    private val layout: MenuLayout,
    val title: Component,
    background: ItemStack?,
    private val addressSpace: AddressSpace,
) {
    private val items = linkedMapOf<Int, ItemStack>()
    private val background = background?.clone()

    /** Number of addresses accepted by [setItem]. */
    val size: Int = when (addressSpace) {
        AddressSpace.Physical -> layout.rows * 9
        is AddressSpace.Region -> layout.region(addressSpace.id).size
    }

    /** Stores a defensive copy, or clears the address when [item] is null or air. */
    fun setItem(slot: Int, item: ItemStack?) {
        require(slot in validAddresses()) {
            "Menu '${layout.id}' frame does not declare slot $slot in its ${addressSpace.description()} address space"
        }
        if (item == null || item.type.isAir) items.remove(slot) else items[slot] = item.clone()
    }

    /**
     * Creates semantic content. [onClick] receives the same physical or logical
     * address that was passed to [setItem]. Empty padding entries never dispatch.
     */
    fun content(onClick: (slot: Int, context: PaperMenuClickContext) -> Unit): PaperMenuContent =
        when (val space = addressSpace) {
            AddressSpace.Physical -> physicalContent(onClick)
            is AddressSpace.Region -> regionContent(space.id, onClick)
        }

    private fun physicalContent(onClick: (Int, PaperMenuClickContext) -> Unit): PaperMenuContent {
        val elements = buildMap {
            layout.elements.forEach { (id, element) ->
                val rendered = element.slots.mapNotNull { items[it.index] }
                if (rendered.isEmpty()) return@forEach
                require(rendered.size == element.slots.size && rendered.drop(1).all { it.isSimilar(rendered.first()) }) {
                    "Menu '${layout.id}' element '$id' must render one identical item in all configured slots"
                }
                val clickSlot = element.slots.first().index
                put(
                    id,
                    PaperMenuEntry(
                        item = rendered.first().clone(),
                        enabled = element.kind == MenuElementKind.BUTTON,
                        onClick = PaperMenuClickHandler { context -> onClick(clickSlot, context) },
                    ),
                )
            }
        }
        val regions = buildMap {
            layout.regions.forEach { (id, region) ->
                val last = region.slots.indexOfLast { items.containsKey(it.index) }
                if (last < 0) return@forEach
                put(id, (0..last).map { offset -> entry(region.slots[offset].index, onClick) })
            }
        }
        return PaperMenuContent(title, background?.clone(), elements, regions)
    }

    private fun regionContent(
        regionId: MenuRegionId,
        onClick: (Int, PaperMenuClickContext) -> Unit,
    ): PaperMenuContent {
        val last = items.keys.maxOrNull() ?: -1
        val entries = if (last < 0) emptyList() else (0..last).map { entry(it, onClick) }
        return PaperMenuContent(title, background?.clone(), regions = mapOf(regionId to entries))
    }

    private fun entry(slot: Int, onClick: (Int, PaperMenuClickContext) -> Unit): PaperMenuEntry {
        val rendered = items[slot]
        if (rendered != null) {
            return PaperMenuEntry(
                item = rendered.clone(),
                onClick = PaperMenuClickHandler { context -> onClick(slot, context) },
            )
        }
        val padding = background ?: throw PaperMenuContentException(
            "Menu '${layout.id}' frame has a sparse region before slot $slot and no background item",
        )
        return PaperMenuEntry(item = padding.clone(), enabled = false)
    }

    private fun validAddresses(): Set<Int> = when (val space = addressSpace) {
        AddressSpace.Physical -> buildSet {
            layout.elements.values.flatMapTo(this) { element -> element.slots.map { it.index } }
            layout.regions.values.flatMapTo(this) { region -> region.slots.map { it.index } }
        }
        is AddressSpace.Region -> layout.region(space.id).indices.toSet()
    }

    private sealed interface AddressSpace {
        data object Physical : AddressSpace
        data class Region(val id: MenuRegionId) : AddressSpace

        fun description(): String = when (this) {
            Physical -> "physical"
            is Region -> "logical region '$id'"
        }
    }

    companion object {
        /** Uses validated physical slot indexes from fixed elements and regions. */
        fun physical(layout: MenuLayout, title: Component, background: ItemStack?): PaperMenuFrame =
            PaperMenuFrame(layout, title, background, AddressSpace.Physical)

        /** Uses zero-based logical indexes within one configured ordered region. */
        fun region(
            layout: MenuLayout,
            region: MenuRegionId,
            title: Component,
            background: ItemStack?,
        ): PaperMenuFrame {
            layout.region(region)
            return PaperMenuFrame(layout, title, background, AddressSpace.Region(region))
        }
    }
}

/** Creates a physical frame from the currently published menu generation. */
fun PaperMenuRuntime.physicalFrame(menu: MenuId, title: Component, background: ItemStack?): PaperMenuFrame =
    PaperMenuFrame.physical(current().catalog.require(menu), title, background)

/** Creates a logical-region frame from the currently published menu generation. */
fun PaperMenuRuntime.regionFrame(
    menu: MenuId,
    region: MenuRegionId,
    title: Component,
    background: ItemStack?,
): PaperMenuFrame = PaperMenuFrame.region(current().catalog.require(menu), region, title, background)
