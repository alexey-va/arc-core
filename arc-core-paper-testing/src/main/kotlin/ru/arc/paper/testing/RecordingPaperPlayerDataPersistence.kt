package ru.arc.paper.testing

import org.bukkit.entity.Player
import ru.arc.paper.playerstate.NativePaperPlayerDataPersistence
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Records native player-data persistence requests for Paper orchestration tests. */
class RecordingPaperPlayerDataPersistence(
    private val delegate: PaperPlayerDataPersistence = NativePaperPlayerDataPersistence,
) : PaperPlayerDataPersistence {
    private val recorded = CopyOnWriteArrayList<UUID>()

    fun playerIds(): List<UUID> = recorded.toList()

    fun count(player: Player): Int = recorded.count { it == player.uniqueId }

    fun clear() = recorded.clear()

    override fun persist(player: Player) {
        recorded += player.uniqueId
        delegate.persist(player)
    }
}
