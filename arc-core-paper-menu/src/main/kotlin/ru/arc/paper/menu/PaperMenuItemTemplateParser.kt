package ru.arc.paper.menu

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemFlag
import ru.arc.config.Config

object PaperMenuItemTemplateParser {
    fun require(config: Config, root: String): Map<String, PaperMenuItemTemplate> =
        when (val result = parse(config, root)) {
            is PaperMenuItemTemplateLoadResult.Loaded -> result.templates
            is PaperMenuItemTemplateLoadResult.Rejected -> throw PaperMenuItemTemplateException(result.issues)
        }

    fun parse(config: Config, root: String): PaperMenuItemTemplateLoadResult {
        require(root.isNotBlank()) { "Template root must not be blank" }
        val issues = mutableListOf<PaperMenuItemTemplateIssue>()
        val templates = linkedMapOf<String, PaperMenuItemTemplate>()
        config.keys(root).forEach { id ->
            val path = "$root.$id"
            val start = issues.size
            val hasMaterial = config.exists("$path.material")
            val hasExternal = config.exists("$path.external-item")
            if (hasMaterial == hasExternal) {
                issues += issue(id, PaperMenuItemTemplateIssueCode.INVALID_SOURCE, "source", "declare exactly one of material or external-item")
            }

            val source = when {
                hasMaterial -> parseMaterial(config.stringOrNull("$path.material"), id, "material", issues)
                    ?.let(PaperMenuItemSource::MaterialItem)
                hasExternal -> parseExternal(config.stringOrNull("$path.external-item"), id, issues)
                    ?.let(PaperMenuItemSource::ExternalItem)
                else -> null
            }
            val fallback = if (config.exists("$path.fallback-material")) {
                parseMaterial(config.stringOrNull("$path.fallback-material"), id, "fallback-material", issues)
            } else {
                Material.BARRIER
            }
            val amount = config.intOrNull("$path.amount") ?: 1
            if (amount !in 1..99) {
                issues += issue(id, PaperMenuItemTemplateIssueCode.INVALID_AMOUNT, "amount", "must be between 1 and 99")
            }
            val customModelData = config.intOrNull("$path.custom-model-data")
            if (customModelData != null && customModelData < 0) {
                issues += issue(id, PaperMenuItemTemplateIssueCode.INVALID_CUSTOM_MODEL_DATA, "custom-model-data", "must be non-negative")
            }
            val flags = linkedSetOf<ItemFlag>()
            config.stringListOrNull("$path.item-flags").orEmpty().forEach { raw ->
                runCatching { ItemFlag.valueOf(raw.uppercase().replace('-', '_')) }
                    .onSuccess(flags::add)
                    .onFailure {
                        issues += issue(id, PaperMenuItemTemplateIssueCode.INVALID_ITEM_FLAG, "item-flags", "unknown flag '$raw'")
                    }
            }
            val glint = config.booleanOrNull("$path.glint")
            val hideTooltip = config.booleanOrNull("$path.hide-tooltip") ?: false

            if (issues.size == start && source != null && fallback != null) {
                templates[id] = PaperMenuItemTemplate(
                    source = source,
                    fallbackMaterial = fallback,
                    amount = amount,
                    customModelData = customModelData,
                    glint = glint,
                    hideTooltip = hideTooltip,
                    itemFlags = flags,
                )
            }
        }
        return if (issues.isEmpty()) {
            PaperMenuItemTemplateLoadResult.Loaded(templates)
        } else {
            PaperMenuItemTemplateLoadResult.Rejected(issues)
        }
    }

    private fun parseMaterial(
        raw: String?,
        template: String,
        path: String,
        issues: MutableList<PaperMenuItemTemplateIssue>,
    ): Material? {
        val material = raw?.let(Material::matchMaterial)
        if (material == null || !material.isItem || material.isAir) {
            issues += issue(template, PaperMenuItemTemplateIssueCode.INVALID_MATERIAL, path, "must name a non-air item material")
            return null
        }
        return material
    }

    private fun parseExternal(
        raw: String?,
        template: String,
        issues: MutableList<PaperMenuItemTemplateIssue>,
    ): NamespacedKey? {
        val parsed = raw?.let(NamespacedKey::fromString)
        if (parsed == null || raw.count { it == ':' } != 1) {
            issues += issue(template, PaperMenuItemTemplateIssueCode.INVALID_EXTERNAL_ID, "external-item", "must be an explicit namespace:key ID")
            return null
        }
        return parsed
    }

    private fun issue(
        template: String,
        code: PaperMenuItemTemplateIssueCode,
        path: String,
        reason: String,
    ) = PaperMenuItemTemplateIssue(code, template, path, reason)
}
