package ru.arc.paper.testing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.inventory.ItemStack
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

    "fails closed when MockBukkit would otherwise abort and skip a scenario" {
        val failure = shouldThrow<AssertionError> {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { runtime ->
                    runtime.addPlayer("Agent").showDemoScreen()
                }
            }
        }

        failure.message shouldBe "MockBukkit scenario reached an unsupported Paper API operation"
        failure.cause!!::class shouldBe org.mockbukkit.mockbukkit.exception.UnimplementedOperationException::class
        MockBukkit.isMocked() shouldBe false
    }

    "patches declared MockBukkit gaps and records inherited Adventure titles" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("Agent")
            val world = runtime.addSimpleWorld("patched")

            player.saveData()
            runtime.playerDataSaveCount(player) shouldBe 1

            val title = Title.title(Component.text("Five"), Component.text("Seconds"))
            player.showTitle(title)
            runtime.adventureTitles(player) shouldBe listOf(title)

            world.getBlockAt(0, 100, 0).isPassable shouldBe true
            world.getBlockAt(0, 100, 0).setType(Material.STONE)
            world.getBlockAt(0, 100, 0).isPassable shouldBe false

            val destination = Location(world, 4.0, 80.0, -2.0)
            player.teleportAsync(destination).join() shouldBe true
            player.location shouldBe destination

            val item = ItemStack.of(Material.DIAMOND, 2)
            item.effectiveName() shouldBe Component.translatable(item.translationKey())
            item.asHoverEvent().value().count() shouldBe 2
        }
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
