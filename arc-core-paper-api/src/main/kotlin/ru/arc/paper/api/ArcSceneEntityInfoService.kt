package ru.arc.paper.api

import org.bukkit.plugin.Plugin
import java.util.Collections

/**
 * Explicitly public, detached information about already-observed scene entities.
 *
 * ArcCore hosts one registry with at most eight sources. Registration, lease close and queries
 * require Paper's primary thread. Queries accept at most 64 entity IDs and return at most 64
 * records. Sources must restrict results to requested, currently public live entities inside
 * [ArcSceneBounds], using bounded in-memory state without IO, chunk loading or profile lookups.
 * The registry also rejects unrequested IDs and duplicate results. A source failure or count
 * overrun fails the query. Returned values may be retained and serialized on a worker.
 * Closing a lease, disabling its plugin or stopping the host releases its callback.
 * Private inventory, cargo, ownership, membership and administrative state are not public info.
 */
interface ArcSceneEntityInfoService {
    fun registerSource(plugin: Plugin, key: String, source: ArcSceneEntityInfoSource): AutoCloseable
    fun information(bounds: ArcSceneBounds, entityIds: Set<String>, limit: Int): List<ArcSceneEntityInfo>
}

fun interface ArcSceneEntityInfoSource {
    fun snapshot(bounds: ArcSceneBounds, entityIds: Set<String>, limit: Int): List<ArcSceneEntityInfo>
}

/** Plain public text only; no native handles, rendered components or executable markup. */
class ArcSceneEntityInfo(
    val entityId: String, val kind: String, val title: String, fields: List<ArcSceneInfoField>,
) {
    val fields: List<ArcSceneInfoField>

    init {
        require(entityId.length in 1..128 && kind.length in 1..64 && title.length <= 128) { "Scene entity info text exceeds its bounds" }
        require(fields.size <= 16 && fields.map { it.key }.distinct().size == fields.size) { "Scene entity info must use at most sixteen distinct fields" }
        this.fields = Collections.unmodifiableList(ArrayList(fields))
    }
}

/** Stable field key and literal human-readable label/value; interpretation belongs to the source. */
class ArcSceneInfoField(val key: String, val label: String, val value: String) {
    init {
        require(key.length in 1..48 && label.length in 1..64 && value.length <= 256) { "Scene info field text exceeds its bounds" }
    }
}
