package ru.arc.paper.menu

import net.kyori.adventure.text.Component

private val MENU_TEXT_TAG = Regex("[a-z0-9_-]{1,64}")

/**
 * Plugin-owned declaration of values that a YAML item presentation may use.
 *
 * YAML owns wording and composition. The plugin only exposes safe Adventure
 * components, boolean state and repeat rows; it never interpolates raw player
 * input into MiniMessage source text.
 */
data class PaperMenuTextContract(
    val values: Set<String> = emptySet(),
    val flags: Set<String> = emptySet(),
    val repeats: Map<String, Set<String>> = emptyMap(),
) {
    init {
        validateNames("value", values)
        validateNames("flag", flags)
        validateNames("repeat", repeats.keys)
        repeats.forEach { (repeat, rowValues) -> validateNames("repeat '$repeat' value", rowValues) }
        require(values.intersect(flags).isEmpty()) { "Menu text values and flags must use distinct names" }
        repeats.forEach { (repeat, rowValues) ->
            require(values.intersect(rowValues).isEmpty()) {
                "Menu text repeat '$repeat' row values must not shadow global values"
            }
        }
    }

    private fun validateNames(kind: String, names: Set<String>) {
        require(names.all(MENU_TEXT_TAG::matches)) {
            "Menu text $kind names must match ${MENU_TEXT_TAG.pattern}: ${names.filterNot(MENU_TEXT_TAG::matches)}"
        }
    }
}

data class PaperMenuItemRenderContext(
    val values: Map<String, Component> = emptyMap(),
    val flags: Set<String> = emptySet(),
    val repeats: Map<String, List<Map<String, Component>>> = emptyMap(),
) {
    init {
        require(values.keys.all(MENU_TEXT_TAG::matches)) { "Unsafe menu text value name" }
        require(flags.all(MENU_TEXT_TAG::matches)) { "Unsafe menu text flag name" }
        require(repeats.keys.all(MENU_TEXT_TAG::matches)) { "Unsafe menu text repeat name" }
        require(repeats.values.flatten().flatMap(Map<String, Component>::keys).all(MENU_TEXT_TAG::matches)) {
            "Unsafe menu text repeat value name"
        }
    }
}

class PaperMenuItemTextTemplate internal constructor(
    val name: String,
    val lore: List<PaperMenuLoreTemplateEntry>,
    internal val nameReferencedValues: Set<String>,
)

sealed interface PaperMenuLoreTemplateEntry {
    val whenFlags: Set<String>
    val unlessFlags: Set<String>

    class Text internal constructor(
        val text: String,
        override val whenFlags: Set<String>,
        override val unlessFlags: Set<String>,
        internal val referencedValues: Set<String>,
    ) : PaperMenuLoreTemplateEntry

    class Repeat internal constructor(
        val repeat: String,
        val text: String,
        override val whenFlags: Set<String>,
        override val unlessFlags: Set<String>,
        internal val referencedValues: Set<String>,
        internal val referencedRowValues: Set<String>,
    ) : PaperMenuLoreTemplateEntry
}
