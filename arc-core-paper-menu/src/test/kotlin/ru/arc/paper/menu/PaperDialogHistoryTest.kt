package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import net.kyori.adventure.text.Component

class PaperDialogHistoryTest : FreeSpec({
    "returning from a preview preserves submitted text rather than the original form value" {
        val player = UUID.randomUUID()
        val input = PaperDialogInputId.of("message")
        val form = PaperDialogScreen(Component.text("Editor"),
            inputs = listOf(PaperDialogTextInput(input, Component.text("Text"), initial = "Old", maxLength = 12)),
            buttons = listOf(PaperDialogButton(PaperDialogActionId.of("preview"), Component.text("Preview")) {}))
        val history = PaperDialogHistory<PaperDialogScreen>()
        history.show(player, "form", form, false)
        history.updateCurrent(player) { it.captureTextInputs { "New message" } }
        history.show(player, "preview", form.copy(title = Component.text("Preview")), true)
        history.back(player)!!.inputs.single().initial shouldBe "New message"
        form.captureTextInputs { null }.inputs.single().initial shouldBe "Old"
        form.captureTextInputs { "x".repeat(100) }.inputs.single().initial shouldBe "x".repeat(12)
    }

    "command entry closes without inventing a semantic parent" {
        val player = UUID.randomUUID()
        val history = PaperDialogHistory<String>()
        history.show(player, "settings", "Settings opened by command", false)
        history.back(player) shouldBe null
        history.current(player) shouldBe null
    }

    "back follows actual visits and refreshing a page is not another visit" {
        val player = UUID.randomUUID()
        val history = PaperDialogHistory<String>()
        history.show(player, "search", "Search", false)
        history.show(player, "settings", "Settings before change", true)
        history.show(player, "settings", "Settings after change", true)
        history.show(player, "details", "Loading", true)
        history.show(player, "details-ready", "Loaded", false)
        history.back(player) shouldBe "Settings after change"
        history.back(player) shouldBe "Search"
        history.back(player) shouldBe null
    }

    "a fresh command discards old history and players are isolated" {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val history = PaperDialogHistory<String>()
        history.show(first, "root", "Root", false)
        history.show(first, "child", "Child", true)
        history.show(second, "other", "Other player", false)
        history.remove(first)
        history.show(first, "guide", "Direct guide command", false)
        history.back(first) shouldBe null
        history.current(second) shouldBe "Other player"
        history.clear()
        history.current(second) shouldBe null
    }

    "forward revisits keep the actual route and history remains bounded" {
        val player = UUID.randomUUID()
        val history = PaperDialogHistory<String>(3)
        history.show(player, "a", "A1", false)
        history.show(player, "b", "B", true)
        history.show(player, "a", "A2", true)
        history.back(player) shouldBe "B"
        repeat(5) { history.show(player, it, "$it", true) }
        history.back(player) shouldBe "3"
        history.back(player) shouldBe "2"
        history.back(player) shouldBe null
    }
})
