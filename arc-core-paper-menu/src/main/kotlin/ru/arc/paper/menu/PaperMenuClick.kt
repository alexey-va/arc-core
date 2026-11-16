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
) {
    internal var attempt: PaperMenuAttempt? = null

    fun reportOutcome(outcome: String) = reportOutcome(outcome, null, null)

    fun reportOutcome(outcome: String, reason: String?) = reportOutcome(outcome, reason, null)

    /**
     * Reports a known `requested`, `succeeded`, `rejected` or `failed` outcome for this click.
     * Call on Paper's primary thread, including asynchronous completions. Other threads are ignored.
     * Use a stable reason code and optional logical target ID; never pass text, input or exception messages.
     */
    fun reportOutcome(outcome: String, reason: String?, target: String?) {
        attempt?.reportOutcome(outcome, reason, target)
    }
}
