package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor

class PaperDialogNavigationTest : FreeSpec({
    "explicit paging preserves actions and locale while moving only those controls below content" {
        val blue = TextColor.color(0x92BED8)
        fun button(id: String, width: Int = 180) = PaperDialogButton(
            PaperDialogActionId.of(id), Component.text(id), Component.text("Tooltip $id"), width,
        ) {}
        val oldPrevious = button("older_items")
        val oldNext = button("more_items", 220)
        val previous = PaperDialogNavigation.previous(oldPrevious, Component.text("Previous page"))
        val next = PaperDialogNavigation.next(oldNext, Component.text("Следующая страница"))
        previous.label shouldBe Component.text("‹ ", blue).append(Component.text("Previous page", blue))
        next.label shouldBe Component.text("Следующая страница", blue).append(Component.text(" ›", blue))
        previous.copy(label = oldPrevious.label) shouldBe oldPrevious
        next.copy(label = oldNext.label) shouldBe oldNext
        PaperDialogNavigation.previous(oldPrevious).label shouldBe
            Component.text("‹ ", blue).append(Component.translatable("spectatorMenu.previous_page", blue))
        PaperDialogNavigation.next(oldNext).label shouldBe
            Component.translatable("spectatorMenu.next_page", blue).append(Component.text(" ›", blue))

        val item = button("item")
        val footer = button("return")
        val screen = PaperDialogScreen(Component.text("Catalog"),
            buttons = listOf(oldNext, item, oldPrevious), exitButton = footer, columns = 2)
        val ordered = PaperDialogNavigation.bottom(screen, previous, next)
        ordered.buttons shouldBe listOf(item, previous, next)
        ordered.copy(buttons = screen.buttons) shouldBe screen
        PaperDialogNavigation.bottom(screen, null, null) shouldBe screen

        val lastPage = screen.copy(buttons = listOf(item))
        PaperDialogNavigation.bottom(lastPage, previous, null).buttons shouldBe listOf(item, previous)
        runCatching { PaperDialogNavigation.bottom(screen, previous, previous) }.isFailure shouldBe true
    }
})
