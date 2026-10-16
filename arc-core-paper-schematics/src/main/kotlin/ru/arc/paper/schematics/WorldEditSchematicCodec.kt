package ru.arc.paper.schematics

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.world.block.BlockState
import org.bukkit.block.structure.Mirror
import org.bukkit.block.structure.StructureRotation
import org.enginehub.linbus.tree.LinCompoundTag
import ru.arc.paper.api.ArcSchematicCacheSettings
import ru.arc.paper.api.ArcSchematicFailure
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicTransform
import java.io.ByteArrayInputStream

internal data class DecodedSchematic(
    val minimum: ArcSchematicPoint,
    val maximum: ArcSchematicPoint,
    val origin: ArcSchematicPoint,
    val palette: List<BlockState>,
    val indices: IntArray,
    val blockEntities: Map<Int, LinCompoundTag>,
    val hasBiomes: Boolean,
    val entityCount: Int,
)

/** Exact native registry seam: decode on a worker, palette calls on the owning Paper thread. */
internal interface SchematicCodec {
    fun decode(bytes: SchematicBytes): DecodedSchematic
    fun canonicalize(state: BlockState): SchematicPaletteEntry
    fun transform(entry: SchematicPaletteEntry, transform: ArcSchematicTransform): SchematicPaletteEntry
}

internal class WorldEditSchematicCodec(private val settings: ArcSchematicCacheSettings) : SchematicCodec {
    override fun decode(bytes: SchematicBytes): DecodedSchematic {
        val format = ClipboardFormats.findByInputStream { ByteArrayInputStream(bytes.bytes) }
            ?: failure(ArcSchematicFailure.UNSUPPORTED_FORMAT, "WorldEdit does not recognize this schematic")
        val clipboard = ByteArrayInputStream(bytes.bytes).use { input -> format.getReader(input).use { it.read() } }
        val min = clipboard.minimumPoint
        val max = clipboard.maximumPoint
        val width = max.x().toLong() - min.x() + 1
        val height = max.y().toLong() - min.y() + 1
        val length = max.z().toLong() - min.z() + 1
        val volume = width * height * length
        if (width <= 0 || height <= 0 || length <= 0 || volume !in 1..settings.maximumVolume.toLong()) {
            failure(ArcSchematicFailure.LIMIT_EXCEEDED, "Decoded schematic dimensions exceed the configured bound")
        }
        val palette = linkedMapOf<BlockState, Int>()
        val indices = IntArray(volume.toInt())
        val entities = HashMap<Int, LinCompoundTag>()
        var index = 0
        // Keep the historical player-book hash order without sorting or retaining per-cell objects.
        for (y in min.y()..max.y()) for (x in min.x()..max.x()) for (z in min.z()..max.z()) {
            if (index % 16_384 == 0 && Thread.currentThread().isInterrupted) failure(ArcSchematicFailure.CLOSED, "Schematic decode interrupted")
            val full = clipboard.getFullBlock(BlockVector3.at(x, y, z))
            indices[index] = palette.getOrPut(full.toImmutableState()) { palette.size }
            full.nbtReference?.value?.let { entities[index] = it }
            index++
        }
        return DecodedSchematic(
            min.point(), max.point(), clipboard.origin.point(), palette.keys.toList(), indices,
            entities, clipboard.hasBiomes(), clipboard.entities.size,
        )
    }

    override fun canonicalize(state: BlockState): SchematicPaletteEntry {
        val data = BukkitAdapter.adapt(state)
        return SchematicPaletteEntry(data.asString, data.material.isAir, data.material.key.toString() == "minecraft:barrier", state)
    }

    override fun transform(entry: SchematicPaletteEntry, transform: ArcSchematicTransform): SchematicPaletteEntry {
        val data = BukkitAdapter.adapt(entry.native)
        // FRONT_BACK reflects the local X axis; rotate +90 maps north to east.
        if (transform.mirrorX) data.mirror(Mirror.FRONT_BACK)
        data.rotate(when (Math.floorMod(transform.quarterTurns, 4)) {
            1 -> StructureRotation.CLOCKWISE_90
            2 -> StructureRotation.CLOCKWISE_180
            3 -> StructureRotation.COUNTERCLOCKWISE_90
            else -> StructureRotation.NONE
        })
        return SchematicPaletteEntry(data.asString, data.material.isAir, entry.barrier, BukkitAdapter.adapt(data))
    }

    private fun BlockVector3.point(): ArcSchematicPoint = ArcSchematicPoint(x(), y(), z())
}
