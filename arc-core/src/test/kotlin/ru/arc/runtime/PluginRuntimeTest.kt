package ru.arc.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.core.ExecutorTaskScheduler
import ru.arc.core.TestTaskScheduler
import ru.arc.observability.RuntimeEvent
import ru.arc.observability.RuntimeEventType
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthSnapshot

class PluginRuntimeTest : FreeSpec({
    "one runtime owns lifecycle events health tasks and reverse resource shutdown" {
        val events = mutableListOf<RuntimeEvent>()
        val health = mutableListOf<RuntimeHealthSnapshot>()
        val closed = mutableListOf<String>()
        val runtime = PluginRuntime("agentic-plugin", ExecutorTaskScheduler(), events::add, health::add)

        runtime.start("version" to "1.0")
        runtime.own(AutoCloseable { closed += "first" })
        runtime.own(AutoCloseable { closed += "second" })
        runtime.registerHealth("storage") {
            RuntimeHealthContribution(recoveryBacklog = 2, schemas = mapOf("journal" to 3))
        }
        runtime.ready("server" to "lab")

        events.map(RuntimeEvent::type) shouldContainExactly
            listOf(RuntimeEventType.PLUGIN_BOOTSTRAP, RuntimeEventType.PLUGIN_READY)
        health.single().recoveryBacklog shouldBe 2
        health.single().schemas shouldBe mapOf("storage.journal" to 3)
        runtime.state shouldBe PluginRuntimeState.ACTIVE

        runtime.close()
        runtime.close()
        closed shouldContainExactly listOf("second", "first")
        runtime.state shouldBe PluginRuntimeState.CLOSED
    }

    "runtime rejects duplicate resources and invalid lifecycle order" {
        val runtime = PluginRuntime("test-plugin", ExecutorTaskScheduler(), {}, {})
        shouldThrow<IllegalStateException> { runtime.ready() }
        runtime.start()
        val resource = AutoCloseable { }
        runtime.own(resource)
        shouldThrow<IllegalStateException> { runtime.own(resource) }
        runtime.close()
        shouldThrow<IllegalStateException> { runtime.start() }
    }

    "periodic health reports are runtime owned and stop on close" {
        val scheduler = TestTaskScheduler()
        val health = mutableListOf<RuntimeHealthSnapshot>()
        val runtime = PluginRuntime("periodic-plugin", scheduler, {}, health::add)
        runtime.start()
        runtime.registerHealth("queue") { RuntimeHealthContribution(recoveryBacklog = 4) }
        runtime.ready()
        runtime.reportHealthEvery(periodTicks = 20, initialDelayTicks = 10)

        scheduler.tick(9)
        health.size shouldBe 1
        scheduler.tick(1)
        health.size shouldBe 2
        health.last().recoveryBacklog shouldBe 4

        runtime.close()
        scheduler.tick(40)
        health.size shouldBe 2
    }
})
