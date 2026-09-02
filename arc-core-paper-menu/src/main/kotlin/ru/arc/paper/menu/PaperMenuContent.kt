package ru.arc.paper.menu

import net.kyori.adventure.text.Component
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.inventory.ItemStack
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuRegionId

fun interface PaperMenuClickHandler {
    fun handle(context: PaperMenuClickContext)
}

enum class PaperMenuTransferDecision {
    ALLOW,
    DENY,
}

/**
 * Atomically approves taking one rendered top-inventory item.
 *
 * The handler runs only for pickup actions from the owning viewer. Placement,
 * cursor swaps, drops, hotbar swaps, creative clones, bottom-inventory moves
 * and drags remain cancelled by the menu runtime. Return [PaperMenuTransferDecision.DENY]
 * whenever durable/domain state could not reserve the item.
 */
fun interface PaperMenuTransferHandler {
    fun handle(context: PaperMenuClickContext): PaperMenuTransferDecision
}

data class PaperMenuEntry(
    val item: ItemStack,
    val enabled: Boolean = true,
    val acceptedClicks: Set<ClickType> = DEFAULT_MENU_CLICKS,
    val onClick: PaperMenuClickHandler = PaperMenuClickHandler {},
    val transfer: PaperMenuTransferHandler? = null,
) {
    init {
        require(!item.type.isAir) { "Menu entries cannot render air" }
        require(acceptedClicks.none(UNSAFE_MENU_CLICKS::contains)) {
            "Menu entry accepts an unsafe inventory click type"
        }
    }
}

internal val SAFE_MENU_TRANSFER_ACTIONS: Set<InventoryAction> = setOf(
    InventoryAction.PICKUP_ALL,
    InventoryAction.PICKUP_HALF,
    InventoryAction.PICKUP_ONE,
    InventoryAction.PICKUP_SOME,
    InventoryAction.MOVE_TO_OTHER_INVENTORY,
)

data class PaperMenuContent(
    val title: Component,
    val background: ItemStack? = null,
    val elements: Map<MenuElementId, PaperMenuEntry> = emptyMap(),
    val regions: Map<MenuRegionId, List<PaperMenuEntry>> = emptyMap(),
) {
    init {
        require(background?.type?.isAir != true) { "Menu background cannot be air" }
    }
}

val DEFAULT_MENU_CLICKS: Set<ClickType> = setOf(ClickType.LEFT, ClickType.RIGHT)

private val UNSAFE_MENU_CLICKS = setOf(
    ClickType.NUMBER_KEY,
    ClickType.DOUBLE_CLICK,
    ClickType.DROP,
    ClickType.CONTROL_DROP,
    ClickType.CREATIVE,
    ClickType.SWAP_OFFHAND,
    ClickType.UNKNOWN,
)

class PaperMenuContentException(message: String) : IllegalArgumentException(message)
