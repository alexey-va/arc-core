package ru.arc.paper.testing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.java.JavaPlugin
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.concurrent.atomic.AtomicInteger

class MockBukkitTestRuntimeTest : FreeSpec({
    afterEach {
        if (MockBukkit.isMocked()) MockBukkit.unmock()
        HarnessPlugin.enabled.set(0)
        HarnessPlugin.disabled.set(0)
    }

    "owns and releases the global Bukkit singleton idempotently" {
        val runtime = MockBukkitTestRuntime.open()
        runtime.isOpen shouldBe true
        MockBukkit.getMock() shouldBe runtime.server

        runtime.close()
        runtime.close()
        runtime.isOpen shouldBe false
        MockBukkit.isMocked() shouldBe false
        shouldThrow<IllegalStateException> { runtime.addPlayer("Closed") }
    }

    "rejects nested ownership without disturbing the active runtime" {
        MockBukkitTestRuntime.open().use { runtime ->
            shouldThrow<IllegalStateException> { MockBukkitTestRuntime.open() }
            MockBukkit.getMock() shouldBe runtime.server
            runtime.isOpen shouldBe true
        }
    }

    "use closes the runtime when the test body fails" {
        shouldThrow<IllegalStateException> {
            MockBukkitTestRuntime.open().use {
                error("test failure")
            }
        }
        MockBukkit.isMocked() shouldBe false
    }

    "creates a bounded generic plugin without transferring lifecycle ownership" {
        MockBukkitTestRuntime.open().use { runtime ->
            runtime.createSimplePlugin("AgenticFixture").name shouldBe "AgenticFixture"
            runtime.isOpen shouldBe true
        }
    }

    "provides deterministic players, worlds, events, plugin lifecycle and ticks" {
        val executions = AtomicInteger()
        MockBukkitTestRuntime.open().use { runtime ->
            val plugin = runtime.loadSimplePlugin<HarnessPlugin>()
            HarnessPlugin.enabled.get() shouldBe 1
            val world = runtime.addSimpleWorld("fixture")
            val player = runtime.addPlayer("Agent")
            val destination = Location(world, 10.0, 70.0, -4.0)
            runtime.server.pluginManager.registerEvents(
                object : Listener {
                    @EventHandler
                    fun onTeleport(event: PlayerTeleportEvent) {
                        event.isCancelled = true
                    }
                },
                plugin,
            )

            val event = PlayerTeleportEvent(player, player.location, destination)
            runtime.callEvent(event) shouldBe event
            event.isCancelled shouldBe true

            runtime.server.scheduler.runTaskLater(plugin, Runnable { executions.incrementAndGet() }, 2L)
            runtime.performTicks(1)
            executions.get() shouldBe 0
            runtime.performTicks(1)
            executions.get() shouldBe 1
            shouldThrow<IllegalArgumentException> { runtime.performTicks(-1) }
        }
        HarnessPlugin.disabled.get() shouldBe 1
    }
}) {
    open class HarnessPlugin : JavaPlugin() {
        override fun onEnable() {
            enabled.incrementAndGet()
        }

        override fun onDisable() {
            disabled.incrementAndGet()
        }

        companion object {
            val enabled = AtomicInteger()
            val disabled = AtomicInteger()
        }
    }
}
