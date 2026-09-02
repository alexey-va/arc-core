package ru.arc.menu

enum class MenuElementKind {
    BUTTON,
    DECORATION,
}

data class MenuElementLayout(
    val id: MenuElementId,
    val slots: List<MenuSlot>,
    val kind: MenuElementKind,
    val template: MenuTemplateId? = null,
) {
    init {
        require(slots.isNotEmpty()) { "Menu element '$id' must occupy at least one slot" }
    }

    companion object {
        fun button(
            id: MenuElementId,
            slot: MenuSlot,
            template: MenuTemplateId? = null,
        ): MenuElementLayout = MenuElementLayout(id, listOf(slot), MenuElementKind.BUTTON, template)

        fun decoration(
            id: MenuElementId,
            slots: List<MenuSlot>,
            template: MenuTemplateId? = null,
        ): MenuElementLayout = MenuElementLayout(id, slots.toList(), MenuElementKind.DECORATION, template)
    }
}

enum class MenuRegionKind {
    CONTENT,
    GROUP,
}

data class MenuRegionLayout(
    val id: MenuRegionId,
    val slots: List<MenuSlot>,
    val kind: MenuRegionKind = MenuRegionKind.CONTENT,
) {
    init {
        require(slots.isNotEmpty()) { "Menu region '$id' must contain at least one slot" }
    }
}

data class MenuPaginationLayout(
    val region: MenuRegionId,
    val previous: MenuElementId,
    val next: MenuElementId,
    val indicator: MenuElementId? = null,
)

data class MenuLayout(
    val id: MenuId,
    val rows: Int,
    val elements: Map<MenuElementId, MenuElementLayout> = emptyMap(),
    val regions: Map<MenuRegionId, MenuRegionLayout> = emptyMap(),
    val backgroundTemplate: MenuTemplateId? = null,
    val pagination: MenuPaginationLayout? = null,
) {
    fun slot(id: MenuElementId): MenuSlot =
        requireNotNull(elements[id]) { "Menu '${this.id}' has no element '$id'" }.slots.single()

    fun slot(id: String): MenuSlot = slot(MenuElementId.of(id))

    fun region(id: MenuRegionId): List<MenuSlot> =
        requireNotNull(regions[id]) { "Menu '${this.id}' has no region '$id'" }.slots

    fun region(id: String): List<MenuSlot> = region(MenuRegionId.of(id))
}
