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
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.metadata.FixedMetadataValue
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
class PaperDialogRuntime internal constructor(
    private val plugin: Plugin,
    private val presenter: ((Player, PaperDialogScreen, PaperDialogSessionRegistration) -> Unit)?,
) : AutoCloseable, Listener {
    constructor(plugin: Plugin) : this(plugin, null)
    private val sessions = PaperDialogSessionStore(plugin.name)
    private val observations = mutableMapOf<UUID, DialogObservation>()
    private class Visit(var screen: PaperDialogScreen, val reopen: (() -> Unit)?, val onDismiss: () -> Unit, val closeOnEscape: Boolean) {
        var dismissed = false
    }
    private val owner = UUID.randomUUID().toString()
    private val currentVisits = mutableMapOf<UUID, Visit>()
    private val players = mutableMapOf<UUID, Player>()
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
     * [onDismiss] must invalidate pending work on Back, Close and root reset.
     * It is not called on forward navigation or same-page refresh. Async opens
     * cannot replace a foreign owner's screen or resurrect a closed flow; the
     * consumer must still guard stale generations within its own domain.
     * Escape uses actual history by default; the explicit closeOnEscape override
     * closes the complete flow. Vanilla Escape itself closes on the client, so
     * unlike ordinary buttons it cannot guarantee mouse-position preservation.
     */
    fun open(player: Player, screen: PaperDialogScreen, reopen: (() -> Unit)?, onDismiss: () -> Unit) {
        open(player, screen, reopen, onDismiss, false)
    }

    /** [closeOnEscape] is an explicit user override, independent of a screen's old footer handler. */
    fun open(player: Player, screen: PaperDialogScreen, reopen: (() -> Unit)?, onDismiss: () -> Unit, closeOnEscape: Boolean) {
        requirePrimaryThread()
        if (closed || !player.isOnline) return // Ignore late completions after shutdown or quit.
        val history = history(player)!!
        val visit = Visit(screen, reopen, onDismiss, closeOnEscape)
        val key = if (screen.id == "dialog") screen.title.toString() else screen.id
        if (!history.show(owner, key,
                Runnable { deactivate(player, visit) },
                Runnable { dismiss(player, visit) },
                Runnable {
                    if (!closed) {
                        currentVisits[player.uniqueId] = visit
                        if (visit.reopen != null) visit.reopen.invoke() else render(player, visit.screen)
                    }
                })) return
        currentVisits[player.uniqueId] = visit
        render(player, screen)
    }

    /**
     * Start a command/hotkey entry with no invented ancestors. Calls made inside
     * any participating plugin's dialog action or Back restoration keep the flow.
     * Call this before asynchronous root loading; a closed flow rejects late open.
     */
    fun beginFlow(player: Player) {
        requirePrimaryThread()
        if (!closed && player.isOnline) history(player)!!.beginFlow(owner)
    }

    /** Return after a completed child action without adding the parent to history again. */
    fun back(player: Player): Boolean {
        requirePrimaryThread()
        if (closed || !player.isOnline) return false
        val history = history(player, create = false) ?: return false
        if (history.owner != owner) return false
        val restored = history.back()
        if (!restored) player.closeDialog()
        return restored
    }

    /**
     * Close the player's complete flow if this runtime owns the visible screen.
     * An inactive owner only discards its own ancestors and cannot close a
     * different plugin's screen. Main thread only; safe to call repeatedly.
     */
    fun close(player: Player) {
        requirePrimaryThread()
        val history = history(player, create = false) ?: return
        if (history.entryOwner == owner) {
            history.clear()
            player.closeDialog()
        } else history.removeOwner(owner)
    }

    @Suppress("UNCHECKED_CAST")
    private fun history(player: Player, create: Boolean = true): PaperDialogHistory? {
        val existing = player.getMetadata(HISTORY_METADATA_KEY).asSequence()
            .mapNotNull { it.value() as? MutableMap<*, *> }
            .firstOrNull { it["schema"] == 1 } as? MutableMap<String, Any>
        val state = existing ?: if (create) java.util.HashMap<String, Any>().apply { put("schema", 1) } else return null
        if (create) {
            // Every participant keeps a metadata handle under its own plugin,
            // so removal of the first owner's value does not lose foreign visits.
            player.setMetadata(HISTORY_METADATA_KEY, FixedMetadataValue(plugin, state))
            players[player.uniqueId] = player
        }
        return PaperDialogHistory(state)
    }

    private fun deactivate(player: Player, visit: Visit) {
        if (currentVisits[player.uniqueId] !== visit) return
        currentVisits.remove(player.uniqueId)
        sessions.remove(player.uniqueId)
        observations.remove(player.uniqueId)
    }

    private fun dismiss(player: Player, visit: Visit) {
        deactivate(player, visit)
        if (visit.dismissed) return
        visit.dismissed = true
        try { visit.onDismiss() }
        catch (failure: Exception) {
            plugin.logger.log(java.util.logging.Level.WARNING, "Dialog dismissal failed for ${visit.screen.id}", failure)
        }
    }

    private fun render(player: Player, original: PaperDialogScreen) {
        val exit = original.exitButton
        val back = PaperDialogButton(
            id = exit?.id ?: (0..original.buttons.size).asSequence()
                .map { PaperDialogActionId.of("arc_history_exit_$it") }
                .first { candidate -> original.buttons.none { it.id == candidate } },
            label = if (exit == null || exit.closeDialogBeforeAction) Component.translatable("gui.back") else exit.label,
            tooltip = exit?.tooltip ?: Component.empty(),
            width = exit?.width ?: 200,
            onClick = {
                back(player)
            },
        )
        val screen = original.copy(exitButton = if (currentVisits[player.uniqueId]?.closeOnEscape == true) {
            back.copy(closeDialogBeforeAction = true, label = exit?.label ?: back.label, onClick = {})
        } else back)

        val actions = (screen.buttons + listOfNotNull(screen.exitButton)).associate { button ->
            button.id to {
                if (button.closeDialogBeforeAction) {
                    close(player)
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
            if (presenter != null) presenter.invoke(player, screen, registration)
            else player.showDialog(createDialog(screen, registration))
            observe(player, "open", observations.getValue(player.uniqueId), screen)
        } catch (failure: Throwable) {
            sessions.remove(player.uniqueId)
            observations.remove(player.uniqueId)
            history(player, create = false)?.clear()
            throw failure
        }
    }

    @EventHandler
    fun onCustomClick(event: PlayerCustomClickEvent) {
        requirePrimaryThread()
        if (closed) return
        val connection = event.commonConnection as? PlayerGameConnection ?: return
        val player = connection.player
        val history = history(player, create = false) ?: return
        if (history.owner != owner) return
        val handler = sessions.consume(player.uniqueId, event.identifier.asString()) ?: return
        val observation = observations.remove(player.uniqueId)
        val action = event.identifier.asString().substringAfterLast('/')
        observation?.let { observe(player, "click", it, button = action) }

        val response = event.dialogResponseView
        currentVisits[player.uniqueId]?.let { visit ->
            visit.screen = visit.screen.captureTextInputs { input -> response?.getText(input.value) }
        }
        // The generated handler reads through this event-owned view immediately.
        val previousResponse = currentResponse.get()
        currentResponse.set(response)
        try { history.dispatch(handler) }
        finally {
            if (previousResponse == null) currentResponse.remove() else currentResponse.set(previousResponse)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        history(event.player, create = false)?.clear()
        sessions.remove(event.player.uniqueId)
        observations.remove(event.player.uniqueId)
        currentVisits.remove(event.player.uniqueId)
        players.remove(event.player.uniqueId)
        event.player.removeMetadata(HISTORY_METADATA_KEY, plugin)
    }

    @EventHandler(ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!closed) history(event.player, create = false)?.beginFlow()
    }

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin === plugin) close()
    }

    override fun close() {
        requirePrimaryThread()
        if (closed) return
        closed = true
        players.values.toList().forEach { player ->
            close(player)
            player.removeMetadata(HISTORY_METADATA_KEY, plugin)
        }
        players.clear()
        currentVisits.clear()
        sessions.clear()
        observations.clear()
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
        // Paper 1.21.11 rejects an empty multiAction grid. Informational and
        // loading screens use a native notice with the same history footer.
        val type = if (buttons.isEmpty()) {
            DialogType.notice(createButton(requireNotNull(screen.exitButton), registration))
        } else DialogType.multiAction(buttons)
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
        PaperMenuObservationPublisher.publish(plugin, payload)
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
        const val HISTORY_METADATA_KEY = "arc:paper_dialog_history:v1"
        val currentResponse = ThreadLocal<DialogResponseView>()
    }
}

/** Keep typed form values when Back restores an immutable screen snapshot. */
internal fun PaperDialogScreen.captureTextInputs(read: (PaperDialogInputId) -> String?): PaperDialogScreen =
    copy(inputs = inputs.map { input ->
        input.copy(initial = read(input.id)?.take(input.maxLength) ?: input.initial)
    })
