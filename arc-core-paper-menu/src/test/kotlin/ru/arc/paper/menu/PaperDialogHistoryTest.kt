package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class PaperDialogHistoryTest : FreeSpec({
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
