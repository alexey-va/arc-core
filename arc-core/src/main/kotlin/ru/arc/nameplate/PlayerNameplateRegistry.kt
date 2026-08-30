package ru.arc.nameplate

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.UUID

private val NAMEPLATE_TOKEN = Regex("[a-z][a-z0-9_.-]{0,63}")

/** Stable ownership key for one independently replaceable nameplate row. */
data class NameplateLayerKey(
    val owner: String,
    val value: String,
) {
    init {
        require(owner.matches(NAMEPLATE_TOKEN)) {
            "Nameplate layer owner must match ${NAMEPLATE_TOKEN.pattern}"
        }
        require(value.matches(NAMEPLATE_TOKEN)) {
            "Nameplate layer value must match ${NAMEPLATE_TOKEN.pattern}"
        }
    }
}

/**
 * One caller-owned row in a player's composed nameplate.
 *
 * Higher [priority] rows render first (at the top). Equal priorities are
 * ordered by [key], so composition remains deterministic across reloads.
 */
data class NameplateLayer(
    val key: NameplateLayerKey,
    val priority: Int,
    val content: Component,
)

/** Immutable, already-composed view consumed by a platform renderer. */
data class PlayerNameplateSnapshot(
    val playerId: UUID,
    val revision: Long,
    val layers: List<NameplateLayer>,
    val content: Component,
)

enum class NameplateRejectionReason {
    TARGET_CAPACITY,
    LAYER_CAPACITY,
    EMPTY_CONTENT,
    MULTILINE_CONTENT,
    CONTENT_TOO_LONG,
}

/** Typed result of adding or replacing one row. */
sealed interface NameplateUpsertResult {
    val revision: Long?

    data class Added(override val revision: Long) : NameplateUpsertResult

    data class Replaced(override val revision: Long) : NameplateUpsertResult

    data class Unchanged(override val revision: Long) : NameplateUpsertResult

    data class Rejected(
        val reason: NameplateRejectionReason,
    ) : NameplateUpsertResult {
        override val revision: Long? = null
    }
}

/** Typed result of removing one caller-owned row. */
sealed interface NameplateRemoveResult {
    data class Removed(val revision: Long) : NameplateRemoveResult

    data object Absent : NameplateRemoveResult
}

/**
 * Bounded, thread-safe composer for player nameplate rows.
 *
 * Callers own wording, styling and update timing. The registry owns stable row
 * identity, deterministic ordering, capacity bounds, replacement and owner
 * cleanup. One [NameplateLayer] is one visual row; explicit plain-text
 * newlines are rejected so a caller cannot bypass [maxLayersPerTarget].
 * Snapshots are immutable and safe to hand to a platform renderer on its
 * owning thread.
 */
