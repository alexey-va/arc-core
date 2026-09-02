package ru.arc.paper.menu

import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.event.player.PlayerCustomClickEvent
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.api.BinaryTagHolder
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin

/**
 * Shows native Paper dialogs and dispatches their custom-click actions.
 *
 * Sessions are bounded to one per player and each action is consumed once.
 * This deliberately uses one Bukkit event listener instead of Paper callback
 * registrations, whose lifecycle is independent from the dialog on screen.
 */
class PaperDialogRuntime(private val plugin: Plugin) : AutoCloseable, Listener {
    private val sessions = PaperDialogSessionStore(plugin.name)
    private var closed = false

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun open(player: Player, screen: PaperDialogScreen) {
        requirePrimaryThread()
        check(!closed) { "Paper dialog runtime is closed" }

        val actions = (screen.buttons + listOfNotNull(screen.exitButton)).associate { button ->
            button.id to {
                player.closeDialog()
                button.onClick.handle(
                    PaperDialogClickContext(player) { input -> currentResponse.get()?.getText(input.value) },
                )
            }
        }
        val registration = sessions.replace(player.uniqueId, actions)

        try {
            val dialog = createDialog(screen, registration)
            player.showDialog(dialog)
        } catch (failure: Throwable) {
            sessions.remove(player.uniqueId)
            throw failure
        }
    }

    @EventHandler
    fun onCustomClick(event: PlayerCustomClickEvent) {
        requirePrimaryThread()
        if (closed) return
        val connection = event.commonConnection as? PlayerGameConnection ?: return
        val player = connection.player
        val handler = sessions.consume(player.uniqueId, event.identifier.asString()) ?: return

        val response = event.dialogResponseView
        // The generated handler reads through this event-owned view immediately.
        currentResponse.set(response)
        try {
            handler()
        } finally {
            currentResponse.remove()
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        sessions.remove(event.player.uniqueId)
    }

    override fun close() {
        requirePrimaryThread()
        if (closed) return
        closed = true
        sessions.clear()
        HandlerList.unregisterAll(this)
    }

    private fun createDialog(
        screen: PaperDialogScreen,
        registration: PaperDialogSessionRegistration,
    ): Dialog = Dialog.create { factory ->
        val base = DialogBase.builder(screen.title)
            .externalTitle(screen.externalTitle)
            .canCloseWithEscape(screen.canCloseWithEscape)
            .pause(screen.pause)
            .afterAction(DialogBase.DialogAfterAction.NONE)
            .body(screen.body.map { DialogBody.plainMessage(it.text, it.width) })
            .inputs(screen.inputs.map(::createInput))
            .build()
        val buttons = screen.buttons.map { createButton(it, registration) }
        val type = DialogType.multiAction(buttons)
            .columns(screen.columns)
            .apply { screen.exitButton?.let { exitAction(createButton(it, registration)) } }
            .build()
        factory.empty().base(base).type(type)
    }

    private fun createInput(input: PaperDialogTextInput): DialogInput =
        DialogInput.text(input.id.value, input.label)
            .width(input.width)
            .labelVisible(input.labelVisible)
            .initial(input.initial)
            .maxLength(input.maxLength)
            .build()

    private fun createButton(
        button: PaperDialogButton,
        registration: PaperDialogSessionRegistration,
    ): ActionButton = ActionButton.builder(button.label)
        .tooltip(button.tooltip)
        .width(button.width)
        .action(
            DialogAction.customClick(
                Key.key(registration.key(button.id)),
                BinaryTagHolder.binaryTagHolder("{}"),
            ),
        )
        .build()

    private fun requirePrimaryThread() {
        check(Bukkit.isPrimaryThread()) { "Paper dialog runtime must be used on the primary server thread" }
    }

    private companion object {
        val currentResponse = ThreadLocal<DialogResponseView>()
    }
}
