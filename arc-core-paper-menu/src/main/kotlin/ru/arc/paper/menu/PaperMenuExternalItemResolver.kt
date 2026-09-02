package ru.arc.paper.menu

import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack

sealed interface PaperMenuExternalItemResult {
    data class Resolved(val item: ItemStack) : PaperMenuExternalItemResult
    data object Missing : PaperMenuExternalItemResult
    data class Failed(val diagnosticKey: String) : PaperMenuExternalItemResult {
        init {
            require(diagnosticKey.isNotBlank()) { "External-item diagnostic key must not be blank" }
        }
    }
}

fun interface PaperMenuExternalItemResolver {
    fun resolve(id: NamespacedKey): PaperMenuExternalItemResult
}
