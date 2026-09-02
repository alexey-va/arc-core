package ru.arc.paper.menu

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemFlag

sealed interface PaperMenuItemSource {
    data class MaterialItem(val material: Material) : PaperMenuItemSource {
        init {
            require(material.isItem && !material.isAir) { "Menu material must be a non-air item" }
        }
    }

    data class ExternalItem(val id: NamespacedKey) : PaperMenuItemSource
}

data class PaperMenuItemTemplate(
    val source: PaperMenuItemSource,
    val fallbackMaterial: Material = Material.BARRIER,
    val amount: Int = 1,
    val customModelData: Int? = null,
    val glint: Boolean? = null,
    val hideTooltip: Boolean = false,
    val itemFlags: Set<ItemFlag> = emptySet(),
    val text: PaperMenuItemTextTemplate? = null,
) {
    init {
        require(fallbackMaterial.isItem && !fallbackMaterial.isAir) { "Fallback material must be a non-air item" }
        require(amount in 1..99) { "Menu item amount must be between 1 and 99" }
        require(customModelData == null || customModelData >= 0) { "Custom model data must be non-negative" }
    }
}

enum class PaperMenuItemTemplateIssueCode {
    INVALID_SOURCE,
    INVALID_MATERIAL,
    INVALID_EXTERNAL_ID,
    INVALID_AMOUNT,
    INVALID_CUSTOM_MODEL_DATA,
    INVALID_ITEM_FLAG,
    INVALID_VALUE,
    INVALID_TEXT_NAME,
    INVALID_TEXT_LORE,
    UNKNOWN_TEXT_TAG,
    UNKNOWN_TEXT_FLAG,
    UNKNOWN_TEXT_REPEAT,
}

data class PaperMenuItemTemplateIssue(
    val code: PaperMenuItemTemplateIssueCode,
    val template: String,
    val path: String,
    val reason: String,
)

class PaperMenuItemTemplateException(
    val issues: List<PaperMenuItemTemplateIssue>,
) : IllegalArgumentException(
    issues.joinToString(prefix = "Invalid Paper menu item templates: ", separator = "; ") {
        "${it.template}.${it.path}: ${it.reason}"
    },
) {
    init {
        require(issues.isNotEmpty()) { "Template exception requires at least one issue" }
    }
}

sealed interface PaperMenuItemTemplateLoadResult {
    data class Loaded(val templates: Map<String, PaperMenuItemTemplate>) : PaperMenuItemTemplateLoadResult
    data class Rejected(val issues: List<PaperMenuItemTemplateIssue>) : PaperMenuItemTemplateLoadResult {
        init {
            require(issues.isNotEmpty()) { "Rejected template candidate requires at least one issue" }
        }
    }
}
