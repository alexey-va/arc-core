package ru.arc.paper.menu

import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuRegionId

sealed interface PaperMenuClickTarget {
    data class Element(val id: MenuElementId) : PaperMenuClickTarget
    data class RegionEntry(val region: MenuRegionId, val index: Int) : PaperMenuClickTarget
}

data class PaperMenuClickContext(
    val session: PaperMenuSession,
    val player: Player,
    val target: PaperMenuClickTarget,
    val event: InventoryClickEvent,
)
