package ru.arc.paper.testing

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.paper.nameplate.PaperNameplateDisplay
import ru.arc.paper.nameplate.PaperNameplateDisplayFactory
import java.util.UUID

/**
 * Recordable nameplate entity boundary for plugin and arc-core Paper tests.
 *
 * MockBukkit does not need to implement TextDisplay spawning, passenger
 * bookkeeping or per-viewer entity visibility for surrounding lifecycle tests
 * to retain their production semantics.
 */
class RecordingPaperNameplateDisplayFactory : PaperNameplateDisplayFactory {
    data class Record(
        val targetId: UUID,
        val contents: MutableList<Component>,
        val shownTo: MutableSet<UUID> = linkedSetOf(),
        val showCalls: MutableList<UUID> = mutableListOf(),
        val hideCalls: MutableList<UUID> = mutableListOf(),
        var attached: Boolean = true,
        var closed: Boolean = false,
    )

    val records = mutableListOf<Record>()

    override fun create(
        target: Player,
        content: Component,
    ): PaperNameplateDisplay {
        val record = Record(target.uniqueId, mutableListOf(content))
        records += record
        return RecordingPaperNameplateDisplay(record)
    }

    fun latest(targetId: UUID): Record? = records.lastOrNull { it.targetId == targetId }

    private class RecordingPaperNameplateDisplay(
        private val record: Record,
    ) : PaperNameplateDisplay {
        override val targetId: UUID = record.targetId

        override fun isAttachedTo(target: Player): Boolean =
            !record.closed && record.attached && target.uniqueId == targetId

        override fun update(content: Component) {
            check(!record.closed) { "Recording nameplate display is closed" }
            record.contents += content
        }

        override fun show(viewer: Player) {
            check(!record.closed) { "Recording nameplate display is closed" }
            record.showCalls += viewer.uniqueId
            record.shownTo += viewer.uniqueId
        }

        override fun hide(viewer: Player) {
            check(!record.closed) { "Recording nameplate display is closed" }
            record.hideCalls += viewer.uniqueId
            record.shownTo -= viewer.uniqueId
        }

        override fun close() {
            record.closed = true
            record.shownTo.clear()
        }
    }
}
