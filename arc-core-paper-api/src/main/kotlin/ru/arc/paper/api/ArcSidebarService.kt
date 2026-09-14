package ru.arc.paper.api

import java.util.UUID
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * One native Paper sidebar owner shared by sibling plugins.
 *
 * Sources publish a current frame per player. The active source with the highest
 * priority owns the physical sidebar; hiding it immediately reveals the next
 * source without making each plugin replace and restore player scoreboards.
 * All calls must run on the Paper primary thread.
 */
interface ArcSidebarService {
    fun register(owner: Plugin, id: String, priority: Int): ArcSidebarHandle

    fun registrations(): List<ArcSidebarRegistrationSnapshot>

    fun active(playerId: UUID): ArcSidebarSelectionSnapshot?
}

/** Lifecycle-owned publication handle for one logical sidebar source. */
interface ArcSidebarHandle : AutoCloseable {
    fun show(player: Player, frame: ArcSidebarFrame)

    fun hide(player: Player) = hide(player.uniqueId)

    fun hide(playerId: UUID)

    override fun close()
}

/** Immutable content for one native render. Empty rows keep only the shared team state. */
class ArcSidebarFrame(
    title: Component,
    rows: List<Component>,
    hiddenNameEntries: Set<String> = emptySet(),
) {
    val title: Component = title
    val rows: List<Component> = rows.toList()
    val hiddenNameEntries: Set<String> = hiddenNameEntries.toSet()

    init {
        require(rows.size <= MAX_ROWS) { "Sidebar frame cannot contain more than $MAX_ROWS rows" }
        require(hiddenNameEntries.all(::isValidEntry)) { "Sidebar hidden-name entry is invalid" }
    }

    companion object {
        const val MAX_ROWS: Int = 15
        private const val MAX_ENTRY_LENGTH = 64

        private fun isValidEntry(value: String): Boolean =
            value.isNotBlank() &&
                value.length <= MAX_ENTRY_LENGTH &&
                value.none { it.code <= 0x1f || it.code in 0x7f..0x9f }
    }
}

/** Stable shared priority bands. Consumers may use values between them when needed. */
object ArcSidebarPriorities {
    const val BASE: Int = 0
    const val ACTIVITY: Int = 100
    const val DUNGEON: Int = 200
    const val EVENT: Int = 300
}

data class ArcSidebarRegistrationSnapshot(
    val owner: String,
    val id: String,
    val priority: Int,
    val activePlayers: Int,
)

data class ArcSidebarSelectionSnapshot(
    val owner: String,
    val id: String,
    val priority: Int,
)
