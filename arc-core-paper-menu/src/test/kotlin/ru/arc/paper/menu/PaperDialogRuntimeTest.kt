package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.spyk
import io.mockk.just
import io.mockk.Runs
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.event.player.PlayerCustomClickEvent
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperDialogRuntimeTest : FreeSpec({
    "number-range responses are exposed through the typed click context" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("SliderTester")
            val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
            val quantity = PaperDialogInputId.of("quantity")
            lateinit var registration: PaperDialogSessionRegistration
            var submitted: Float? = null
            PaperDialogRuntime(paper.createSimplePlugin("DialogSlider")) { _, _, registered ->
                registration = registered
            }.use { runtime ->
                runtime.beginFlow(player)
                runtime.open(player, PaperDialogScreen(
                    Component.text("Slider"),
                    numberInputs = listOf(PaperDialogNumberRangeInput(
                        quantity, Component.text("Quantity"), 1f, 64f, initial = 1f, step = 1f,
                    )),
                    buttons = listOf(PaperDialogButton(
                        PaperDialogActionId.of("submit"), Component.text("Submit"),
                    ) { submitted = it.number(quantity) }),
                ))
                val response = mockk<DialogResponseView> {
                    every { getFloat("quantity") } returns 17f
                }
                runtime.onCustomClick(mockk {
                    every { commonConnection } returns connection
                    every { identifier } returns Key.key(registration.key(PaperDialogActionId.of("submit")))
                    every { dialogResponseView } returns response
                })

                submitted shouldBe 17f
            }
        }
    }

    "default child transitions and repeated purchases reuse history without closing the native window" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = spyk(paper.addPlayer("RepeatedBuyer"))
            every { player.closeDialog() } just Runs
            val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
            lateinit var screen: PaperDialogScreen
            lateinit var registration: PaperDialogSessionRegistration
            PaperDialogRuntime(paper.createSimplePlugin("RepeatShop")) { _, shown, registered ->
                screen = shown
                registration = registered
            }.use { runtime ->
                fun button(id: String, action: () -> Unit) = PaperDialogButton(
                    PaperDialogActionId.of(id), Component.text(id), onClick = { action() })
                fun shop() {
                    runtime.open(player, PaperDialogScreen(Component.text("Shop"), id = "shop", buttons = listOf(
                        button("confirm") {
                            runtime.open(player, PaperDialogScreen(Component.text("Confirm"), id = "confirm", buttons = listOf(
                                button("buy") { shop() },
                            )))
                        },
                    )))
                }
                fun click(id: PaperDialogActionId) {
                    val key = registration.key(id)
                    runtime.onCustomClick(mockk {
                        every { commonConnection } returns connection
                        every { identifier } returns Key.key(key)
                        every { dialogResponseView } returns null
                    })
                }
                runtime.beginFlow(player)
                runtime.open(player, PaperDialogScreen(Component.text("Panel"), id = "panel", buttons = listOf(button("shop") { shop() })))
                click(PaperDialogActionId.of("shop"))
                repeat(3) {
                    click(PaperDialogActionId.of("confirm"))
                    click(PaperDialogActionId.of("buy"))
                    screen.id shouldBe "shop"
                }
                click(requireNotNull(screen.exitButton).id)
                screen.id shouldBe "panel"
                verify(exactly = 0) { player.closeDialog() }
                click(requireNotNull(screen.exitButton).id)
                verify(exactly = 1) { player.closeDialog() }
            }
        }
    }

    "information screens have only a working history footer and no invented grid action" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = spyk(paper.addPlayer("InfoTester"))
            every { player.closeDialog() } just Runs
            lateinit var displayed: PaperDialogScreen
            lateinit var registration: PaperDialogSessionRegistration
            PaperDialogRuntime(paper.createSimplePlugin("DialogInfo")) { _, screen, registered ->
                displayed = screen
                registration = registered
            }.use { runtime ->
                for (customFooter in listOf(false, true)) {
                    runtime.beginFlow(player)
                    runtime.open(player, PaperDialogScreen(Component.text("Information"), buttons = emptyList(),
                        exitButton = if (customFooter) PaperDialogButton(PaperDialogActionId.of("back"), Component.text("Back")) {
                            error("Semantic footer must be replaced by actual history")
                        } else null))
                    displayed.buttons shouldBe emptyList()
                    val footer = requireNotNull(displayed.exitButton)
                    registration.actions.size shouldBe 1
                    val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
                    runtime.onCustomClick(mockk {
                        every { commonConnection } returns connection
                        every { identifier } returns Key.key(registration.key(footer.id))
                        every { dialogResponseView } returns null
                    })
                }
                verify(exactly = 2) { player.closeDialog() }
                runtime.close(player)
                verify(exactly = 2) { player.closeDialog() }
            }
        }
    }

    "actual history refreshes its parent, invalidates pending work and ends at the command entry" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = spyk(paper.addPlayer("DialogTester"))
            every { player.closeDialog() } just Runs
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
                runtime.beginFlow(player)
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

                runtime.beginFlow(player)
                root()
                click("settings")
                runtime.onCommand(PlayerCommandPreprocessEvent(player, "/settings"))
                runtime.open(player, PaperDialogScreen(Component.text("Direct settings"), id = "settings",
                    buttons = listOf(button("noop") {}), exitButton = button("back") { semanticParentCalls++ }))
                click("back")
                verify(exactly = 2) { player.closeDialog() }
                semanticParentCalls shouldBe 0

                runtime.beginFlow(player)
                root()
                click("settings")
                runtime.open(player, displayed, null, {}, true)
                click("back")
                verify(exactly = 3) { player.closeDialog() }
            }
        }
    }
})
