package ru.arc.paper.menu

import ru.arc.config.Config
import ru.arc.menu.MenuCatalog
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.menu.MenuLayoutParser
import ru.arc.menu.MenuTemplateId

/** One fully validated Paper menu generation: topology and safe item sources. */
data class PaperMenuConfiguration(
    val catalog: MenuCatalog,
    val templates: Map<String, PaperMenuItemTemplate>,
) {
    fun template(id: MenuTemplateId): PaperMenuItemTemplate =
        requireNotNull(templates[id.value]) { "Paper menu template '$id' is not configured" }

    fun template(menu: MenuId, element: MenuElementId): PaperMenuItemTemplate {
        val template = requireNotNull(catalog.require(menu).elements.getValue(element).template) {
            "Paper menu '$menu' element '$element' has no item template"
        }
        return template(template)
    }
}

object PaperMenuConfigurationParser {
    fun require(
        config: Config,
        layoutRoot: String,
        templateRoot: String,
        contracts: Map<MenuId, MenuContract>,
        requiredTemplates: Set<String> = emptySet(),
        textContracts: Map<String, PaperMenuTextContract> = emptyMap(),
    ): PaperMenuConfiguration {
        val catalog = MenuLayoutParser.require(config, layoutRoot, contracts)
        val templates = PaperMenuItemTemplateParser.require(config, templateRoot, textContracts)
        val referenced = catalog.layouts.values.flatMap { layout ->
            listOfNotNull(layout.backgroundTemplate?.value) +
                layout.elements.values.mapNotNull { it.template?.value }
        }.toSet()
        val missing = referenced + requiredTemplates - templates.keys
        require(missing.isEmpty()) { "Paper menu configuration references missing templates: ${missing.sorted()}" }
        return PaperMenuConfiguration(catalog, templates)
    }
}
