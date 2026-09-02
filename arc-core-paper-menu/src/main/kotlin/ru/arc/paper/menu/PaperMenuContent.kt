package ru.arc.paper.menu

import net.kyori.adventure.text.Component
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuRegionId

fun interface PaperMenuClickHandler {
    fun handle(context: PaperMenuClickContext)
}

data class PaperMenuEntry(
    val item: ItemStack,
    val enabled: Boolean = true,
    val acceptedClicks: Set<ClickType> = DEFAULT_MENU_CLICKS,
    val onClick: PaperMenuClickHandler = PaperMenuClickHandler {},
) {
    init {
        require(!item.type.isAir) { "Menu entries cannot render air" }
        require(acceptedClicks.none(UNSAFE_MENU_CLICKS::contains)) {
            "Menu entry accepts an unsafe inventory click type"
        }
    }
}

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
