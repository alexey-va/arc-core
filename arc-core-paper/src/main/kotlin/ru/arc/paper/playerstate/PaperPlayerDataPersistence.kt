package ru.arc.paper.playerstate

import org.bukkit.entity.Player

/**
 * Exact Paper boundary for persisting a player's native data.
 *
 * Callers own the durable gameplay journal and invoke this port only on the
 * owning Paper thread after the in-memory mutation has been verified.
 */
fun interface PaperPlayerDataPersistence {
    fun persist(player: Player)
}

/** Production adapter bound to [Player.saveData]. */
object NativePaperPlayerDataPersistence : PaperPlayerDataPersistence {
    override fun persist(player: Player) = player.saveData()
}
