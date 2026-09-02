package ru.arc.menu

import ru.arc.config.Config

object MenuLayoutParser {
    const val SCHEMA_VERSION = 1

    fun require(
        config: Config,
        root: String,
        contracts: Map<MenuId, MenuContract>,
    ): MenuCatalog = when (val result = parse(config, root, contracts)) {
        is MenuCatalogLoadResult.Loaded -> result.catalog
        is MenuCatalogLoadResult.Rejected -> throw MenuValidationException(result.issues)
    }

    fun parse(
        config: Config,
        root: String,
        contracts: Map<MenuId, MenuContract>,
    ): MenuCatalogLoadResult {
        require(root.isNotBlank()) { "Menu layout root must not be blank" }
        require(contracts.isNotEmpty()) { "At least one menu contract is required" }
        val issues = mutableListOf<MenuValidationIssue>()
        val layouts = linkedMapOf<MenuId, MenuLayout>()
        val configuredKeys = config.keys(root)

        configuredKeys.forEach { rawId ->
            val id = runCatching { MenuId.of(rawId) }.getOrElse {
                issues += issue(rawId, MenuValidationCode.INVALID_ID, root, it.message ?: "invalid menu id")
                return@forEach
            }
            val contract = contracts[id]
            if (contract == null) {
                issues += issue(id, MenuValidationCode.UNKNOWN_REFERENCE, root, "menu has no consumer contract")
                return@forEach
            }
            parseLayout(config, "$root.$rawId", id, contract, issues)?.let { layouts[id] = it }
        }

        contracts.keys.filterNot(layouts::containsKey).filterNot { expected ->
            issues.any { it.menu == expected.value }
        }.forEach { missing ->
            issues += issue(missing, MenuValidationCode.UNKNOWN_REFERENCE, root, "required menu layout is missing")
        }

        return if (issues.isEmpty()) {
            MenuCatalogLoadResult.Loaded(MenuCatalog(layouts = layouts))
        } else {
            MenuCatalogLoadResult.Rejected(issues.toList())
        }
    }

    private fun parseLayout(
        config: Config,
        path: String,
        id: MenuId,
        contract: MenuContract,
        issues: MutableList<MenuValidationIssue>,
    ): MenuLayout? {
        val startIssueCount = issues.size
        val schema = config.intOrNull("$path.schema-version")
        if (schema != SCHEMA_VERSION) {
            issues += issue(
                id,
                if (schema == null) MenuValidationCode.INVALID_VALUE else MenuValidationCode.INVALID_SCHEMA_VERSION,
                "schema-version",
                "must equal $SCHEMA_VERSION",
            )
        }
        val rows = config.intOrNull("$path.rows")
        if (rows == null) {
            issues += issue(id, MenuValidationCode.INVALID_VALUE, "rows", "must be an integer")
        } else if (rows !in 1..MenuSlot.MAX_ROWS) {
            issues += issue(id, MenuValidationCode.INVALID_ROWS, "rows", "must be between 1 and ${MenuSlot.MAX_ROWS}")
        }

        val elements = linkedMapOf<MenuElementId, MenuElementLayout>()
        parseExplicitElements(config, path, id, issues, elements)
        parsePattern(config, path, id, rows, issues, elements)
        val regions = parseRegions(config, path, id, elements, issues)
        val pagination = parsePagination(config, path, id, issues)
        val background = config.stringOrNull("$path.background.template")?.let { raw ->
            parseTemplateId(id, "background.template", raw, issues)
        }

        if (rows == null) return null
        val layout = MenuLayout(id, rows, elements, regions, background, pagination)
        if (issues.size == startIssueCount) issues += layout.validate(contract)
        return layout.takeIf { issues.size == startIssueCount }
    }

    private fun parseExplicitElements(
        config: Config,
        menuPath: String,
        menu: MenuId,
        issues: MutableList<MenuValidationIssue>,
        target: MutableMap<MenuElementId, MenuElementLayout>,
    ) {
        config.keys("$menuPath.elements").forEach { rawId ->
            val id = parseElementId(menu, "elements.$rawId", rawId, issues) ?: return@forEach
            val path = "$menuPath.elements.$rawId"
            val kind = when (config.stringOrNull("$path.kind")?.lowercase() ?: "button") {
                "button" -> MenuElementKind.BUTTON
                "decoration" -> MenuElementKind.DECORATION
                else -> {
                    issues += issue(menu, MenuValidationCode.INVALID_VALUE, "elements.$rawId.kind", "must be button or decoration")
                    return@forEach
                }
            }
            val template = config.stringOrNull("$path.template")?.let { raw ->
                parseTemplateId(menu, "elements.$rawId.template", raw, issues)
            }
            val placements = listOf(
                config.exists("$path.slot"),
                config.exists("$path.position"),
                config.exists("$path.slots"),
            ).count { it }
            if (placements != 1) {
                issues += issue(menu, MenuValidationCode.INVALID_VALUE, "elements.$rawId", "must declare exactly one of slot, position, or slots")
                return@forEach
            }
            val slots = when {
                config.exists("$path.slot") -> parseSingleSlot(config, "$path.slot", menu, "elements.$rawId.slot", issues)
                    ?.let(::listOf)
                config.exists("$path.position") -> parsePosition(config, "$path.position", menu, "elements.$rawId.position", issues)
                    ?.let(::listOf)
                else -> parseSlotList(config, "$path.slots", menu, "elements.$rawId.slots", issues)
            } ?: return@forEach
            if (kind == MenuElementKind.BUTTON && slots.size != 1) {
                issues += issue(menu, MenuValidationCode.INVALID_VALUE, "elements.$rawId.slots", "button must occupy exactly one slot")
                return@forEach
            }
            target[id] = MenuElementLayout(id, slots, kind, template)
        }
    }

