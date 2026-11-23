package ru.arc.paper.menu

import io.kotest.assertions.throwables.shouldThrow
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
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperDialogRuntimeTest : FreeSpec({
    "fresh player interactions reopen after the footer closes and continuations stay fenced" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = spyk(paper.addPlayer("RepeatEntry"))
            every { player.closeDialog() } just Runs
            val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
            val shown = mutableListOf<String>()
            lateinit var shownScreen: PaperDialogScreen
            lateinit var registration: PaperDialogSessionRegistration
            PaperDialogRuntime(paper.createSimplePlugin("RepeatEntry")) { _, screen, registered ->
                shown += screen.id
                shownScreen = screen
                registration = registered
            }.use { runtime ->
                val entry = PaperDialogScreen(Component.text("Trainer"), id = "trainer", buttons = emptyList())
                val late = entry.copy(id = "late")
                runtime.openInFlow(player, late)
                shown shouldBe emptyList()
                repeat(2) {
                    runtime.openRoot(player, entry)
                    val exit = registration.key(requireNotNull(shownScreen.exitButton).id)
                    runtime.onCustomClick(mockk {
                        every { commonConnection } returns connection
                        every { identifier } returns Key.key(exit)
                        every { dialogResponseView } returns null
                    })
                    runtime.openInFlow(player, late)
                }
                runtime.onCommand(PlayerCommandPreprocessEvent(player, "/unrelated"))
                runtime.openInFlow(player, late)
                shown shouldBe listOf("trainer", "trainer")
                verify(exactly = 2) { player.closeDialog() }
            }
        }
    }

    "reserved root presents once and loses ownership to a newer entry or close" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = spyk(paper.addPlayer("ReservedEntry"))
            val other = paper.addPlayer("OtherPlayer")
            every { player.closeDialog() } just Runs
            val shown = mutableListOf<String>()
            PaperDialogRuntime(paper.createSimplePlugin("ReservedEntry")) { _, screen, _ ->
                shown += screen.id
            }.use { runtime ->
                val reward = PaperDialogScreen(Component.text("Voucher"), id = "voucher", buttons = emptyList())
                val old = requireNotNull(runtime.reserveRoot(player))
                runtime.openInFlow(player, reward)
                runtime.openReservedRoot(other, old, reward) shouldBe false
                shown shouldBe emptyList()

                runtime.openRoot(player, reward.copy(id = "newer"))
                runtime.openReservedRoot(player, old, reward) shouldBe false
                val canceled = requireNotNull(runtime.reserveRoot(player))
                runtime.close(player)
                runtime.openReservedRoot(player, canceled, reward) shouldBe false
                val current = requireNotNull(runtime.reserveRoot(player))
                runtime.openReservedRoot(player, current, reward) shouldBe true
                runtime.openReservedRoot(player, current, reward) shouldBe false
                shown shouldBe listOf("newer", "voucher")
            }
        }
    }

    "number-range responses are exposed through the typed click context" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("SliderTester")
            val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
            val quantity = PaperDialogInputId.of("quantity")
            lateinit var registration: PaperDialogSessionRegistration
            var submitted: Float? = null
            val failure = IllegalStateException("private handler details")
            val plugin = paper.createSimplePlugin("DialogSlider")
            val observed = paper.observations(plugin)
            PaperDialogRuntime(plugin) { _, _, registered ->
                registration = registered
            }.use { runtime ->
                runtime.beginFlow(player)
                runtime.openInFlow(player, PaperDialogScreen(
                    Component.text("Slider"),
                    numberInputs = listOf(PaperDialogNumberRangeInput(
                        quantity, Component.text("Quantity"), 1f, 64f, initial = 1f, step = 1f,
                    )),
                    buttons = listOf(PaperDialogButton(
                        PaperDialogActionId.of("submit"), Component.text("Submit"),
                    ) {
                        submitted = it.number(quantity)
                        throw failure
                    }),
                ))
                val response = mockk<DialogResponseView> {
                    every { getFloat("quantity") } returns 17f
                }
                shouldThrow<IllegalStateException> {
                    runtime.onCustomClick(mockk {
                        every { commonConnection } returns connection
                        every { identifier } returns Key.key(registration.key(PaperDialogActionId.of("submit")))
                        every { dialogResponseView } returns response
                    })
                } shouldBe failure

                submitted shouldBe 17f
                val attempt = observed.single { it["phase"] == "click" }
                observed.single { it["phase"] == "result" } shouldBe attempt + mapOf(
                    "phase" to "result", "outcome" to "failed", "reason" to "handler_exception",
                )
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
            val plugin = paper.createSimplePlugin("RepeatShop")
            val observed = paper.observations(plugin)
            PaperDialogRuntime(plugin) { _, shown, registered ->
                screen = shown
                registration = registered
            }.use { runtime ->
                fun button(id: String, action: () -> Unit) = PaperDialogButton(
                    PaperDialogActionId.of(id), Component.text(id), onClick = { action() })
                fun shop() {
                    runtime.openInFlow(player, PaperDialogScreen(Component.text("Shop"), id = "shop", buttons = listOf(
                        button("confirm") {
                            runtime.openInFlow(player, PaperDialogScreen(Component.text("Confirm"), id = "confirm", buttons = listOf(
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
                runtime.openInFlow(player, PaperDialogScreen(Component.text("Panel"), id = "panel", buttons = listOf(button("shop") { shop() })))
                screen.exitButton?.label shouldBe Component.translatable("mco.selectServer.close", NamedTextColor.WHITE)
                screen.exitButton?.tooltip shouldBe screen.exitButton?.label
                screen.exitButton?.width shouldBe 200
                click(PaperDialogActionId.of("shop"))
                val shopAttempt = observed.single { it["phase"] == "click" }
                val shopOpen = observed.last { it["phase"] == "open" }
                shopOpen["surface"] shouldBe "shop"
                shopOpen["triggerAttemptId"] shouldBe shopAttempt["attemptId"]
                screen.exitButton?.label shouldBe Component.text("‹ ", NamedTextColor.WHITE)
                    .append(Component.translatable("gui.back", NamedTextColor.WHITE))
                screen.exitButton?.tooltip shouldBe Component.translatable("gui.back", NamedTextColor.WHITE)
                repeat(3) {
                    click(PaperDialogActionId.of("confirm"))
                    click(PaperDialogActionId.of("buy"))
                    screen.id shouldBe "shop"
                }
                click(requireNotNull(screen.exitButton).id)
                screen.id shouldBe "panel"
                verify(exactly = 0) { player.closeDialog() }
                val backAttempt = observed.last { it["phase"] == "click" }
                val backScope = observed.filter { it["attemptId"] == backAttempt["attemptId"] && it["historyFooter"] == true }
                backScope.map { it["phase"] } shouldBe listOf("dispatch_start", "dispatch_end")
                val panelOpen = observed.last { it["phase"] == "open" }
                panelOpen["triggerAttemptId"] shouldBe backAttempt["attemptId"]
                observed.subList(observed.indexOf(backScope.first()) + 1, observed.indexOf(backScope.last())) shouldBe listOf(panelOpen)
                click(requireNotNull(screen.exitButton).id)
                verify(exactly = 1) { player.closeDialog() }
                observed.single { it["phase"] == "close" }["reason"] shouldBe "footer_close"
                observed.single { it["phase"] == "result" } shouldBe observed.last { it["phase"] == "click" } + mapOf(
                    "phase" to "result", "outcome" to "succeeded", "reason" to "footer_close",
                )

                runtime.beginFlow(player)
                runtime.openInFlow(player, PaperDialogScreen(Component.text("Panel"), buttons = emptyList(), id = "panel"))
                val closeFailure = IllegalStateException("native close failed")
                every { player.closeDialog() } throws closeFailure
                shouldThrow<IllegalStateException> { click(requireNotNull(screen.exitButton).id) } shouldBe closeFailure
                observed.count { it["phase"] == "close" } shouldBe 1
                observed.last { it["phase"] == "result" }["outcome"] shouldBe "failed"
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
                    val customLabel = Component.text("Back", NamedTextColor.WHITE).font(Key.key("test:custom_footer"))
                    val customTooltip = Component.text("Configured return")
                    runtime.openInFlow(player, PaperDialogScreen(Component.text("Information"), buttons = emptyList(),
                        exitButton = if (customFooter) PaperDialogButton(PaperDialogActionId.of("back"), customLabel, customTooltip, 178) {
                            error("Semantic footer must be replaced by actual history")
                        } else null))
                    displayed.buttons shouldBe emptyList()
                    val footer = requireNotNull(displayed.exitButton)
                    if (customFooter) {
                        footer.label shouldBe customLabel
                        footer.tooltip shouldBe customTooltip
                        footer.width shouldBe 178
                    }
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
                    runtime.openInFlow(player, PaperDialogScreen(Component.text("Saved $value"), id = "saved",
                        buttons = listOf(button("settings") {
                            // Public entry helpers called from a menu must not reset its history.
                            runtime.beginFlow(player)
                            runtime.openInFlow(player, PaperDialogScreen(Component.text("Settings"), id = "settings",
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
                runtime.openRoot(player, PaperDialogScreen(Component.text("Direct settings"), id = "settings",
                    buttons = listOf(button("noop") {}), exitButton = button("back") { semanticParentCalls++ }))
                click("back")
                verify(exactly = 2) { player.closeDialog() }
                semanticParentCalls shouldBe 0

                runtime.beginFlow(player)
                root()
                click("settings")
                runtime.openInFlow(player, displayed.copy(exitButton = null), null, {}, true)
                displayed.exitButton?.label shouldBe Component.translatable("mco.selectServer.close", NamedTextColor.WHITE)
                displayed.exitButton?.tooltip shouldBe displayed.exitButton?.label
                click(requireNotNull(displayed.exitButton).id.value)
                verify(exactly = 3) { player.closeDialog() }
            }
        }
    }
})
