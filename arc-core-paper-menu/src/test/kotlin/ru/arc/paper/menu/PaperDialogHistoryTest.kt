package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component

class PaperDialogHistoryTest : FreeSpec({
    "submitted text is retained and bounded when restoring the form snapshot" {
        val input = PaperDialogInputId.of("message")
        val form = PaperDialogScreen(Component.text("Editor"),
            inputs = listOf(PaperDialogTextInput(input, Component.text("Text"), initial = "Old", maxLength = 12)),
            buttons = emptyList())
        form.captureTextInputs { "New message" }.inputs.single().initial shouldBe "New message"
        form.captureTextInputs { null }.inputs.single().initial shouldBe "Old"
        form.captureTextInputs { "x".repeat(100) }.inputs.single().initial shouldBe "x".repeat(12)
    }

    "shared wrappers follow actual visits and refresh without extra steps or dismissals" {
        val state = java.util.HashMap<String, Any>()
        val first = PaperDialogHistory(state)
        val second = PaperDialogHistory(state)
        val restored = mutableListOf<String>()
        var dismissed = 0
        var deactivated = 0
        fun show(history: PaperDialogHistory, owner: String, key: String, title: String = key) =
            history.show(owner, key, Runnable { deactivated++ }, Runnable { dismissed++ }, Runnable { restored += title })
        show(first, "a", "search") shouldBe true
        first.dispatch {
            second.beginFlow() // A foreign public entry called by A's action is a child.
            show(second, "b", "settings") shouldBe true
        }
        second.dispatch { show(second, "b", "settings", "Updated settings") shouldBe true }
        second.dispatch { show(second, "b", "loading") shouldBe true }
        show(second, "b", "loaded") shouldBe true
        dismissed shouldBe 0
        deactivated shouldBe 4
        second.back() shouldBe true
        second.back() shouldBe true
        restored shouldBe listOf("Updated settings", "search")
        first.back() shouldBe false
        first.owner shouldBe null
        dismissed shouldBe 3
        show(first, "a", "late") shouldBe false
        first.beginFlow()
        show(first, "a", "command") shouldBe true
        first.back() shouldBe false
    }

    "three purchases return to one shop visit without replaying confirmations" {
        val history = PaperDialogHistory(java.util.HashMap())
        val restored = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        fun show(key: String) = history.show("shop-owner", key, Runnable {},
            Runnable { dismissed += key }, Runnable { restored += key })
        show("panel")
        history.dispatch { show("shop") }
        repeat(3) {
            history.dispatch { show("confirm") }
            history.dispatch {
                history.back() shouldBe true
                show("shop")
            }
        }
        restored.clear()
        dismissed shouldBe listOf("confirm", "confirm", "confirm")
        history.back() shouldBe true
        restored shouldBe listOf("panel")
        history.back() shouldBe false
    }

    "foreign late completion cannot replace the current screen and inactive unload keeps it" {
        val history = PaperDialogHistory(java.util.HashMap())
        fun show(owner: String, key: String) = history.show(owner, key, Runnable {}, Runnable {}, Runnable {})
        show("a", "root") shouldBe true
        history.dispatch { show("b", "child") shouldBe true }
        show("a", "late") shouldBe false
        history.removeOwner("a")
        history.owner shouldBe "b"
        history.back() shouldBe false
    }

    "restore entry helpers preserve ancestors and direct entry clears all owners" {
        val history = PaperDialogHistory(java.util.HashMap())
        var restored = false
        fun show(owner: String, key: String, resume: Runnable = Runnable {}) =
            history.show(owner, key, Runnable {}, Runnable {}, resume)
        show("a", "root")
        history.dispatch { show("a", "parent", Runnable {
            history.beginFlow()
            show("a", "parent-refreshed")
            restored = true
        }) }
        history.dispatch { show("b", "child") }
        history.dispatch { history.back() shouldBe true }
        restored shouldBe true
        history.back() shouldBe true
        history.beginFlow()
        show("b", "direct") shouldBe true
        history.back() shouldBe false
    }

    "a pending explicit root rejects the previous owner's asynchronous result" {
        val history = PaperDialogHistory(java.util.HashMap())
        history.beginFlow("b")
        history.show("a", "late", Runnable {}, Runnable {}, Runnable {}) shouldBe false
        history.entryOwner shouldBe "b"
        history.clear()
        history.show("b", "late", Runnable {}, Runnable {}, Runnable {}) shouldBe false
    }

    "bounded history and separate player states cannot leak visits" {
        val history = PaperDialogHistory(java.util.HashMap(), 3)
        val otherPlayer = PaperDialogHistory(java.util.HashMap())
        val restored = mutableListOf<String>()
        repeat(5) { index -> history.dispatch {
            history.show("a", "$index", Runnable {}, Runnable {}, Runnable { restored += "$index" })
        } }
        otherPlayer.show("b", "other", Runnable {}, Runnable {}, Runnable {})
        history.back() shouldBe true
        history.back() shouldBe true
        history.back() shouldBe false
        restored shouldBe listOf("3", "2")
        otherPlayer.owner shouldBe "b"
    }
})
