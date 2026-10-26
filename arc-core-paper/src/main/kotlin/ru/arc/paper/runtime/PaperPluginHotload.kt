package ru.arc.paper.runtime

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.PluginCommand
import org.bukkit.command.RemoteConsoleCommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import ru.arc.observability.StructuredDebugLine
import ru.arc.runtime.PluginHotload
import ru.arc.runtime.PluginHotloadStatus
import java.util.Locale
import java.util.concurrent.CompletionStage

/**
 * Console-only prepare/status adapter, independent of the gameplay runtime.
 *
 * Construction is passive, including when used in a JavaPlugin field initializer.
 * Call [beginGeneration] before creating a runtime, then [register]. Both prepare
 * and disable call [prepare]; only actual disable calls [close]. Do not register
 * this adapter as a resource of the gameplay runtime: prepare must retain status.
 * The stop callback owns gameplay command/listener/service cleanup and returns
 * its real named drains. No reload, loader hook, reflection, or resource registry
 * is installed here.
 *
 * Lifecycle methods run on Paper's primary thread. [status] is non-blocking and
 * thread-safe after initial metadata capture. [close] removes only this adapter's
 * command-map identities and detaches its callback, even if another owner has
 * replaced a label. A successful next generation may register a fresh command.
 */
class PaperPluginHotload(
    private val plugin: Plugin,
    stop: () -> Map<String, CompletionStage<*>>,
) : AutoCloseable {
    private val delegate by lazy { PluginHotload(plugin.pluginMeta.version, stop) }
    private val namespace by lazy { plugin.name.lowercase(Locale.ROOT) }
    private var registration: Registration? = null

    fun beginGeneration() {
        requirePrimaryThread()
        delegate.beginGeneration()
    }

    /** Registers only `<plugin name lowercase>:hotload prepare|status`. */
    fun register() {
        requirePrimaryThread()
        if (registration != null) return
        val commandMap = plugin.server.commandMap
        check(commandMap.getCommand("$namespace:hotload") == null) {
            "Hotload command $namespace:hotload is already registered"
        }
        val command = HotloadCommand(namespace, this)
        registration = Registration(commandMap, command)
        try {
            commandMap.register(namespace, command)
            check(commandMap.getCommand("$namespace:hotload") === command) {
                "Could not register hotload command $namespace:hotload"
            }
            // Each plugin has its own endpoint; never claim the global hotload label.
            commandMap.knownCommands.remove("hotload", command)
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun prepare(): CompletionStage<Unit> {
        requirePrimaryThread()
        return delegate.prepare()
    }

    fun status(): PluginHotloadStatus = delegate.status()

    /**
     * Retires standard Bukkit gameplay registrations after the domain owner has
     * stopped and captured its drains. This is explicit: accepted operations
     * must release any required tasks/channels before calling it. It attempts
     * every cleanup and reports failures together. Domain-specific resources
     * still belong to their existing owner.
     *
     * Descriptor command registrations stay available for a later enable, but
     * their old executors/completers are replaced by detached inert handlers.
     * Passing null to PluginCommand.setExecutor would restore plugin.onCommand.
     * This adapter's maintenance command is a separate Command and survives.
     */
    fun retireGameplayRegistrations() {
        requirePrimaryThread()
        val server = plugin.server
        val failures = mutableListOf<Throwable>()
        fun attempt(action: () -> Unit) {
            try { action() } catch (failure: Throwable) { failures += failure }
        }
        attempt { HandlerList.unregisterAll(plugin) }
        attempt { server.servicesManager.unregisterAll(plugin) }
        attempt { server.messenger.unregisterIncomingPluginChannel(plugin) }
        attempt { server.messenger.unregisterOutgoingPluginChannel(plugin) }
        attempt {
            server.commandMap.knownCommands.values.filterIsInstance<PluginCommand>()
                .filter { it.plugin === plugin }.distinct().forEach { command ->
                    attempt { command.setExecutor(RETIRED_EXECUTOR) }
                    attempt { command.tabCompleter = RETIRED_COMPLETER }
                }
        }
        attempt { server.scheduler.cancelTasks(plugin) }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Could not retire every plugin gameplay registration", failures.first()).apply {
                failures.drop(1).forEach(::addSuppressed)
            }
        }
    }

    /** Unregisters the administrative endpoint; never starts or resets cleanup. */
    override fun close() {
        requirePrimaryThread()
        val owned = registration ?: return
        registration = null
        owned.command.detach()
        try {
            val commands = owned.commandMap.knownCommands
            commands.entries.filter { it.value === owned.command }.map { it.key }
                .forEach { commands.remove(it, owned.command) }
        } finally {
            owned.command.unregister(owned.commandMap)
        }
    }

    private fun requirePrimaryThread() {
        check(plugin.server.isPrimaryThread) { "Paper hotload lifecycle requires the primary thread" }
    }

    private data class Registration(val commandMap: CommandMap, val command: HotloadCommand)

    private companion object {
        val RETIRED_EXECUTOR = CommandExecutor { _, _, _, _ -> true }
        val RETIRED_COMPLETER = TabCompleter { _, _, _, _ -> emptyList() }
    }

    private class HotloadCommand(
        namespace: String,
        private var owner: PaperPluginHotload?,
    ) : Command("hotload", "Prepare this plugin for replacement", "/$namespace:hotload <prepare|status>", emptyList()) {
        private val pluginName = namespace
        private val requiredPermission = "$namespace.hotload"
        private val renderer = StructuredDebugLine("ARC_HOTLOAD", maxFields = 70)

        init {
            permission = requiredPermission
        }

        fun detach() {
            owner = null
        }

        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
            val current = owner ?: return false
            if (!isConsole(sender) || !sender.hasPermission(requiredPermission)) {
                sender.sendMessage("This command requires an authorized console.")
                return true
            }
            if (args.size != 1 || args[0] !in listOf("prepare", "status")) {
                sender.sendMessage(usage)
                return true
            }
            if (args[0] == "prepare") current.prepare()
            val status = current.status()
            val fields = mutableListOf<Pair<String, Any?>>(
                "schema" to 1,
                "plugin" to pluginName,
                "version" to status.version,
                "state" to status.state.name.lowercase(Locale.ROOT),
                "failure" to status.failure?.javaClass?.simpleName,
            )
            status.phases.forEach { (name, phase) ->
                fields += "phase.$name" to phase.state.name.lowercase(Locale.ROOT)
                fields += "phase.$name.failure" to phase.failure?.javaClass?.simpleName
            }
            sender.sendMessage(renderer.line(fields))
            return true
        }

        override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> {
            if (owner == null || !isConsole(sender) || !sender.hasPermission(requiredPermission) || args.size != 1) {
                return emptyList()
            }
            return listOf("prepare", "status").filter { it.startsWith(args[0]) }
        }

        private fun isConsole(sender: CommandSender): Boolean =
            sender is ConsoleCommandSender || sender is RemoteConsoleCommandSender
    }
}