    private fun parsePattern(
        config: Config,
        menuPath: String,
        menu: MenuId,
        rows: Int?,
        issues: MutableList<MenuValidationIssue>,
        target: MutableMap<MenuElementId, MenuElementLayout>,
    ) {
        if (!config.exists("$menuPath.pattern")) return
        val pattern = config.stringListOrNull("$menuPath.pattern")
        if (rows == null || pattern == null || rows !in 1..MenuSlot.MAX_ROWS || pattern.size != rows || pattern.any { it.length != MenuSlot.COLUMNS }) {
            issues += issue(menu, MenuValidationCode.INVALID_PATTERN, "pattern", "must contain exactly one nine-character line per row")
            return
        }
        val symbols = linkedMapOf<Char, MutableList<MenuSlot>>()
        pattern.forEachIndexed { row, line ->
            line.forEachIndexed { column, symbol ->
                if (symbol != '.') symbols.getOrPut(symbol) { mutableListOf() } += MenuSlot.at(row, column)
            }
        }
        symbols.forEach { (symbol, slots) ->
            val legendPath = "$menuPath.legend.$symbol"
            val rawElement = config.stringOrNull("$legendPath.element") ?: config.stringOrNull(legendPath)
            if (rawElement == null) {
                issues += issue(menu, MenuValidationCode.INVALID_LEGEND, "legend.$symbol", "pattern symbol has no element mapping")
                return@forEach
            }
            val element = parseElementId(menu, "legend.$symbol.element", rawElement, issues) ?: return@forEach
            if (element in target) {
                issues += issue(menu, MenuValidationCode.INVALID_LEGEND, "legend.$symbol", "element '$element' already has an explicit placement")
                return@forEach
            }
            val kind = when (config.stringOrNull("$legendPath.kind")?.lowercase()) {
                null -> if (slots.size == 1) MenuElementKind.BUTTON else MenuElementKind.DECORATION
                "button" -> MenuElementKind.BUTTON
                "decoration" -> MenuElementKind.DECORATION
                else -> {
                    issues += issue(menu, MenuValidationCode.INVALID_LEGEND, "legend.$symbol.kind", "must be button or decoration")
                    return@forEach
                }
            }
            if (kind == MenuElementKind.BUTTON && slots.size != 1) {
                issues += issue(menu, MenuValidationCode.INVALID_LEGEND, "legend.$symbol", "button symbol must occur exactly once")
                return@forEach
            }
            val template = (config.stringOrNull("$legendPath.template") ?: element.value).let { raw ->
                parseTemplateId(menu, "legend.$symbol.template", raw, issues)
            }
            target[element] = MenuElementLayout(element, slots.toList(), kind, template)
        }
        config.keys("$menuPath.legend").filter { it.length != 1 || it.single() !in symbols }.forEach { unused ->
            issues += issue(menu, MenuValidationCode.INVALID_LEGEND, "legend.$unused", "legend entry is unused by the pattern")
        }
    }

    private fun parseRegions(
        config: Config,
        menuPath: String,
        menu: MenuId,
        elements: Map<MenuElementId, MenuElementLayout>,
        issues: MutableList<MenuValidationIssue>,
    ): Map<MenuRegionId, MenuRegionLayout> = buildMap {
        config.keys("$menuPath.regions").forEach { rawId ->
            val id = parseRegionId(menu, "regions.$rawId", rawId, issues) ?: return@forEach
            val path = "$menuPath.regions.$rawId"
            val hasSlots = config.exists("$path.slots")
            val hasElements = config.exists("$path.elements")
            if (hasSlots == hasElements) {
                issues += issue(menu, MenuValidationCode.INVALID_VALUE, "regions.$rawId", "must declare exactly one of slots or elements")
                return@forEach
            }
            if (hasSlots) {
                parseSlotList(config, "$path.slots", menu, "regions.$rawId.slots", issues)?.let { slots ->
                    put(id, MenuRegionLayout(id, slots, MenuRegionKind.CONTENT))
                }
            } else {
                val refs = config.stringListOrNull("$path.elements")
                if (refs.isNullOrEmpty()) {
                    issues += issue(menu, MenuValidationCode.INVALID_VALUE, "regions.$rawId.elements", "must contain element IDs")
                    return@forEach
                }
                val slots = refs.flatMap { raw ->
                    val element = parseElementId(menu, "regions.$rawId.elements", raw, issues)
                    val layout = element?.let(elements::get)
                    if (element != null && layout == null) {
                        issues += issue(menu, MenuValidationCode.UNKNOWN_REFERENCE, "regions.$rawId.elements", "references unknown element '$element'")
                    }
                    layout?.slots.orEmpty()
                }
                if (slots.isNotEmpty()) put(id, MenuRegionLayout(id, slots, MenuRegionKind.GROUP))
            }
        }
    }

