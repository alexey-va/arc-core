package ru.arc.menu

data class MenuCatalog(
    val generation: Long = 0,
    val layouts: Map<MenuId, MenuLayout>,
) {
    init {
        require(generation >= 0) { "Menu catalog generation must be non-negative" }
        require(layouts.isNotEmpty()) { "Menu catalog must contain at least one layout" }
    }

    fun require(id: MenuId): MenuLayout =
        requireNotNull(layouts[id]) { "Menu catalog has no layout '$id'" }

    fun require(id: String): MenuLayout = require(MenuId.of(id))

    internal fun withGeneration(value: Long): MenuCatalog = copy(generation = value)
}

sealed interface MenuCatalogLoadResult {
    data class Loaded(val catalog: MenuCatalog) : MenuCatalogLoadResult
    data class Rejected(val issues: List<MenuValidationIssue>) : MenuCatalogLoadResult {
        init {
            require(issues.isNotEmpty()) { "Rejected menu catalog requires at least one issue" }
        }
    }
}
