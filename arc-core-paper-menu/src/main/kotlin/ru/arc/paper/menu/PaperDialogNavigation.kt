package ru.arc.paper.menu

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor

/** Native paging presentation and ordering; screen history remains owned by [PaperDialogRuntime]. */
object PaperDialogNavigation {
    private val pageColor = TextColor.color(0x92BED8)

    /** Uses the client's built-in translation. Supply a bare localized caption to retain a server locale. */
    @JvmStatic
    fun previous(button: PaperDialogButton): PaperDialogButton =
        previous(button, Component.translatable("spectatorMenu.previous_page"))

    /** Adds the previous-page marker without parsing the old label or changing the action. */
    @JvmStatic
    fun previous(button: PaperDialogButton, caption: Component): PaperDialogButton = button.copy(
        label = Component.text("‹ ", pageColor).append(caption.color(pageColor)),
    )

    /** Uses the client's built-in translation. Supply a bare localized caption to retain a server locale. */
    @JvmStatic
    fun next(button: PaperDialogButton): PaperDialogButton =
        next(button, Component.translatable("spectatorMenu.next_page"))

    /** Adds the next-page marker without parsing the old label or changing the action. */
    @JvmStatic
    fun next(button: PaperDialogButton, caption: Component): PaperDialogButton = button.copy(
        label = caption.color(pageColor).append(Component.text(" ›", pageColor)),
    )

    /**
     * Places explicitly supplied, usable paging controls after content, previous before next.
     * Existing occurrences of those exact IDs are moved; other controls and the exit footer
     * remain untouched. Pass null at a boundary unless the consumer really wraps pages.
     *
     * Paper has one flat action grid: this orders the last controls but cannot create an
     * independent row after a partially filled row. No filler or disabled action is added.
     * These adapters retain handlers, widths and lifecycle flags; paging actions should keep
     * closeDialogBeforeAction=false. Pass all arguments explicitly across plugin classloaders.
     */
    @JvmStatic
    @JvmOverloads
    fun bottom(
        screen: PaperDialogScreen,
        previous: PaperDialogButton? = null,
        next: PaperDialogButton? = null,
    ): PaperDialogScreen {
        val navigation = listOfNotNull(previous, next)
        if (navigation.isEmpty()) return screen
        val ids = navigation.map { it.id }.toSet()
        require(ids.size == navigation.size) { "Previous and next page action ids must differ" }
        return screen.copy(buttons = screen.buttons.filterNot { it.id in ids } + navigation)
    }
}
