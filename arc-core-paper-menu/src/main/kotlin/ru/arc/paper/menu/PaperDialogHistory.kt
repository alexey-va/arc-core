package ru.arc.paper.menu

import java.util.UUID

/** Actual visits, not a consumer's hard-coded hierarchy. Main-thread confined. */
internal class PaperDialogHistory<T>(private val limit: Int = 64) {
    private data class Visit<T>(val key: Any, val value: T)
    private val visits = mutableMapOf<UUID, MutableList<Visit<T>>>()

    fun show(player: UUID, key: Any, value: T, navigate: Boolean) {
        val flow = visits.getOrPut(player) { mutableListOf() }
        if (flow.isNotEmpty() && (!navigate || flow.last().key == key)) flow.removeLast()
        flow += Visit(key, value)
        if (flow.size > limit) flow.removeAt(0)
    }

    fun current(player: UUID): T? = visits[player]?.lastOrNull()?.value

    fun updateCurrent(player: UUID, update: (T) -> T) {
        val flow = visits[player] ?: return
        val current = flow.lastOrNull() ?: return
        flow[flow.lastIndex] = current.copy(value = update(current.value))
    }

    fun back(player: UUID): T? {
        val flow = visits[player] ?: return null
        flow.removeLastOrNull()
        if (flow.isEmpty()) visits.remove(player)
        return flow.lastOrNull()?.value
    }

    fun remove(player: UUID) { visits.remove(player) }
    fun clear() { visits.clear() }
}
