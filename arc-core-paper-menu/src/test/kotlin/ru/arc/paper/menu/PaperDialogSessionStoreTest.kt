package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import java.util.UUID

class PaperDialogSessionStoreTest : FreeSpec({
    "replaces the previous dialog and consumes an action exactly once" {
        val player = UUID.randomUUID()
        val store = PaperDialogSessionStore("arc")
        var firstClicks = 0
        var secondClicks = 0

        val first = store.replace(player, mapOf(PaperDialogActionId.of("first") to { firstClicks++ }))
        val second = store.replace(player, mapOf(PaperDialogActionId.of("second") to { secondClicks++ }))

        store.consume(player, first.key(PaperDialogActionId.of("first")))?.invoke()
        store.consume(player, second.key(PaperDialogActionId.of("second")))?.invoke()
        store.consume(player, second.key(PaperDialogActionId.of("second")))?.invoke()

        firstClicks shouldBe 0
        secondClicks shouldBe 1
        store.size shouldBe 0
    }

    "rejects actions owned by another player or namespace" {
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        val store = PaperDialogSessionStore("arc")
        var clicks = 0
        val action = PaperDialogActionId.of("open_land")
        val session = store.replace(owner, mapOf(action to { clicks++ }))

        store.consume(other, session.key(action)) shouldBe null
        store.consume(owner, "other:dialog/${session.nonce}/open_land") shouldBe null
        clicks shouldBe 0
        store.size shouldBe 1
    }

    "validates identifiers used in native dialog keys" {
        PaperDialogActionId.of("member_add").value shouldBe "member_add"
        PaperDialogInputId.of("player_name").value shouldBe "player_name"

        runCatching { PaperDialogActionId.of("Member Add") }.isFailure shouldBe true
        runCatching { PaperDialogInputId.of("player-name") }.isFailure shouldBe true
    }

    "rejects duplicate actions and unbounded layouts before presentation" {
        val action = PaperDialogActionId.of("open")
        val button = PaperDialogButton(action, Component.text("Open")) {}

        runCatching { PaperDialogScreen(Component.text("Title"), buttons = listOf(button, button)) }.isFailure shouldBe true
        runCatching { PaperDialogScreen(Component.text("Title"), buttons = listOf(button), columns = 6) }.isFailure shouldBe true
        runCatching { PaperDialogBody(Component.text("Body"), width = 0) }.isFailure shouldBe true
    }

    "keeps navigation dialogs open unless an action explicitly requests closing" {
        val navigate = PaperDialogButton(PaperDialogActionId.of("navigate"), Component.text("Navigate")) {}
        val execute = PaperDialogButton(
            PaperDialogActionId.of("execute"),
            Component.text("Execute"),
            closeDialogBeforeAction = true,
        ) {}

        navigate.closeDialogBeforeAction shouldBe false
        execute.closeDialogBeforeAction shouldBe true
    }
})
