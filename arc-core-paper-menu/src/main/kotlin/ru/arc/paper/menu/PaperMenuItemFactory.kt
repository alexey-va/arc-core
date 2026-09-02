package ru.arc.paper.menu

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.inventory.ItemStack

class PaperMenuItemFactory(
    private val externalItems: PaperMenuExternalItemResolver? = null,
    private val diagnostics: (String) -> Unit = {},
) {
    fun create(
        template: PaperMenuItemTemplate,
        name: Component,
        lore: List<Component>,
    ): ItemStack {
        val item = when (val source = template.source) {
            is PaperMenuItemSource.MaterialItem -> ItemStack.of(source.material)
            is PaperMenuItemSource.ExternalItem -> resolveExternal(source, template)
        }
        item.amount = template.amount
        item.editMeta { meta ->
            meta.displayName(nonItalic(name))
            meta.lore(lore.map(::nonItalic))
            applyCustomModelData(meta, template.customModelData)
            meta.setEnchantmentGlintOverride(template.glint)
            meta.isHideTooltip = template.hideTooltip
            if (template.itemFlags.isNotEmpty()) meta.addItemFlags(*template.itemFlags.toTypedArray())
        }
        return item
    }

    private fun resolveExternal(
        source: PaperMenuItemSource.ExternalItem,
        template: PaperMenuItemTemplate,
    ): ItemStack {
        val result = externalItems?.let { resolver ->
            runCatching { resolver.resolve(source.id) }
                .getOrElse { PaperMenuExternalItemResult.Failed("external-resolver-failed") }
        } ?: PaperMenuExternalItemResult.Failed("external-resolver-unavailable")
        return when (result) {
            is PaperMenuExternalItemResult.Resolved -> result.item.clone()
            PaperMenuExternalItemResult.Missing -> fallback(template, "external-item-missing")
            is PaperMenuExternalItemResult.Failed -> fallback(template, result.diagnosticKey)
        }
    }

    private fun fallback(template: PaperMenuItemTemplate, diagnostic: String): ItemStack {
        diagnostics(diagnostic.take(MAX_DIAGNOSTIC_LENGTH).ifBlank { "external-item-failed" })
        return ItemStack.of(template.fallbackMaterial)
    }

    private fun nonItalic(component: Component): Component =
        component.decoration(TextDecoration.ITALIC, false)

    @Suppress("DEPRECATION")
    private fun applyCustomModelData(meta: org.bukkit.inventory.meta.ItemMeta, value: Int?) {
        if (value != null) meta.setCustomModelData(value)
    }

    companion object {
        const val MAX_DIAGNOSTIC_LENGTH = 64
    }
}
