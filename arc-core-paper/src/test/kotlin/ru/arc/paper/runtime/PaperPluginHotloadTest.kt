package ru.arc.paper.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.CommandExecutor
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.PluginCommand
import org.bukkit.command.RemoteConsoleCommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.runtime.PluginHotloadState
import java.util.concurrent.CompletableFuture

class PaperPluginHotloadTest : FreeSpec({
    "construction does not touch plugin metadata or the server" {
        val plugin = mockk<Plugin>()
        PaperPluginHotload(plugin) { emptyMap() }
        verify { plugin wasNot Called }
    }

    "namespaced command remains usable after prepare and waits for its real drain" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcHotload")
            val drain = CompletableFuture<Unit>()
            var calls = 0
            val hotload = PaperPluginHotload(plugin) { calls++; mapOf("storage" to drain) }
            hotload.beginGeneration()
            hotload.register()
            hotload.register()
            val commands = paper.server.commandMap
            val command = requireNotNull(commands.getCommand("archotload:hotload"))
            commands.getCommand("hotload") shouldBe null
            val console = authorizedConsole()

            commands.dispatch(console, "archotload:hotload prepare") shouldBe true
            commands.dispatch(console, "archotload:hotload prepare") shouldBe true
            calls shouldBe 1
            hotload.status().state shouldBe PluginHotloadState.PREPARING
            verify { console.sendMessage(match<String> { "state=preparing" in it && "phase.storage=preparing" in it }) }

            drain.complete(Unit)
            commands.dispatch(console, "archotload:hotload status") shouldBe true
            verify { console.sendMessage(match<String> { "state=ready" in it && "phase.storage=ready" in it }) }
            hotload.close()
            commands.getCommand("archotload:hotload") shouldBe null
            command.execute(console, "archotload:hotload", arrayOf("prepare")) shouldBe false
            calls shouldBe 1
        }
    }

    "only permitted local or remote consoles can prepare and syntax is exact" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcHotload")
            var calls = 0
            val hotload = PaperPluginHotload(plugin) { calls++; emptyMap() }
            hotload.beginGeneration()
            hotload.register()
            val command = requireNotNull(paper.server.commandMap.getCommand("archotload:hotload"))
            val player = paper.addPlayer("Operator").apply { isOp = true }
            val denied = mockk<ConsoleCommandSender>(relaxed = true)
            every { denied.hasPermission("archotload.hotload") } returns false
            val console = authorizedConsole()

            command.execute(player, "archotload:hotload", arrayOf("prepare")) shouldBe true
            command.execute(denied, "archotload:hotload", arrayOf("prepare")) shouldBe true
            listOf(emptyArray(), arrayOf("Prepare"), arrayOf("prepare", "now"), arrayOf("reload")).forEach { args ->
                command.execute(console, "archotload:hotload", args) shouldBe true
            }
            calls shouldBe 0
            command.tabComplete(player, "archotload:hotload", arrayOf("")) shouldBe emptyList()
            command.tabComplete(console, "archotload:hotload", arrayOf("st")) shouldBe listOf("status")

            val remote = mockk<RemoteConsoleCommandSender>(relaxed = true)
            every { remote.hasPermission("archotload.hotload") } returns true
            command.execute(remote, "archotload:hotload", arrayOf("prepare")) shouldBe true
            calls shouldBe 1
            hotload.status().state shouldBe PluginHotloadState.READY
            hotload.close()
        }
    }

    "close removes exact owned identities preserves replacements and permits the next registration" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcHotload")
            var calls = 0
            val hotload = PaperPluginHotload(plugin) { calls++; emptyMap() }
            hotload.beginGeneration()
            hotload.register()
            val commands = paper.server.commandMap
            val old = requireNotNull(commands.getCommand("archotload:hotload"))
            val replacement = object : Command("replacement") {
                override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>) = true
            }
            commands.knownCommands["archotload:hotload"] = replacement
            commands.knownCommands["owned-extra-label"] = old
            hotload.prepare().toCompletableFuture().join()
            hotload.close()
            hotload.close()

            commands.getCommand("archotload:hotload") shouldBe replacement
            commands.knownCommands.values.none { it === old } shouldBe true
            old.isRegistered shouldBe false
            old.execute(authorizedConsole(), "hotload", arrayOf("prepare")) shouldBe false
            old.tabComplete(authorizedConsole(), "hotload", arrayOf("")) shouldBe emptyList()
            calls shouldBe 1

            commands.knownCommands.remove("archotload:hotload", replacement)
            hotload.beginGeneration()
            hotload.register()
            val fresh = requireNotNull(commands.getCommand("archotload:hotload"))
            (fresh === old) shouldBe false
            fresh.execute(authorizedConsole(), "archotload:hotload", arrayOf("prepare")) shouldBe true
            calls shouldBe 2
            hotload.close()
        }
    }

    "a namespaced registration collision never replaces another command" {
        MockBukkitTestRuntime.open().use { paper ->
            val first = PaperPluginHotload(paper.createSimplePlugin("ArcHotload")) { emptyMap() }
            val second = PaperPluginHotload(paper.createSimplePlugin("ArcHotload")) { emptyMap() }
            first.beginGeneration()
            first.register()
            val original = paper.server.commandMap.getCommand("archotload:hotload")

            shouldThrow<IllegalStateException> { second.register() }
            paper.server.commandMap.getCommand("archotload:hotload") shouldBe original
            second.close()
            paper.server.commandMap.getCommand("archotload:hotload") shouldBe original
            first.close()
        }
    }
    "explicit gameplay retirement closes native registrations while preserving maintenance and other owners" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcHotload")
            val other = paper.createSimplePlugin("Other")
            val hotload = PaperPluginHotload(plugin) { emptyMap() }
            hotload.beginGeneration()
            hotload.register()
            val listener = object : Listener {
                @EventHandler
                fun join(event: PlayerJoinEvent) = Unit
            }
            paper.server.pluginManager.registerEvents(listener, plugin)
            paper.server.servicesManager.register(Runnable::class.java, Runnable {}, plugin, ServicePriority.Normal)
            paper.server.servicesManager.register(CharSequence::class.java, "other", other, ServicePriority.Normal)
            paper.server.messenger.registerIncomingPluginChannel(plugin, "arc:incoming") { _, _, _ -> }
            paper.server.messenger.registerOutgoingPluginChannel(plugin, "arc:outgoing")
            var ownTicks = 0
            var otherTicks = 0
            paper.server.scheduler.runTaskTimer(plugin, Runnable { ownTicks++ }, 1L, 1L)
            paper.server.scheduler.runTaskTimer(other, Runnable { otherTicks++ }, 1L, 1L)
            var gameplayCalls = 0
            var executor = CommandExecutor { _, _, _, _ -> gameplayCalls++; true }
            var completer = TabCompleter { _, _, _, _ -> gameplayCalls++; listOf("old") }
            val gameplay = mockk<PluginCommand>(relaxed = true)
            every { gameplay.plugin } returns plugin
            every { gameplay.setExecutor(any()) } answers { executor = firstArg() }
            every { gameplay.setTabCompleter(any()) } answers { completer = firstArg() }
            paper.server.commandMap.knownCommands["gameplay"] = gameplay

            hotload.retireGameplayRegistrations()
            hotload.retireGameplayRegistrations()
            HandlerList.getRegisteredListeners(plugin).isEmpty() shouldBe true
            paper.server.servicesManager.getRegistrations(plugin).isEmpty() shouldBe true
            paper.server.servicesManager.load(CharSequence::class.java) shouldBe "other"
            paper.server.messenger.getIncomingChannels(plugin).isEmpty() shouldBe true
            paper.server.messenger.getOutgoingChannels(plugin).isEmpty() shouldBe true
            paper.performTicks(2)
            ownTicks shouldBe 0
            otherTicks shouldBe 2
            executor.onCommand(authorizedConsole(), gameplay, "gameplay", emptyArray()) shouldBe true
            completer.onTabComplete(authorizedConsole(), gameplay, "gameplay", emptyArray()) shouldBe emptyList()
            gameplayCalls shouldBe 0
            paper.server.commandMap.getCommand("archotload:hotload")!!.execute(
                authorizedConsole(), "archotload:hotload", arrayOf("status"),
            ) shouldBe true
            hotload.close()
            paper.server.commandMap.knownCommands.remove("gameplay", gameplay)
        }
    }
})

private fun authorizedConsole(): ConsoleCommandSender = mockk<ConsoleCommandSender>(relaxed = true).also {
    every { it.hasPermission("archotload.hotload") } returns true
}
