package ru.arc.paper.sidebar

import io.papermc.paper.scoreboard.numbers.NumberFormat
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.ScoreboardManager
import org.bukkit.scoreboard.Team
import ru.arc.paper.api.ArcSidebarFrame
import ru.arc.paper.api.ArcSidebarHandle
import ru.arc.paper.api.ArcSidebarRegistrationSnapshot
import ru.arc.paper.api.ArcSidebarSelectionSnapshot
import ru.arc.paper.api.ArcSidebarService

/** Native implementation registered by the ARC host through Bukkit ServicesManager. */
class PaperArcSidebarService(
    private val host: Plugin,
    scoreboardManager: ScoreboardManager = requireNotNull(Bukkit.getScoreboardManager()) {
        "Paper scoreboard manager is unavailable"
    },
) : ArcSidebarService, AutoCloseable, Listener {
    private val renderer = NativeSidebarRenderer(scoreboardManager)
    private val registrations = linkedMapOf<SourceKey, Source>()
    private var nextOrder = 0L
    private var closed = false

    init {
        require(Bukkit.isPrimaryThread()) { "Sidebar service must be created on the Paper primary thread" }
        Bukkit.getPluginManager().registerEvents(this, host)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        registrations.values.forEach { it.frames.remove(event.player.uniqueId) }
        renderer.forget(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginDisable(event: PluginDisableEvent) {
        if (closed) return
        registrations.values.filter { it.owner === event.plugin }.toList().forEach(::unregister)
    }

    override fun register(owner: Plugin, id: String, priority: Int): ArcSidebarHandle {
        requireOpen()
        require(Bukkit.isPrimaryThread()) { "Sidebar sources must be registered on the Paper primary thread" }
        require(isValidId(id)) { "Sidebar source id is invalid" }

        val key = SourceKey(owner.name, id)
        require(key !in registrations) { "Sidebar source ${key.owner}:${key.id} is already registered" }

        val source = Source(key, owner, priority, nextOrder++)
        registrations[key] = source
        return Handle(source)
    }

    override fun registrations(): List<ArcSidebarRegistrationSnapshot> =
        registrations.values
            .sortedWith(SOURCE_ORDER)
            .map { source ->
                ArcSidebarRegistrationSnapshot(
                    owner = source.key.owner,
                    id = source.key.id,
                    priority = source.priority,
                    activePlayers = source.frames.size,
                )
            }

    override fun active(playerId: UUID): ArcSidebarSelectionSnapshot? =
        winner(playerId)?.let { source ->
            ArcSidebarSelectionSnapshot(source.key.owner, source.key.id, source.priority)
        }

    override fun close() {
        if (closed) return
        require(Bukkit.isPrimaryThread()) { "Sidebar service must close on the Paper primary thread" }
        closed = true
        HandlerList.unregisterAll(this)
        registrations.clear()
        renderer.close()
    }

    private fun show(source: Source, player: Player, frame: ArcSidebarFrame) {
        requireOpen()
        require(Bukkit.isPrimaryThread()) { "Sidebar frames must be changed on the Paper primary thread" }
        require(registrations[source.key] === source) { "Sidebar source is closed" }

        source.frames[player.uniqueId] = frame
        renderWinner(player)
    }

    private fun hide(source: Source, playerId: UUID) {
        requireOpen()
        require(Bukkit.isPrimaryThread()) { "Sidebar frames must be changed on the Paper primary thread" }
        require(registrations[source.key] === source) { "Sidebar source is closed" }
        if (source.frames.remove(playerId) == null) return
        Bukkit.getPlayer(playerId)?.let(::renderWinner) ?: renderer.forget(playerId)
    }

    private fun unregister(source: Source) {
        if (closed || registrations[source.key] !== source) return
        require(Bukkit.isPrimaryThread()) { "Sidebar source must close on the Paper primary thread" }

        registrations.remove(source.key)
        val affected = source.frames.keys.toList()
        source.frames.clear()
        affected.forEach { playerId ->
            Bukkit.getPlayer(playerId)?.let(::renderWinner) ?: renderer.forget(playerId)
        }
    }

    private fun renderWinner(player: Player) {
        val winner = winner(player.uniqueId)
        if (winner == null) {
            renderer.clear(player)
            return
        }
        renderer.render(player, requireNotNull(winner.frames[player.uniqueId]))
    }

    private fun winner(playerId: UUID): Source? =
        registrations.values
            .asSequence()
            .filter { playerId in it.frames }
            .minWithOrNull(SOURCE_ORDER)

    private fun requireOpen() = check(!closed) { "Sidebar service is closed" }

    private inner class Handle(private val source: Source) : ArcSidebarHandle {
        private var handleClosed = false

        override fun show(player: Player, frame: ArcSidebarFrame) {
            check(!handleClosed) { "Sidebar source is closed" }
            show(source, player, frame)
        }

        override fun hide(playerId: UUID) {
            if (handleClosed) return
            hide(source, playerId)
        }

        override fun close() {
            if (handleClosed) return
            handleClosed = true
            unregister(source)
        }
    }

    private data class SourceKey(val owner: String, val id: String)

    private class Source(
        val key: SourceKey,
        val owner: Plugin,
        val priority: Int,
        val order: Long,
    ) {
        val frames = hashMapOf<UUID, ArcSidebarFrame>()
    }

    private class NativeSidebarRenderer(private val manager: ScoreboardManager) : AutoCloseable {
        private val sessions = hashMapOf<UUID, Session>()

        fun render(player: Player, frame: ArcSidebarFrame) {
            val session = sessions.getOrPut(player.uniqueId) {
                val board = manager.newScoreboard
                val hiddenNames = board.registerNewTeam(HIDDEN_NAMES_TEAM)
                hiddenNames.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER)
                Session(player.scoreboard, board)
            }

            val existing = session.board.getObjective(OBJECTIVE_NAME)
            if (frame.rows.isEmpty()) {
                existing?.unregister()
            } else {
                val objective = existing ?: session.board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, frame.title).also {
                    it.displaySlot = DisplaySlot.SIDEBAR
                }
                objective.displayName(frame.title)
                frame.rows.forEachIndexed { index, row ->
                    val entry = ROW_ENTRIES[index]
                    objective.getScore(entry).apply {
                        score = frame.rows.size - index
                        customName(row)
                        numberFormat(NumberFormat.blank())
                    }
                }
                for (index in frame.rows.size until ArcSidebarFrame.MAX_ROWS) {
                    session.board.resetScores(ROW_ENTRIES[index])
                }
            }

            syncHiddenNames(requireNotNull(session.board.getTeam(HIDDEN_NAMES_TEAM)), frame.hiddenNameEntries)
            if (player.scoreboard !== session.board) player.scoreboard = session.board
        }

        fun clear(player: Player) {
            val session = sessions.remove(player.uniqueId) ?: return
            if (player.scoreboard === session.board) player.scoreboard = session.previous
        }

        fun forget(playerId: UUID) {
            sessions.remove(playerId)
        }

        override fun close() {
            sessions.toMap().forEach { (playerId, session) ->
                Bukkit.getPlayer(playerId)?.takeIf { it.scoreboard === session.board }?.scoreboard = session.previous
            }
            sessions.clear()
        }

        private fun syncHiddenNames(team: Team, target: Set<String>) {
            team.entries.filterNot(target::contains).forEach(team::removeEntry)
            target.filterNot(team::hasEntry).forEach(team::addEntry)
        }

        private data class Session(val previous: Scoreboard, val board: Scoreboard)

        companion object {
            private const val OBJECTIVE_NAME = "arc_sidebar"
            private const val HIDDEN_NAMES_TEAM = "arc_hidden_names"
            private val ROW_ENTRIES = (0 until ArcSidebarFrame.MAX_ROWS).map { index -> "§${index.toString(16)}" }
        }
    }

    companion object {
        private const val MAX_ID_LENGTH = 64

        private val SOURCE_ORDER = compareByDescending<Source> { it.priority }.thenBy { it.order }

        private fun isValidId(value: String): Boolean =
            value.isNotBlank() &&
                value.length <= MAX_ID_LENGTH &&
                value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }
}
