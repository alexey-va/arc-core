package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.event.player.PlayerCustomClickEvent
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class PaperDialogRuntimeTest : FreeSpec({
    "actual history refreshes its parent, invalidates pending work and ends at the command entry" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = mockk<Player>(relaxed = true)
            every { player.uniqueId } returns UUID.randomUUID()
            val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
            lateinit var displayed: PaperDialogScreen
            lateinit var registration: PaperDialogSessionRegistration
            var value = 120
            var dismissed = 0
            var semanticParentCalls = 0
            PaperDialogRuntime(paper.createSimplePlugin("DialogHistory")) { _, screen, registered ->
                displayed = screen
                registration = registered
            }.use { runtime ->
                fun click(id: String) {
                    val key = registration.key(PaperDialogActionId.of(id))
                    runtime.onCustomClick(mockk {
                        every { commonConnection } returns connection
                        every { identifier } returns Key.key(key)
                        every { dialogResponseView } returns null
                    })
                }
                fun button(id: String, action: () -> Unit) =
                    PaperDialogButton(PaperDialogActionId.of(id), Component.text(id)) { action() }
                fun root() {
                    runtime.open(player, PaperDialogScreen(Component.text("Saved $value"), id = "saved",
                        buttons = listOf(button("settings") {
                            // Public entry helpers called from a menu must not reset its history.
                            runtime.beginFlow(player)
                            runtime.open(player, PaperDialogScreen(Component.text("Settings"), id = "settings",
                                buttons = listOf(button("change") { value = 60 }),
                                exitButton = button("back") { semanticParentCalls++ }),
                                null, { dismissed++ })
                        }), exitButton = button("back") { semanticParentCalls++ }),
                        ::root, { dismissed++ })
                }
                root()
                click("settings")
                value = 60
                click("back")
                displayed.title shouldBe Component.text("Saved 60")
                dismissed shouldBe 1
                semanticParentCalls shouldBe 0
                click("back")
                verify(exactly = 1) { player.closeDialog() }
                dismissed shouldBe 2

                root()
                click("settings")
                runtime.onCommand(PlayerCommandPreprocessEvent(player, "/settings"))
                runtime.open(player, PaperDialogScreen(Component.text("Direct settings"), id = "settings",
                    buttons = listOf(button("noop") {}), exitButton = button("back") { semanticParentCalls++ }))
                click("back")
                verify(exactly = 2) { player.closeDialog() }
                semanticParentCalls shouldBe 0

                root()
                click("settings")
                runtime.open(player, displayed, null, {}, true)
                click("back")
                verify(exactly = 3) { player.closeDialog() }
            }
        }
    }
})