    private fun parsePagination(
        config: Config,
        menuPath: String,
        menu: MenuId,
        issues: MutableList<MenuValidationIssue>,
    ): MenuPaginationLayout? {
        if (!config.exists("$menuPath.pagination")) return null
        val region = config.stringOrNull("$menuPath.pagination.region")?.let { parseRegionId(menu, "pagination.region", it, issues) }
        val previous = config.stringOrNull("$menuPath.pagination.previous")?.let { parseElementId(menu, "pagination.previous", it, issues) }
        val next = config.stringOrNull("$menuPath.pagination.next")?.let { parseElementId(menu, "pagination.next", it, issues) }
        val indicator = config.stringOrNull("$menuPath.pagination.indicator")?.let { parseElementId(menu, "pagination.indicator", it, issues) }
        if (region == null || previous == null || next == null) {
            issues += issue(menu, MenuValidationCode.INVALID_PAGINATION, "pagination", "requires region, previous, and next IDs")
            return null
        }
        return MenuPaginationLayout(region, previous, next, indicator)
    }

    private fun parseSingleSlot(
        config: Config,
        configPath: String,
        menu: MenuId,
        issuePath: String,
        issues: MutableList<MenuValidationIssue>,
    ): MenuSlot? {
        val raw = config.stringOrNull(configPath)
        val index = raw?.toIntOrNull()
        if (index == null) {
            issues += issue(menu, MenuValidationCode.INVALID_SLOT_EXPRESSION, issuePath, "must be an integer slot")
            return null
        }
        return runCatching { MenuSlot.of(index) }.getOrElse {
            issues += issue(menu, MenuValidationCode.SLOT_OUT_OF_BOUNDS, issuePath, "slot $index is outside 0..53")
            null
        }
    }

    private fun parsePosition(
        config: Config,
        configPath: String,
        menu: MenuId,
        issuePath: String,
        issues: MutableList<MenuValidationIssue>,
    ): MenuSlot? {
        val row = config.intOrNull("$configPath.row")
        val column = config.intOrNull("$configPath.column")
        if (row == null || column == null) {
            issues += issue(menu, MenuValidationCode.INVALID_SLOT_EXPRESSION, issuePath, "requires integer row and column")
            return null
        }
        return runCatching { MenuSlot.at(row, column) }.getOrElse {
            issues += issue(menu, MenuValidationCode.SLOT_OUT_OF_BOUNDS, issuePath, "position ($row,$column) is outside a chest")
            null
        }
    }

    private fun parseSlotList(
        config: Config,
        configPath: String,
        menu: MenuId,
        issuePath: String,
        issues: MutableList<MenuValidationIssue>,
    ): List<MenuSlot>? {
        val raw = config.stringListOrNull(configPath)
        if (raw == null) {
            issues += issue(menu, MenuValidationCode.INVALID_SLOT_EXPRESSION, issuePath, "must be a slot list")
            return null
        }
        return MenuSlotExpression.parse(raw).getOrElse { failure ->
            val code = if (failure.message?.contains("between 0") == true) {
                MenuValidationCode.SLOT_OUT_OF_BOUNDS
            } else {
                MenuValidationCode.INVALID_SLOT_EXPRESSION
            }
            issues += issue(menu, code, issuePath, failure.message ?: "invalid slot expression")
            null
        }
    }

    private fun parseElementId(menu: MenuId, path: String, raw: String, issues: MutableList<MenuValidationIssue>): MenuElementId? =
        runCatching { MenuElementId.of(raw) }.getOrElse {
            issues += issue(menu, MenuValidationCode.INVALID_ID, path, it.message ?: "invalid element id")
            null
        }

    private fun parseRegionId(menu: MenuId, path: String, raw: String, issues: MutableList<MenuValidationIssue>): MenuRegionId? =
        runCatching { MenuRegionId.of(raw) }.getOrElse {
            issues += issue(menu, MenuValidationCode.INVALID_ID, path, it.message ?: "invalid region id")
            null
        }

    private fun parseTemplateId(menu: MenuId, path: String, raw: String, issues: MutableList<MenuValidationIssue>): MenuTemplateId? =
        runCatching { MenuTemplateId.of(raw) }.getOrElse {
            issues += issue(menu, MenuValidationCode.INVALID_ID, path, it.message ?: "invalid template id")
            null
        }

    private fun issue(menu: MenuId, code: MenuValidationCode, path: String, reason: String) =
        issue(menu.value, code, path, reason)

    private fun issue(menu: String, code: MenuValidationCode, path: String, reason: String) =
        MenuValidationIssue(code, menu, path, reason)
}
