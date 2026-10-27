package ru.arc.paper.api

import org.bukkit.plugin.Plugin
import java.util.Collections
import java.util.UUID

/**
 * Passive, explicitly public scene observations hosted once by ArcCore.
 *
 * Registration, lease close and queries run on Paper's primary thread. Sources must perform
 * bounded in-memory captures there, without IO, chunk loading or network profile resolution.
 * Returned DTOs contain no native handles and may be retained and serialized on a worker.
 * Each query returns at most 512 records; the host accepts 32 entity/8 location sources and
 * 8 chunk listeners. Chunk hints are passive; a failed listener is removed and logged once.
 * Closing a lease, disabling its plugin or stopping the host removes the source callback.
 * Private or per-viewer actors must never be registered as public observations.
 * A source exception or record-count overrun fails that query without caching a partial result.
 */
interface ArcSceneObservationService {
    fun registerEntitySource(plugin: Plugin, key: String, source: ArcSceneEntitySource): AutoCloseable
    fun registerLocationSource(plugin: Plugin, key: String, source: ArcSceneLocationSource): AutoCloseable
    fun registerChunkListener(plugin: Plugin, key: String, listener: ArcSceneChunkListener): AutoCloseable
    /** Passive accepted-write hint; Main-only, at most eight listeners, no world lookup or capture. */
    fun chunkChanged(worldId: UUID, chunkX: Int, chunkZ: Int)
    fun entities(bounds: ArcSceneBounds, limit: Int): List<ArcSceneEntity>
    fun locations(limit: Int): List<ArcSceneLocation>
}

fun interface ArcSceneEntitySource {
    fun snapshot(bounds: ArcSceneBounds, limit: Int): List<ArcSceneEntity>
}

fun interface ArcSceneLocationSource {
    fun locations(limit: Int): List<ArcSceneLocation>
}

fun interface ArcSceneChunkListener {
    fun changed(worldId: UUID, chunkX: Int, chunkZ: Int)
}

/** World-space half-open bounds: each minimum is included and each maximum is excluded. */
class ArcSceneBounds(
    val worldId: UUID,
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double,
) {
    init {
        require(listOf(minX, minY, minZ, maxX, maxY, maxZ).all(Double::isFinite)) { "Scene bounds must be finite" }
        require(minX < maxX && minY < maxY && minZ < maxZ) { "Scene bounds must be nonempty" }
    }

    fun contains(worldId: UUID, x: Double, y: Double, z: Double): Boolean =
        this.worldId == worldId && x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ
}

/** Only the six visible player-equipment slots; no inventory, item NBT, lore or owner data. */
class ArcSceneEquipment(
    val slot: String, val material: String, val amount: Int,
    val customModelData: Int?, val tint: Int?,
) {
    init {
        require(slot in setOf("HAND", "OFF_HAND", "FEET", "LEGS", "CHEST", "HEAD")) { "Unsupported scene equipment slot" }
        require(material.length in 1..128 && material.all { it.isLetterOrDigit() || it in "_:./-" }) { "Invalid scene material" }
        require(amount in 1..99) { "Scene item amount must be 1..99" }
        require(tint == null || tint in 0..0xFFFFFF) { "Scene tint must be RGB" }
    }
}

/** Detached current appearance. [skinTexture] is an already-resolved base64 texture property. */
class ArcSceneEntity(
    val worldId: UUID, val id: String, val type: String, val name: String,
    val x: Double, val y: Double, val z: Double,
    val bodyYaw: Float, val headYaw: Float, val pitch: Float,
    val pose: String, val scale: Double, val seated: Boolean,
    val skinTexture: String?, equipment: List<ArcSceneEquipment>,
) {
    val equipment: List<ArcSceneEquipment>
    init {
        require(id.length in 1..128 && type.length in 1..64 && name.length <= 128 && pose.length in 1..32) { "Scene entity text exceeds its bounds" }
        require(listOf(x, y, z, scale).all(Double::isFinite) && scale in 0.01..16.0) { "Invalid scene position or scale" }
        require(bodyYaw.isFinite() && headYaw.isFinite() && pitch.isFinite()) { "Scene angles must be finite" }
        require(skinTexture == null || skinTexture.length <= 16_384) { "Scene skin texture is too large" }
        require(equipment.size <= 6 && equipment.map { it.slot }.distinct().size == equipment.size) { "Scene equipment must use at most six distinct slots" }
        this.equipment = Collections.unmodifiableList(ArrayList(equipment))
    }
}

/** An existing public scene anchor; consumers choose their own smaller capture bounds. */
class ArcSceneLocation(
    val id: String, val name: String, val worldId: UUID,
    val x: Double, val y: Double, val z: Double, val radius: Double,
) {
    init {
        require(id.length in 1..128 && name.length in 1..128) { "Scene location text exceeds its bounds" }
        require(listOf(x, y, z, radius).all(Double::isFinite) && radius in 0.0..4096.0) { "Invalid scene location position or radius" }
    }
}