class PlayerNameplateRegistry(
    private val maxTargets: Int = 512,
    private val maxLayersPerTarget: Int = 6,
    private val maxPlainCharactersPerLayer: Int = 160,
) {
    private data class TargetState(
        val layers: LinkedHashMap<NameplateLayerKey, NameplateLayer> = linkedMapOf(),
        var revision: Long = 0L,
    )

    private val monitor = Any()
    private val targets = linkedMapOf<UUID, TargetState>()
    private val plainText = PlainTextComponentSerializer.plainText()
    private var nextRevision = 1L

    init {
        require(maxTargets in 1..100_000) { "Nameplate target capacity must be between 1 and 100000" }
        require(maxLayersPerTarget in 1..32) { "Nameplate layer capacity must be between 1 and 32" }
        require(maxPlainCharactersPerLayer in 1..1_024) {
            "Nameplate plain-text limit must be between 1 and 1024"
        }
    }

    fun upsert(
        playerId: UUID,
        layer: NameplateLayer,
    ): NameplateUpsertResult = synchronized(monitor) {
        validate(layer)?.let { return@synchronized NameplateUpsertResult.Rejected(it) }
        val current = targets[playerId]
        if (current == null && targets.size >= maxTargets) {
            return@synchronized NameplateUpsertResult.Rejected(NameplateRejectionReason.TARGET_CAPACITY)
        }
        if (current != null && layer.key !in current.layers && current.layers.size >= maxLayersPerTarget) {
            return@synchronized NameplateUpsertResult.Rejected(NameplateRejectionReason.LAYER_CAPACITY)
        }

        val state = current ?: TargetState().also { targets[playerId] = it }
        val previous = state.layers[layer.key]
        if (previous == layer) return@synchronized NameplateUpsertResult.Unchanged(state.revision)

        state.layers[layer.key] = layer
        state.revision = takeRevisionLocked()
        if (previous == null) {
            NameplateUpsertResult.Added(state.revision)
        } else {
            NameplateUpsertResult.Replaced(state.revision)
        }
    }

    fun remove(
        playerId: UUID,
        key: NameplateLayerKey,
    ): NameplateRemoveResult = synchronized(monitor) {
        val state = targets[playerId] ?: return@synchronized NameplateRemoveResult.Absent
        if (state.layers.remove(key) == null) return@synchronized NameplateRemoveResult.Absent
        val revision = takeRevisionLocked()
        if (state.layers.isEmpty()) targets.remove(playerId) else state.revision = revision
        NameplateRemoveResult.Removed(revision)
    }

    /** Removes every row owned by [owner] and returns the number of affected rows. */
    fun clearOwner(owner: String): Int = synchronized(monitor) {
        require(owner.matches(NAMEPLATE_TOKEN)) { "Nameplate layer owner must match ${NAMEPLATE_TOKEN.pattern}" }
        var removed = 0
        val targetsIterator = targets.iterator()
        while (targetsIterator.hasNext()) {
            val (_, state) = targetsIterator.next()
            val layerIterator = state.layers.iterator()
            var targetChanged = false
            while (layerIterator.hasNext()) {
                if (layerIterator.next().key.owner == owner) {
                    layerIterator.remove()
                    removed++
                    targetChanged = true
                }
            }
            if (!targetChanged) continue
            if (state.layers.isEmpty()) targetsIterator.remove() else state.revision = takeRevisionLocked()
        }
        removed
    }

    /** Removes every row for [playerId] and returns how many were present. */
    fun clearTarget(playerId: UUID): Int = synchronized(monitor) {
        targets.remove(playerId)?.layers?.size ?: 0
    }

    fun clearAll(): Int = synchronized(monitor) {
        val removed = targets.values.sumOf { it.layers.size }
        targets.clear()
        removed
    }

    fun snapshot(playerId: UUID): PlayerNameplateSnapshot? = synchronized(monitor) {
        val state = targets[playerId] ?: return@synchronized null
        snapshotLocked(playerId, state)
    }

    /** Returns target ids in stable insertion order. */
    fun targetIds(): Set<UUID> = synchronized(monitor) { LinkedHashSet(targets.keys) }

    fun targetCount(): Int = synchronized(monitor) { targets.size }

    fun layerCount(): Int = synchronized(monitor) { targets.values.sumOf { it.layers.size } }

    private fun snapshotLocked(
        playerId: UUID,
        state: TargetState,
    ): PlayerNameplateSnapshot {
        val ordered = state.layers.values.sortedWith(
            compareByDescending<NameplateLayer> { it.priority }
                .thenBy { it.key.owner }
                .thenBy { it.key.value },
        )
        val composedChildren = buildList {
            ordered.forEachIndexed { index, layer ->
                if (index > 0) add(Component.newline())
                add(layer.content)
            }
        }
        val composed = Component.empty().children(composedChildren)
        return PlayerNameplateSnapshot(playerId, state.revision, ordered, composed)
    }

    private fun validate(layer: NameplateLayer): NameplateRejectionReason? {
        val plain = plainText.serialize(layer.content)
        return when {
            plain.isEmpty() -> NameplateRejectionReason.EMPTY_CONTENT
            '\n' in plain || '\r' in plain -> NameplateRejectionReason.MULTILINE_CONTENT
            plain.length > maxPlainCharactersPerLayer -> NameplateRejectionReason.CONTENT_TOO_LONG
            else -> null
        }
    }

    private fun takeRevisionLocked(): Long {
        val revision = nextRevision
        nextRevision = if (nextRevision == Long.MAX_VALUE) 1L else nextRevision + 1L
        return revision
    }
}
