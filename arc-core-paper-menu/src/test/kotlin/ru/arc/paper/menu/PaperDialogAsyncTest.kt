package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.event.player.PlayerCustomClickEvent
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperDialogAsyncTest : FreeSpec({
    "large screens share one tick budget and publish only complete owning-thread sessions" {
        MockBukkitTestRuntime.open().use { paper ->
            val scheduler = TestTaskScheduler()
            val scope = LifecycleTaskScope(scheduler)
            val work = BudgetedTaskQueue(scope, maximumSteps = 8, nanoTime = { 0 })
            var reads = 0
            val shown = mutableListOf<PaperDialogSessionRegistration>()
            PaperDialogRuntime(paper.createSimplePlugin("AsyncDialogs")) { _, _, registration ->
                Bukkit.isPrimaryThread() shouldBe true
                shown += registration
            }.use { runtime ->
                val results = (1..2).map { number ->
                    val player = paper.addPlayer("Dialog$number")
                    val buttons = countedButtons(300) { reads++ }
                    val screen = PaperDialogScreen(Component.text("Large"), buttons = buttons)
                    runtime.beginFlow(player)
                    runtime.openAsync(player, screen, work)
                }
                reads = 0 // Screen model validation precedes admission; it can run on the consumer worker.
                shown.size shouldBe 0
                scheduler.tick()
                reads shouldBe 8
                shown.size shouldBe 0
                results.all { !it.isDone } shouldBe true
                scheduler.tick(100)
                shown.size shouldBe 2
                shown.all { it.actions.size == 301 } shouldBe true
                results.all { it.isDone && !it.isCompletedExceptionally } shouldBe true
                reads shouldBe 600
            }
            work.close()
            scope.close()
        }
    }

    "back dismisses a partial preparation without adding a history visit or binding actions" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = spyk(paper.addPlayer("PartialDialog"))
            every { player.closeDialog() } just Runs
            val scheduler = TestTaskScheduler()
            val scope = LifecycleTaskScope(scheduler)
            val work = BudgetedTaskQueue(scope, maximumSteps = 2, nanoTime = { 0 })
            val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
            lateinit var visible: PaperDialogSessionRegistration
            var presentations = 0
            var clicks = 0
            PaperDialogRuntime(paper.createSimplePlugin("PartialDialogs")) { _, _, registration ->
                visible = registration
                presentations++
            }.use { runtime ->
                val back = PaperDialogActionId.of("back")
                runtime.beginFlow(player)
                runtime.open(player, PaperDialogScreen(Component.text("Pending"), buttons = emptyList(),
                    exitButton = PaperDialogButton(back, Component.text("Back")) {}))
                val result = runtime.openAsync(player, largeScreen { clicks++ }, work)
                scheduler.tick()
                presentations shouldBe 1
                runtime.onCustomClick(mockk<PlayerCustomClickEvent> {
                    every { commonConnection } returns connection
                    every { identifier } returns Key.key(visible.key(back))
                    every { dialogResponseView } returns null
                })
                result.isCompletedExceptionally shouldBe true
                scheduler.tick(200)
                presentations shouldBe 1
                clicks shouldBe 0
                verify(exactly = 1) { player.closeDialog() }
            }
            work.close()
            scope.close()
        }
    }

    "replacement, flow reset and runtime close cancel partial native preparation" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("SupersededDialog")
            val scheduler = TestTaskScheduler()
            val scope = LifecycleTaskScope(scheduler)
            val work = BudgetedTaskQueue(scope, maximumSteps = 2, nanoTime = { 0 })
            var presentations = 0
            PaperDialogRuntime(paper.createSimplePlugin("SupersededDialogs")) { _, _, _ -> presentations++ }.use { runtime ->
                runtime.beginFlow(player)
                val first = runtime.openAsync(player, largeScreen(), work)
                scheduler.tick()
                val second = runtime.openAsync(player, largeScreen(), work)
                first.isCompletedExceptionally shouldBe true
                runtime.beginFlow(player)
                second.isCompletedExceptionally shouldBe true
                val third = runtime.openAsync(player, largeScreen(), work)
                scheduler.tick()
                runtime.close()
                third.isCompletedExceptionally shouldBe true
                scheduler.tick(200)
                presentations shouldBe 0
            }
            work.close()
            scope.close()
        }
    }

    "a stale domain generation cannot bind even after all buttons were prepared" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("StaleDialog")
            val scheduler = TestTaskScheduler()
            val scope = LifecycleTaskScope(scheduler)
            val work = BudgetedTaskQueue(scope, maximumSteps = 1, nanoTime = { 0 })
            var current = true
            var presentations = 0
            PaperDialogRuntime(paper.createSimplePlugin("StaleDialogs")) { _, _, _ -> presentations++ }.use { runtime ->
                runtime.beginFlow(player)
                val result = runtime.openAsync(player, PaperDialogScreen(Component.text("One"),
                    buttons = listOf(PaperDialogButton(PaperDialogActionId.of("one"), Component.text("One")) {})),
                    work, { current })
                scheduler.tick(3) // button, unique footer id, footer; presentation has not started.
                current = false
                scheduler.tick(10)
                result.isCompletedExceptionally shouldBe true
                presentations shouldBe 0
            }
            work.close()
            scope.close()
        }
    }
})

private fun countedButtons(count: Int, read: () -> Unit): List<PaperDialogButton> = object : AbstractList<PaperDialogButton>() {
    override val size = count
    override fun get(index: Int): PaperDialogButton {
        read()
        return PaperDialogButton(PaperDialogActionId.of("item_$index"), Component.text("Item $index")) {}
    }
}

private fun largeScreen(clicked: () -> Unit = {}): PaperDialogScreen = PaperDialogScreen(
    Component.text("Large"), buttons = List(300) { index ->
        PaperDialogButton(PaperDialogActionId.of("item_$index"), Component.text("Item $index")) { clicked() }
    },
)
