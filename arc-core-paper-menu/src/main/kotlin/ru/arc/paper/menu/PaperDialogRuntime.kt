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
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.plugin.Plugin
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Shows native Paper dialogs and dispatches their custom-click actions.
 *
 * Sessions are bounded to one per player and each action is consumed once.
 * This deliberately uses one Bukkit event listener instead of Paper callback
 * registrations, whose lifecycle is independent from the dialog on screen.
 */
class PaperDialogRuntime(private val plugin: Plugin) : AutoCloseable, Listener {
    private val sessions = PaperDialogSessionStore(plugin.name)
    private val observations = mutableMapOf<UUID, DialogObservation>()
    private data class Visit(val screen: PaperDialogScreen, val reopen: (() -> Unit)?, val onDismiss: () -> Unit)
    private val history = PaperDialogHistory<Visit>()
    private var dispatching: UUID? = null
    private var closed = false

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun open(player: Player, screen: PaperDialogScreen) {
        open(player, screen, null, {})
    }

    /**
     * A callback transition records a child; an asynchronous completion replaces
     * the current visit. [reopen] refreshes a restored screen's domain state.
     * [onDismiss] must invalidate pending work on both Back and Close.
     * Escape uses actual history by default; an explicitly closing footer still
     * closes the complete flow. Vanilla Escape itself closes on the client, so
     * unlike ordinary buttons it cannot guarantee mouse-position preservation.
     */
    fun open(player: Player, screen: PaperDialogScreen, reopen: (() -> Unit)?, onDismiss: () -> Unit) {
        requirePrimaryThread()
        check(!closed) { "Paper dialog runtime is closed" }

        val key = if (screen.id == "dialog") screen.title else screen.id
        history.show(player.uniqueId, key, Visit(screen, reopen, onDismiss), dispatching == player.uniqueId)
        render(player, screen)
    }

    /** Start a command/hotkey entry with no invented ancestors; internal actions keep their flow. */
    fun beginFlow(player: Player) {
        requirePrimaryThread()
        if (dispatching == player.uniqueId) return
        history.current(player.uniqueId)?.onDismiss?.invoke()
        history.remove(player.uniqueId)
        sessions.remove(player.uniqueId)
        observations.remove(player.uniqueId)
    }

    private fun render(player: Player, original: PaperDialogScreen) {
        val exit = original.exitButton
        val back = PaperDialogButton(
            id = exit?.id ?: PaperDialogActionId.of("arc_history_exit"),
            label = exit?.label ?: Component.translatable("gui.back"),
            tooltip = exit?.tooltip ?: Component.empty(),
            width = exit?.width ?: 200,
            onClick = {
                history.current(player.uniqueId)?.onDismiss?.invoke()
                val previous = history.back(player.uniqueId)
                if (previous == null) {
                    player.closeDialog()
                } else {
                    // A return is a replacement, never another forward visit.
                    val actionOwner = dispatching
                    dispatching = null
                    try {
                        if (previous.reopen != null) previous.reopen.invoke()
                        else render(player, previous.screen)
                    } finally { dispatching = actionOwner }
                }
            },
        )
        val screen = original.copy(exitButton = if (exit?.closeDialogBeforeAction == true) exit else back)

        val actions = (screen.buttons + listOfNotNull(screen.exitButton)).associate { button ->
            button.id to {
                if (button.closeDialogBeforeAction) {
                    history.current(player.uniqueId)?.onDismiss?.invoke()
                    history.remove(player.uniqueId)
                    player.closeDialog()
                }
                button.onClick.handle(
                    PaperDialogClickContext(player) { input -> currentResponse.get()?.getText(input.value) },
                )
            }
        }
        val registration = sessions.replace(player.uniqueId, actions)
        val visitId = UUID.randomUUID().toString()
        observations[player.uniqueId] = DialogObservation(visitId, screen)

        try {
            val dialog = createDialog(screen, registration)
            player.showDialog(dialog)
            observe(player, "open", observations.getValue(player.uniqueId), screen)
        } catch (failure: Throwable) {
            sessions.remove(player.uniqueId)
            observations.remove(player.uniqueId)
            history.remove(player.uniqueId)
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
        val observation = observations.remove(player.uniqueId)
        val action = event.identifier.asString().substringAfterLast('/')
        observation?.let { observe(player, "click", it, button = action) }

        val response = event.dialogResponseView
        // The generated handler reads through this event-owned view immediately.
        currentResponse.set(response)
        val previousDispatch = dispatching
        dispatching = player.uniqueId
        try {
            handler()
        } finally {
            dispatching = previousDispatch
            currentResponse.remove()
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        sessions.remove(event.player.uniqueId)
        observations.remove(event.player.uniqueId)
        history.remove(event.player.uniqueId)
    }

    @EventHandler(ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) { beginFlow(event.player) }

    override fun close() {
        requirePrimaryThread()
        if (closed) return
        closed = true
        sessions.clear()
        observations.clear()
        history.clear()
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

    private fun observe(player: Player, phase: String, observation: DialogObservation, screen: PaperDialogScreen = observation.screen, button: String? = null) {
        val actions = (screen.buttons + listOfNotNull(screen.exitButton)).mapIndexed { index, item -> item.id.value to index }.toMap()
        val payload = linkedMapOf<String, Any>(
            "protocol" to 1, "owner" to plugin.name, "surface" to screen.id,
            "revision" to observation.revision, "visitId" to observation.visitId,
            "playerId" to player.uniqueId.toString(), "phase" to phase,
            "buttons" to actions,
        )
        button?.let { payload["button"] = it }
        plugin.server.pluginManager.callEvent(PaperMenuObservationEvent(PaperMenuObservationEvent.Kind.fromPhase(phase), payload))
    }

    private data class DialogObservation(val visitId: String, val screen: PaperDialogScreen) {
        val revision: String = buildString {
            append(screen.id).append('|').append(screen.columns)
            screen.inputs.forEach { append("|i:").append(it.id.value) }
            (screen.buttons + listOfNotNull(screen.exitButton)).forEach { append("|b:").append(it.id.value) }
        }.let { canonical ->
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
                .take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
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
