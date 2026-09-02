package ru.arc.paper.menu

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemFlag
import ru.arc.config.Config

object PaperMenuItemTemplateParser {
    fun require(
        config: Config,
        root: String,
        textContracts: Map<String, PaperMenuTextContract> = emptyMap(),
    ): Map<String, PaperMenuItemTemplate> =
        when (val result = parse(config, root, textContracts)) {
            is PaperMenuItemTemplateLoadResult.Loaded -> result.templates
            is PaperMenuItemTemplateLoadResult.Rejected -> throw PaperMenuItemTemplateException(result.issues)
        }

    fun parse(
        config: Config,
        root: String,
        textContracts: Map<String, PaperMenuTextContract> = emptyMap(),
    ): PaperMenuItemTemplateLoadResult {
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
            val text = parseText(config, path, id, textContracts[id] ?: PaperMenuTextContract(), issues)

            if (issues.size == start && source != null && fallback != null) {
                templates[id] = PaperMenuItemTemplate(
                    source = source,
                    fallbackMaterial = fallback,
                    amount = amount,
                    customModelData = customModelData,
                    glint = glint,
                    hideTooltip = hideTooltip,
                    itemFlags = flags,
                    text = text,
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

    private fun parseText(
        config: Config,
        path: String,
        template: String,
        contract: PaperMenuTextContract,
        issues: MutableList<PaperMenuItemTemplateIssue>,
    ): PaperMenuItemTextTemplate? {
        val hasName = config.exists("$path.name")
        val hasLore = config.exists("$path.lore")
        if (!hasName && !hasLore) return null
        val name = config.stringOrNull("$path.name")
        if (name == null) {
            issues += issue(template, PaperMenuItemTemplateIssueCode.INVALID_TEXT_NAME, "name", "must be a string when lore is configured")
            return null
        }
        val nameRefs = validateText(template, "name", name, contract.values, issues)
        val rawLore = if (hasLore) config.rawValue("$path.lore") else emptyList<Any>()
        if (rawLore !is List<*>) {
            issues += issue(template, PaperMenuItemTemplateIssueCode.INVALID_TEXT_LORE, "lore", "must be a list of strings or mappings")
            return null
        }
        val lore = mutableListOf<PaperMenuLoreTemplateEntry>()
        rawLore.forEachIndexed { index, raw ->
            val entryPath = "lore[$index]"
            val parsed = when (raw) {
                is String -> {
                    val refs = validateText(template, entryPath, raw, contract.values, issues)
                    PaperMenuLoreTemplateEntry.Text(raw, emptySet(), emptySet(), refs)
                }
                is Map<*, *> -> parseLoreMapping(template, entryPath, raw, contract, issues)
                else -> {
                    issues += issue(template, PaperMenuItemTemplateIssueCode.INVALID_TEXT_LORE, entryPath, "must be a string or mapping")
                    null
                }
            }
            if (parsed != null) {
                lore += parsed
            }
        }
        return PaperMenuItemTextTemplate(name, lore, nameRefs)
    }

    private fun parseLoreMapping(
        template: String,
        path: String,
        raw: Map<*, *>,
        contract: PaperMenuTextContract,
        issues: MutableList<PaperMenuItemTemplateIssue>,
    ): PaperMenuLoreTemplateEntry? {
        val text = raw["text"] as? String
        if (text == null) {
            issues += issue(template, PaperMenuItemTemplateIssueCode.INVALID_TEXT_LORE, "$path.text", "must be a string")
            return null
        }
        val whenFlags = parseNames(raw["when"], template, "$path.when", contract.flags, issues)
        val unlessFlags = parseNames(raw["unless"], template, "$path.unless", contract.flags, issues)
        val repeat = raw["repeat"]
        if (repeat == null) {
            val refs = validateText(template, "$path.text", text, contract.values, issues)
            return PaperMenuLoreTemplateEntry.Text(text, whenFlags, unlessFlags, refs)
        }
        if (repeat !is String || repeat !in contract.repeats) {
            issues += issue(
                template,
                PaperMenuItemTemplateIssueCode.UNKNOWN_TEXT_REPEAT,
                "$path.repeat",
                "unknown repeat '$repeat'; available: ${contract.repeats.keys.sorted()}",
            )
            return null
        }
        val rowValues = contract.repeats.getValue(repeat)
        val allValues = contract.values + rowValues
        val refs = validateText(template, "$path.text", text, allValues, issues)
        return PaperMenuLoreTemplateEntry.Repeat(
            repeat,
            text,
            whenFlags,
            unlessFlags,
            refs.intersect(contract.values),
            refs.intersect(rowValues),
        )
    }

    private fun parseNames(
        raw: Any?,
        template: String,
        path: String,
        allowed: Set<String>,
        issues: MutableList<PaperMenuItemTemplateIssue>,
    ): Set<String> {
        val names = when (raw) {
            null -> emptyList()
            is String -> listOf(raw)
            is List<*> -> {
                if (raw.any { it !is String }) {
                    issues += issue(template, PaperMenuItemTemplateIssueCode.INVALID_TEXT_LORE, path, "must contain only flag names")
                }
                raw.filterIsInstance<String>()
            }
            else -> {
                issues += issue(template, PaperMenuItemTemplateIssueCode.INVALID_TEXT_LORE, path, "must be a flag name or list of flag names")
                emptyList()
            }
        }.toSet()
        names.filterNot(allowed::contains).forEach { name ->
            issues += issue(
                template,
                PaperMenuItemTemplateIssueCode.UNKNOWN_TEXT_FLAG,
                path,
                "unknown flag '$name'; available: ${allowed.sorted()}",
            )
        }
        return names.intersect(allowed)
    }

    private fun validateText(
        template: String,
        path: String,
        text: String,
        allowedValues: Set<String>,
        issues: MutableList<PaperMenuItemTemplateIssue>,
    ): Set<String> {
        val names = TAG_REFERENCE.findAll(text).map { it.groupValues[1].lowercase() }.toSet()
        val unknown = names - allowedValues - STANDARD_TAG_NAMES
        unknown.forEach { name ->
            issues += issue(
                template,
                PaperMenuItemTemplateIssueCode.UNKNOWN_TEXT_TAG,
                path,
                "unknown value tag '$name'; available: ${allowedValues.sorted()}",
            )
        }
        return names.intersect(allowedValues)
    }

    private fun Config.rawValue(path: String): Any? {
        var current: Any? = map
        path.split('.').forEach { segment ->
            current = (current as? Map<*, *>)?.get(segment) ?: return null
        }
        return current
    }

    private val TAG_REFERENCE = Regex("</?([a-zA-Z0-9_-]{1,64})(?=[>:])")

    // Names provided by Adventure's standard MiniMessage resolver. Dynamic
    // placeholders use the plugin-owned contract above and are never raw text.
    private val STANDARD_TAG_NAMES = setOf(
        "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
        "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
        "color", "colour", "gradient", "transition", "rainbow", "shadow", "shadow_color",
        "bold", "b", "italic", "em", "i", "underlined", "u", "strikethrough", "st",
        "obfuscated", "obf", "reset", "newline", "br", "font", "key", "keybind", "selector",
        "score", "nbt", "click", "hover", "insertion", "translatable", "translate", "lang", "sprite",
    )
}
