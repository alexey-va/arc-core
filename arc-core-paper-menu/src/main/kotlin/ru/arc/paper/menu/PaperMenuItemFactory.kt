package ru.arc.paper.menu

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.inventory.ItemStack

class PaperMenuItemFactory(
    private val externalItems: PaperMenuExternalItemResolver? = null,
    private val diagnostics: (String) -> Unit = {},
) {
    private val miniMessage = MiniMessage.miniMessage()

    /** Renders both the safe item source and all configured text. */
    fun create(
        template: PaperMenuItemTemplate,
        context: PaperMenuItemRenderContext,
    ): ItemStack {
        val text = requireNotNull(template.text) { "Paper menu item template has no configured name/lore" }
        val missing = text.nameReferencedValues - context.values.keys
        require(missing.isEmpty()) { "Menu item text is missing values: ${missing.sorted()}" }
        val globalResolver = resolver(context.values)
        val name = miniMessage.deserialize(text.name, globalResolver)
        val lore = buildList {
            text.lore.forEach { entry ->
                if (!entry.isVisible(context.flags)) return@forEach
                when (entry) {
                    is PaperMenuLoreTemplateEntry.Text -> {
                        requireValues(entry.referencedValues, context.values.keys)
                        add(miniMessage.deserialize(entry.text, globalResolver))
                    }
                    is PaperMenuLoreTemplateEntry.Repeat -> {
                        context.repeats[entry.repeat].orEmpty().forEachIndexed { index, row ->
                            requireValues(entry.referencedValues, context.values.keys)
                            val rowMissing = entry.referencedRowValues - row.keys
                            require(rowMissing.isEmpty()) {
                                "Menu item repeat '${entry.repeat}' row $index is missing values: ${rowMissing.sorted()}"
                            }
                            add(miniMessage.deserialize(entry.text, resolver(context.values + row)))
                        }
                    }
                }
            }
        }
        return create(template, name, lore)
    }

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

    private fun resolver(values: Map<String, Component>): TagResolver =
        TagResolver.builder().also { builder ->
            values.forEach { (name, value) -> builder.resolver(Placeholder.component(name, value)) }
        }.build()

    private fun requireValues(required: Set<String>, provided: Set<String>) {
        val missing = required - provided
        require(missing.isEmpty()) { "Menu item text is missing values: ${missing.sorted()}" }
    }

    @Suppress("DEPRECATION")
    private fun applyCustomModelData(meta: org.bukkit.inventory.meta.ItemMeta, value: Int?) {
        if (value != null) meta.setCustomModelData(value)
    }

    companion object {
        const val MAX_DIAGNOSTIC_LENGTH = 64
    }
}

private fun PaperMenuLoreTemplateEntry.isVisible(flags: Set<String>): Boolean =
    whenFlags.all(flags::contains) && unlessFlags.none(flags::contains)
