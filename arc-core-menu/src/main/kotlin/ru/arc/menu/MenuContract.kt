package ru.arc.menu

data class MenuContract(
    val requiredElements: Set<MenuElementId> = emptySet(),
    val optionalElements: Set<MenuElementId> = emptySet(),
    val requiredRegions: Set<MenuRegionId> = emptySet(),
    val optionalRegions: Set<MenuRegionId> = emptySet(),
    val allowUnknownElements: Boolean = false,
    val allowUnknownRegions: Boolean = false,
) {
    init {
        require(requiredElements.intersect(optionalElements).isEmpty()) {
            "Required and optional menu elements must be disjoint"
        }
        require(requiredRegions.intersect(optionalRegions).isEmpty()) {
            "Required and optional menu regions must be disjoint"
        }
    }

    internal val declaredElements: Set<MenuElementId> get() = requiredElements + optionalElements
    internal val declaredRegions: Set<MenuRegionId> get() = requiredRegions + optionalRegions
}
