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
import net.kyori.adventure.text.format.NamedTextColor
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
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import ru.arc.core.BudgetedTaskQueue

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
    private val preparations = mutableMapOf<UUID, CompletableFuture<Void>>()

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
        cancelPreparation(player.uniqueId)
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
     * Prepare an unbounded screen through the caller's shared native tick budget.
     * This is an asynchronous refresh: show a small pending screen inside an action
     * first when a child must enter history. No sessions or history are published
     * until preparation completes. Build the immutable [screen] away from gameplay
     * when its model validation itself is large; never read Player state there.
     *
     * Paper's final Dialog.create conversion/showDialog remains one indivisible
     * native API call. This method bounds our per-body/input/button binding loops,
     * not that platform conversion or the native packet size.
     */
    @JvmOverloads
    fun openAsync(
        player: Player,
        screen: PaperDialogScreen,
        work: BudgetedTaskQueue,
        isCurrent: () -> Boolean = { true },
        reopen: (() -> Unit)? = null,
        onDismiss: () -> Unit = {},
        closeOnEscape: Boolean = false,
    ): CompletableFuture<Void> {
        requirePrimaryThread()
        cancelPreparation(player.uniqueId)
        if (closed || !player.isOnline) return CompletableFuture.failedFuture(CancellationException("Dialog owner is unavailable"))
        val result = CompletableFuture<Void>()
        preparations[player.uniqueId] = result
        val handlers = linkedMapOf<PaperDialogActionId, () -> Unit>()
        val registration = sessions.prepare(Collections.unmodifiableMap(handlers))
        val actions = linkedMapOf<String, Int>()
        val body = mutableListOf<DialogBody>()
        val inputs = mutableListOf<DialogInput>()
        val buttons = mutableListOf<ActionButton>()
        val digest = MessageDigest.getInstance("SHA-256")
        fun revisionPart(value: String) { digest.update(value.toByteArray(StandardCharsets.UTF_8)) }
        val steps = sequence {
            revisionPart("${screen.id}|${screen.columns}")
            for (item in screen.body) {
                if (presenter == null) body += DialogBody.plainMessage(item.text, item.width)
                yield(Unit)
            }
            for (item in screen.inputs) {
                if (presenter == null) inputs += createInput(item)
                revisionPart("|it:${item.id.value}")
                yield(Unit)
            }
            for (item in screen.numberInputs) {
                if (presenter == null) inputs += createInput(item)
                revisionPart("|in:${item.id.value}")
                yield(Unit)
            }
            for (button in screen.buttons) {
                handlers[button.id] = actionHandler(player, button)
                actions[button.id.value] = actions.size
                revisionPart("|b:${button.id.value}")
                if (presenter == null) buttons += createButton(button, registration)
                yield(Unit)
            }
            var footerId = screen.exitButton?.id
            if (footerId == null) {
                var index = 0
                do {
                    footerId = PaperDialogActionId.of("arc_history_exit_${index++}")
                    yield(Unit)
                } while (handlers.containsKey(footerId))
            }
            val footer = historyFooter(player, screen, requireNotNull(footerId), closeOnEscape)
            handlers[footer.id] = actionHandler(player, footer)
            actions[footer.id.value] = actions.size
            revisionPart("|b:${footer.id.value}")
            val nativeFooter = if (presenter == null) createButton(footer, registration) else null
            yield(Unit)
            val revision = digest.digest().take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val observation = DialogObservation(UUID.randomUUID().toString(), screen, revision, Collections.unmodifiableMap(actions))
            val dialog = if (presenter == null) createDialog(screen, body, inputs, buttons, requireNotNull(nativeFooter)) else null
            yield(Unit)
            // No pending continuation may deactivate this just-prepared operation during history publication.
            preparations.remove(player.uniqueId, result)
            val history = history(player)!!
            val visit = Visit(screen, reopen, onDismiss, closeOnEscape)
            val key = if (screen.id == "dialog") screen.title.toString() else screen.id
            if (!history.show(owner, key, Runnable { deactivate(player, visit) }, Runnable { dismiss(player, visit) }, Runnable {
                    if (!closed) {
                        currentVisits[player.uniqueId] = visit
                        if (visit.reopen != null) visit.reopen.invoke()
                        else openAsync(player, visit.screen, work, reopen = null, onDismiss = visit.onDismiss, closeOnEscape = visit.closeOnEscape)
                    }
                })) throw CancellationException("Dialog flow changed before presentation")
            if (closed || !player.isOnline) throw CancellationException("Dialog owner closed during presentation")
            currentVisits[player.uniqueId] = visit
            sessions.publish(player.uniqueId, registration)
            observations[player.uniqueId] = observation
            try {
                if (presenter != null) presenter.invoke(player, screen, registration)
                else player.showDialog(requireNotNull(dialog))
                observe(player, "open", observation)
            } catch (failure: Throwable) {
                sessions.remove(player.uniqueId)
                observations.remove(player.uniqueId)
                history.clear()
                throw failure
            }
        }.iterator()
        work.submit({
            !closed && !result.isDone && player.isOnline && preparations[player.uniqueId] === result && isCurrent()
        }) {
            requirePrimaryThread()
            // hasNext resumes the lazy native stage; never probe the following stage in this tick quantum.
            if (steps.hasNext()) { steps.next(); false } else true
        }.whenComplete { _, failure ->
            preparations.remove(player.uniqueId, result)
            if (failure != null) result.completeExceptionally(failure) else result.complete(null)
        }
        return result
    }

    private fun cancelPreparation(playerId: UUID) {
        preparations.remove(playerId)?.completeExceptionally(CancellationException("Dialog visit changed"))
    }

    /**
     * Start a command/hotkey entry with no invented ancestors. Calls made inside
     * any participating plugin's dialog action or Back restoration keep the flow.
     * Call this before asynchronous root loading; a closed flow rejects late open.
     */
    fun beginFlow(player: Player) {
        requirePrimaryThread()
        cancelPreparation(player.uniqueId)
        if (!closed && player.isOnline) history(player)!!.beginFlow(owner)
    }

    /** Return after a completed child action without adding the parent to history again. */
    fun back(player: Player): Boolean {
        requirePrimaryThread()
        cancelPreparation(player.uniqueId)
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
        cancelPreparation(player.uniqueId)
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
        cancelPreparation(player.uniqueId)
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
        val footerId = original.exitButton?.id ?: (0..original.buttons.size).asSequence()
                .map { PaperDialogActionId.of("arc_history_exit_$it") }
                .first { candidate -> original.buttons.none { it.id == candidate } }
        val screen = original.copy(
            exitButton = historyFooter(player, original, footerId, currentVisits[player.uniqueId]?.closeOnEscape == true),
        )

        val actions = (screen.buttons + listOfNotNull(screen.exitButton)).associate { it.id to actionHandler(player, it) }
        val registration = sessions.replace(player.uniqueId, actions)
        val visitId = UUID.randomUUID().toString()
        observations[player.uniqueId] = DialogObservation(visitId, screen)

        try {
            if (presenter != null) presenter.invoke(player, screen, registration)
            else player.showDialog(createDialog(screen, registration))
            observe(player, "open", observations.getValue(player.uniqueId))
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
        cancelPreparation(player.uniqueId)
        val observation = observations.remove(player.uniqueId)
        val action = event.identifier.asString().substringAfterLast('/')
        observation?.let { observe(player, "click", it, button = action) }

        val response = event.dialogResponseView
        currentVisits[player.uniqueId]?.let { visit ->
            visit.screen = visit.screen.captureInputs(
                readText = { input -> response?.getText(input.value) },
                readNumber = { input -> response?.getFloat(input.value) },
            )
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
        cancelPreparation(event.player.uniqueId)
        history(event.player, create = false)?.clear()
        sessions.remove(event.player.uniqueId)
        observations.remove(event.player.uniqueId)
        currentVisits.remove(event.player.uniqueId)
        players.remove(event.player.uniqueId)
        event.player.removeMetadata(HISTORY_METADATA_KEY, plugin)
    }

    @EventHandler(ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        cancelPreparation(event.player.uniqueId)
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
        preparations.values.forEach { it.completeExceptionally(CancellationException("Dialog runtime closed")) }
        preparations.clear()
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
            .inputs(screen.inputs.map(::createInput) + screen.numberInputs.map(::createInput))
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

    private fun createDialog(
        screen: PaperDialogScreen,
        body: List<DialogBody>,
        inputs: List<DialogInput>,
        buttons: List<ActionButton>,
        footer: ActionButton,
    ): Dialog = Dialog.create { factory ->
        val base = DialogBase.builder(screen.title).externalTitle(screen.externalTitle)
            .canCloseWithEscape(screen.canCloseWithEscape).pause(screen.pause)
            .afterAction(DialogBase.DialogAfterAction.NONE).body(body).inputs(inputs).build()
        val type = if (buttons.isEmpty()) DialogType.notice(footer)
            else DialogType.multiAction(buttons).columns(screen.columns).exitAction(footer).build()
        factory.empty().base(base).type(type)
    }

    private fun actionHandler(player: Player, button: PaperDialogButton): () -> Unit = {
        if (button.closeDialogBeforeAction) close(player)
        button.onClick.handle(PaperDialogClickContext(player,
            { input -> currentResponse.get()?.getText(input.value) },
            { input -> currentResponse.get()?.getFloat(input.value) }))
    }

    private fun historyFooter(player: Player, screen: PaperDialogScreen, id: PaperDialogActionId, closeOnEscape: Boolean): PaperDialogButton {
        val exit = screen.exitButton
        val key = if (screen.id == "dialog") screen.title.toString() else screen.id
        val returns = !closeOnEscape && history(player, create = false)?.hasPreviousAfterShow(owner, key) == true
        val caption = Component.translatable(if (returns) "gui.back" else "mco.selectServer.close", NamedTextColor.WHITE)
        val label = if (returns) Component.text("‹ ", NamedTextColor.WHITE).append(caption) else caption
        return PaperDialogButton(id,
            label = exit?.label ?: label,
            tooltip = exit?.tooltip ?: caption, width = exit?.width ?: 200,
            closeDialogBeforeAction = closeOnEscape,
            onClick = { if (!closeOnEscape) back(player) })
    }

    private fun observe(player: Player, phase: String, observation: DialogObservation, button: String? = null) {
        val payload = linkedMapOf<String, Any>(
            "protocol" to 1, "owner" to plugin.name, "surface" to observation.screen.id,
            "revision" to observation.revision, "visitId" to observation.visitId,
            "playerId" to player.uniqueId.toString(), "phase" to phase,
            "buttons" to observation.actions,
        )
        button?.let { payload["button"] = it }
        PaperMenuObservationPublisher.publish(plugin, payload)
    }

    private data class DialogObservation(
        val visitId: String,
        val screen: PaperDialogScreen,
        val revision: String = buildString {
            append(screen.id).append('|').append(screen.columns)
            screen.inputs.forEach { append("|it:").append(it.id.value) }
            screen.numberInputs.forEach { append("|in:").append(it.id.value) }
            (screen.buttons + listOfNotNull(screen.exitButton)).forEach { append("|b:").append(it.id.value) }
        }.let { canonical ->
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
                .take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        },
        val actions: Map<String, Int> = (screen.buttons + listOfNotNull(screen.exitButton))
            .mapIndexed { index, item -> item.id.value to index }.toMap(),
    )

    private fun createInput(input: PaperDialogTextInput): DialogInput =
        DialogInput.text(input.id.value, input.label)
            .width(input.width)
            .labelVisible(input.labelVisible)
            .initial(input.initial)
            .maxLength(input.maxLength)
            .build()

    private fun createInput(input: PaperDialogNumberRangeInput): DialogInput =
        DialogInput.numberRange(input.id.value, input.label, input.start, input.end)
            .width(input.width)
            .labelFormat(input.labelFormat)
            .apply {
                input.initial?.let(::initial)
                input.step?.let(::step)
            }
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
    captureInputs(readText = read, readNumber = { null })

/** Keep every typed form value when Back restores an immutable screen snapshot. */
internal fun PaperDialogScreen.captureInputs(
    readText: (PaperDialogInputId) -> String?,
    readNumber: (PaperDialogInputId) -> Float?,
): PaperDialogScreen = copy(
    inputs = inputs.map { input ->
        input.copy(initial = readText(input.id)?.take(input.maxLength) ?: input.initial)
    },
    numberInputs = numberInputs.map { input ->
        val value = readNumber(input.id)?.takeIf(Float::isFinite)?.coerceIn(input.start, input.end)
        input.copy(initial = value ?: input.initial)
    },
)
