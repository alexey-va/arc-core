package ru.arc.menu

private val MENU_KEY = Regex("[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")

@JvmInline
value class MenuId private constructor(val value: String) {
    companion object {
        fun of(value: String): MenuId = MenuId(validMenuKey("menu", value))
    }

    override fun toString(): String = value
}

@JvmInline
value class MenuElementId private constructor(val value: String) {
    companion object {
        fun of(value: String): MenuElementId = MenuElementId(validMenuKey("menu element", value))
    }

    override fun toString(): String = value
}

@JvmInline
value class MenuRegionId private constructor(val value: String) {
    companion object {
        fun of(value: String): MenuRegionId = MenuRegionId(validMenuKey("menu region", value))
    }

    override fun toString(): String = value
}

@JvmInline
value class MenuTemplateId private constructor(val value: String) {
    companion object {
        fun of(value: String): MenuTemplateId = MenuTemplateId(validMenuKey("menu template", value))
    }

    override fun toString(): String = value
}

@JvmInline
value class MenuSlot private constructor(val index: Int) {
    val row: Int get() = index / COLUMNS
    val column: Int get() = index % COLUMNS

    companion object {
        const val COLUMNS = 9
        const val MAX_ROWS = 6
        const val MAX_SLOTS = COLUMNS * MAX_ROWS

        fun of(index: Int): MenuSlot {
            require(index in 0 until MAX_SLOTS) {
                "Menu slot must be between 0 and ${MAX_SLOTS - 1}: $index"
            }
            return MenuSlot(index)
        }

        fun at(row: Int, column: Int): MenuSlot {
            require(row in 0 until MAX_ROWS) { "Menu row must be between 0 and ${MAX_ROWS - 1}: $row" }
            require(column in 0 until COLUMNS) { "Menu column must be between 0 and ${COLUMNS - 1}: $column" }
            return of(row * COLUMNS + column)
        }
    }
}

private fun validMenuKey(kind: String, value: String): String {
    require(MENU_KEY.matches(value)) {
        "$kind id must use 1-64 lowercase characters from a-z, 0-9, dot, underscore, or dash: '$value'"
    }
    return value
}
