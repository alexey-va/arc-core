package ru.arc.paper.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.BukkitTaskScheduler
import ru.arc.observability.RuntimeEvent
import ru.arc.observability.RuntimeEventType
import ru.arc.paper.player.TestPaperPlugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import java.util.concurrent.atomic.AtomicInteger

class PaperPluginRuntimeTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var plugin: TestPaperPlugin

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        plugin = paper.loadPlugin<TestPaperPlugin>()
    }

    afterEach { paper.close() }

    "start, reload and close visibly own task epochs" {
        val events = mutableListOf<RuntimeEvent>()
        val runtime = PaperPluginRuntime(plugin, "test-plugin", BukkitTaskScheduler(plugin), events::add)
        val first = runtime.start("server" to "test")
        val calls = AtomicInteger()
        runtime.tasks.runLater(first, 2) { calls.incrementAndGet() }

        runtime.reload()
        paper.performTicks(2)
        calls.get() shouldBe 0
        runtime.tasks.trackedTaskCount() shouldBe 0

        runtime.ready("features" to 3)
        events.map(RuntimeEvent::type) shouldBe listOf(RuntimeEventType.PLUGIN_BOOTSTRAP, RuntimeEventType.PLUGIN_READY)
        runtime.close()
        runtime.state shouldBe PaperPluginRuntimeState.CLOSED
    }

    "resources close in reverse order exactly once" {
        val runtime = PaperPluginRuntime(plugin, "test-plugin", BukkitTaskScheduler(plugin)) { }
        val closed = mutableListOf<String>()
        val first = AutoCloseable { closed += "first" }
        val second = AutoCloseable { closed += "second" }
        runtime.own(first)
        runtime.own(second)
        shouldThrow<IllegalStateException> { runtime.own(first) }

        runtime.start()
        runtime.close()
        runtime.close()

        closed shouldBe listOf("second", "first")
        runtime.ownedResourceCount() shouldBe 0
    }

    "runtime cannot reopen after terminal close" {
        val runtime = PaperPluginRuntime(plugin, "test-plugin", BukkitTaskScheduler(plugin)) { }
        runtime.close()

        shouldThrow<IllegalStateException> { runtime.start() }
        shouldThrow<IllegalStateException> { runtime.own(AutoCloseable {}) }
    }
})
