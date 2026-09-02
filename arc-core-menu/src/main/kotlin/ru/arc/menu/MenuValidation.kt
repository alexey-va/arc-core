package ru.arc.menu

enum class MenuValidationCode {
    INVALID_ID,
    INVALID_SCHEMA_VERSION,
    INVALID_ROWS,
    INVALID_VALUE,
    MISSING_VALUE,
    SLOT_OUT_OF_BOUNDS,
    SLOT_COLLISION,
    INVALID_SLOT_EXPRESSION,
    INVALID_PATTERN,
    INVALID_LEGEND,
    MISSING_REQUIRED_ELEMENT,
    MISSING_REQUIRED_REGION,
    UNKNOWN_ELEMENT,
    UNKNOWN_REGION,
    UNKNOWN_REFERENCE,
    INVALID_PAGINATION,
}

data class MenuValidationIssue(
    val code: MenuValidationCode,
    val menu: MenuId,
    val path: String,
    val reason: String,
)

class MenuValidationException(
    val issues: List<MenuValidationIssue>,
) : IllegalArgumentException(
    issues.joinToString(prefix = "Invalid menu layout: ", separator = "; ") { issue ->
        "${issue.menu}.${issue.path}: ${issue.reason}"
    },
) {
    init {
        require(issues.isNotEmpty()) { "MenuValidationException requires at least one issue" }
    }
}

fun MenuLayout.validated(contract: MenuContract): MenuLayout {
    val issues = validate(contract)
    if (issues.isNotEmpty()) throw MenuValidationException(issues)
    return this
}

fun MenuLayout.validate(contract: MenuContract): List<MenuValidationIssue> = buildList {
    if (rows !in 1..MenuSlot.MAX_ROWS) {
        add(issue(MenuValidationCode.INVALID_ROWS, "rows", "must be between 1 and ${MenuSlot.MAX_ROWS}"))
    }

    contract.requiredElements.forEach { element ->
        if (element !in elements) {
            add(issue(MenuValidationCode.MISSING_REQUIRED_ELEMENT, "elements.$element", "required element is missing"))
        }
    }
    contract.requiredRegions.forEach { region ->
        if (region !in regions) {
            add(issue(MenuValidationCode.MISSING_REQUIRED_REGION, "regions.$region", "required region is missing"))
        }
    }

    if (!contract.allowUnknownElements) {
        elements.keys.filterNot(contract.declaredElements::contains).forEach { element ->
            add(issue(MenuValidationCode.UNKNOWN_ELEMENT, "elements.$element", "element is not declared by the consumer contract"))
        }
    }
    if (!contract.allowUnknownRegions) {
        regions.keys.filterNot(contract.declaredRegions::contains).forEach { region ->
            add(issue(MenuValidationCode.UNKNOWN_REGION, "regions.$region", "region is not declared by the consumer contract"))
        }
    }

    if (rows in 1..MenuSlot.MAX_ROWS) {
        val occupied = linkedMapOf<MenuSlot, String>()
        elements.forEach { (id, element) ->
            element.slots.forEachIndexed { index, slot ->
                val path = if (element.slots.size == 1) "elements.$id.slot" else "elements.$id.slots[$index]"
                validateSlot(this@validate, slot, path, occupied)
            }
        }
        regions.forEach { (id, region) ->
            region.slots.forEachIndexed { index, slot ->
                validateSlot(this@validate, slot, "regions.$id.slots[$index]", occupied)
            }
        }
    }

    pagination?.let { page ->
        if (page.region !in regions) {
            add(issue(MenuValidationCode.INVALID_PAGINATION, "pagination.region", "references unknown region '${page.region}'"))
        }
        listOf(page.previous to "previous", page.next to "next").forEach { (element, field) ->
            if (element !in elements) {
                add(issue(MenuValidationCode.INVALID_PAGINATION, "pagination.$field", "references unknown element '$element'"))
            }
        }
        page.indicator?.takeIf { it !in elements }?.let { element ->
            add(issue(MenuValidationCode.INVALID_PAGINATION, "pagination.indicator", "references unknown element '$element'"))
        }
    }
}

private fun MutableList<MenuValidationIssue>.validateSlot(
    layout: MenuLayout,
    slot: MenuSlot,
    path: String,
    occupied: MutableMap<MenuSlot, String>,
) {
    if (slot.index >= layout.rows * MenuSlot.COLUMNS) {
        add(layout.issue(MenuValidationCode.SLOT_OUT_OF_BOUNDS, path, "slot ${slot.index} is outside ${layout.rows} rows"))
        return
    }
    val previous = occupied.putIfAbsent(slot, path)
    if (previous != null) {
        add(layout.issue(MenuValidationCode.SLOT_COLLISION, path, "slot ${slot.index} is already occupied by $previous"))
    }
}

private fun MenuLayout.issue(code: MenuValidationCode, path: String, reason: String): MenuValidationIssue =
    MenuValidationIssue(code, id, path, reason)
