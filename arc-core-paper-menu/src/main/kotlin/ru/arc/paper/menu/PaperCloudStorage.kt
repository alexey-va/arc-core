package ru.arc.paper.menu

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.menu.MenuElementId

/**
 * Backing state of a cloud chest. Calls are synchronous on Paper's primary thread.
 * Snapshots must be detached and preserve null slots (use null, never AIR, for
 * empty cells). compareAndSet must atomically
 * validate the complete expected snapshot and commit all replacements, or change
 * nothing. The consumer owns permissions, persistence and recovery guarantees;
 * a successful return is the boundary after which the player inventory is changed.
 */
interface PaperCloudStorage {
    fun snapshot(): List<ItemStack?>
    fun compareAndSet(expected: List<ItemStack?>, replacement: List<ItemStack?>): Boolean
}

enum class PaperCloudStorageFailure { FULL, STALE, REJECTED }

data class PaperCloudStorageButton(val item: ItemStack, val action: (Player) -> Unit)

/**
 * A configured storage region with optional non-transferable decorations and
 * fixed navigation buttons. slotOrder permutes logical region offsets; it never
 * introduces physical slots outside the YAML region. Decorations occupy padding
 * beyond the backing snapshot. Background is rendered outside the storage region.
 */
data class PaperCloudStorageContent(
    val title: Component,
    val allowDeposits: Boolean = true,
    val slotOrder: List<Int>? = null,
    val decorations: Map<Int, ItemStack> = emptyMap(),
    val buttons: Map<MenuElementId, PaperCloudStorageButton> = emptyMap(),
    val background: ItemStack? = null,
    val onFailure: (Player, PaperCloudStorageFailure) -> Unit = { _, _ -> },
)

internal data class CloudStorageMove(val contents: List<ItemStack?>, val moved: Int)

/** Detached vanilla-style quick move: merge existing stacks before empty slots. */
internal fun planCloudStorageMove(contents: List<ItemStack?>, source: ItemStack, order: List<Int>): CloudStorageMove {
    val result = contents.map { it?.clone() }.toMutableList()
    var remaining = source.amount
    for (merge in listOf(true, false)) {
        for (slot in order) {
            val target = result[slot]?.takeUnless { it.type.isAir }
            if (merge && target?.isSimilar(source) != true || !merge && target != null) continue
            val capacity = ((target?.maxStackSize ?: source.maxStackSize) - (target?.amount ?: 0)).coerceAtLeast(0)
            val moved = minOf(remaining, capacity)
            if (moved <= 0) continue
            result[slot] = (target ?: source).clone().also { it.amount = (target?.amount ?: 0) + moved }
            remaining -= moved
            if (remaining == 0) return CloudStorageMove(result, source.amount)
        }
    }
    return CloudStorageMove(result, source.amount - remaining)
}
