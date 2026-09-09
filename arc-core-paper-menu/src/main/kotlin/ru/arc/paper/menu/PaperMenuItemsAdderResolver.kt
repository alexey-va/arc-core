package ru.arc.paper.menu

import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack

/**
 * Resolves `itemsadder:pack/item` through the consumer's optional ItemsAdder hook.
 * The consumer supplies `CustomStack.getInstance(id)?.itemStack` as [lookup] and
 * a live plugin-enabled check as [isAvailable]; core has no ItemsAdder dependency.
 * A bare key is passed through for compatibility with existing menu definitions.
 *
 * Call on the Paper primary thread. Availability is checked on every resolution,
 * before touching the optional API. Exceptions propagate to [PaperMenuItemFactory],
 * which owns fallback diagnostics and clones the returned stack before editing it.
 * This adapter neither caches items nor owns the provider's lifecycle.
 */
class PaperMenuItemsAdderResolver(
    private val isAvailable: () -> Boolean,
    private val lookup: (String) -> ItemStack?,
) : PaperMenuExternalItemResolver {
    override fun resolve(id: NamespacedKey): PaperMenuExternalItemResult {
        if (id.namespace != "itemsadder" || !isAvailable()) return PaperMenuExternalItemResult.Missing
        return lookup(id.key.replaceFirst('/', ':'))?.let(PaperMenuExternalItemResult::Resolved)
            ?: PaperMenuExternalItemResult.Missing
    }
}
