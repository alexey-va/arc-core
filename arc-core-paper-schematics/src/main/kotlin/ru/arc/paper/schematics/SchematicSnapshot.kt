package ru.arc.paper.schematics

import com.sk89q.worldedit.world.block.BlockState
import org.enginehub.linbus.tree.LinCompoundTag
import org.enginehub.linbus.tree.LinRootEntry
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.api.ArcSchematicTransform
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.AbstractList
import java.util.Collections
import java.util.HexFormat
import java.util.function.BiConsumer

internal data class SchematicPaletteEntry(val text: String, val air: Boolean, val barrier: Boolean, val native: BlockState)

/** Arrays and NBT are exclusively owned by this instance and never exposed to consumers. */
internal class SchematicSnapshot(
    val producer: Any,
    override val minimum: ArcSchematicPoint,
    override val maximum: ArcSchematicPoint,
    override val origin: ArcSchematicPoint,
    override val fileSha256: String,
    override val hasBiomes: Boolean,
    override val entityCount: Int,
    val palette: List<SchematicPaletteEntry>,
    private val indices: IntArray,
    private val blockEntities: Map<Int, LinCompoundTag>,
) : ArcSchematicSnapshot {
    override val sizeX = Math.addExact(Math.subtractExact(maximum.x, minimum.x), 1)
    override val sizeY = Math.addExact(Math.subtractExact(maximum.y, minimum.y), 1)
    override val sizeZ = Math.addExact(Math.subtractExact(maximum.z, minimum.z), 1)
    override val volume = sizeX.toLong() * sizeY * sizeZ
    override val nonBarrierCount = indices.count { !palette[it].barrier }
    override val contentSha256: String
    val retainedBytes: Long

    init {
        require(volume == indices.size.toLong())
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$sizeX:$sizeY:$sizeZ\n".toByteArray(UTF_8))
        for (index in indices.indices) {
            val entry = palette[indices[index]]
            if (entry.air) continue
            val z = index % sizeZ
            val x = (index / sizeZ) % sizeX
            val y = index / (sizeX * sizeZ)
            digest.update("$x:$y:$z:${entry.text}\n".toByteArray(UTF_8))
        }
        contentSha256 = HexFormat.of().formatHex(digest.digest())
        // Conservative retained-data weight, not a claim of exact JVM heap usage.
        retainedBytes = 256L + indices.size * 4L + palette.sumOf { 128L + it.text.length * 2L } +
            blockEntities.values.sumOf { 256L + LinRootEntry("", it).writeToArray().size * 8L }
    }

    private val view: List<ArcSchematicBlock> = Collections.unmodifiableList(object : AbstractList<ArcSchematicBlock>() {
        override val size: Int get() = this@SchematicSnapshot.indices.size
        override fun get(index: Int): ArcSchematicBlock {
            java.util.Objects.checkIndex(index, this@SchematicSnapshot.indices.size)
            return cell(index)
        }
    })

    override fun blocks(): List<ArcSchematicBlock> = view

    override fun blockAt(x: Int, y: Int, z: Int): ArcSchematicBlock {
        require(x in minimum.x..maximum.x && y in minimum.y..maximum.y && z in minimum.z..maximum.z) { "Schematic position is outside the snapshot" }
        return cell(index(x, y, z))
    }

    fun index(x: Int, y: Int, z: Int): Int = ((y - minimum.y) * sizeX + x - minimum.x) * sizeZ + z - minimum.z
    fun paletteIndex(index: Int): Int = indices[index]
    fun nbt(index: Int): LinCompoundTag? = blockEntities[index]
    fun entry(index: Int): SchematicPaletteEntry = palette[indices[index]]

    fun position(index: Int): ArcSchematicPoint = ArcSchematicPoint(
        minimum.x + (index / sizeZ) % sizeX,
        minimum.y + index / (sizeX * sizeZ),
        minimum.z + index % sizeZ,
    )

    private fun cell(index: Int): ArcSchematicBlock {
        val entry = entry(index)
        return ArcSchematicBlock(position(index), entry.text, entry.air, entry.barrier)
    }

    fun cropped(minimum: ArcSchematicPoint, maximum: ArcSchematicPoint, checkCurrent: () -> Unit): SchematicSnapshot {
        if (minimum.x > maximum.x || minimum.y > maximum.y || minimum.z > maximum.z ||
            minimum.x < this.minimum.x || minimum.y < this.minimum.y || minimum.z < this.minimum.z ||
            maximum.x > this.maximum.x || maximum.y > this.maximum.y || maximum.z > this.maximum.z) {
            failure(ru.arc.paper.api.ArcSchematicFailure.INVALID_SNAPSHOT, "Crop bounds must be nonempty and inside the source snapshot")
        }
        val width = maximum.x - minimum.x + 1
        val height = maximum.y - minimum.y + 1
        val length = maximum.z - minimum.z + 1
        val cells = IntArray(Math.multiplyExact(Math.multiplyExact(width, height), length))
        val nbt = HashMap<Int, LinCompoundTag>()
        var target = 0
        for (y in minimum.y..maximum.y) for (x in minimum.x..maximum.x) for (z in minimum.z..maximum.z) {
            if (target % 16_384 == 0) checkCurrent()
            val source = index(x, y, z)
            cells[target] = indices[source]
            blockEntities[source]?.let { nbt[target] = it }
            target++
        }
        checkCurrent()
        return SchematicSnapshot(producer, minimum, maximum, origin, fileSha256, hasBiomes, entityCount, palette, cells, nbt)
    }

    fun inspectBlockEntityNbt(visitor: BiConsumer<ArcSchematicPoint, String>, checkCurrent: () -> Unit) {
        blockEntities.forEach { (index, tag) ->
            checkCurrent()
            visitor.accept(position(index), tag.toString())
        }
        checkCurrent()
    }

    fun transformed(transform: ArcSchematicTransform, transformedPalette: List<SchematicPaletteEntry>): SchematicSnapshot {
        val a = transformPoint(subtract(minimum, origin), transform)
        val b = transformPoint(subtract(maximum, origin), transform)
        val min = ArcSchematicPoint(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z))
        val max = ArcSchematicPoint(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
        val width = max.x - min.x + 1
        val length = max.z - min.z + 1
        val cells = IntArray(indices.size)
        val nbt = HashMap<Int, LinCompoundTag>(blockEntities.size)
        for (index in indices.indices) {
            if (index % 16_384 == 0 && Thread.currentThread().isInterrupted) failure(ru.arc.paper.api.ArcSchematicFailure.CLOSED, "Schematic transform interrupted")
            val position = transformPoint(subtract(position(index), origin), transform)
            val target = ((position.y - min.y) * width + position.x - min.x) * length + position.z - min.z
            cells[target] = indices[index]
            blockEntities[index]?.let { nbt[target] = it }
        }
        return SchematicSnapshot(producer, min, max, ArcSchematicPoint(0, 0, 0), fileSha256, hasBiomes, entityCount, transformedPalette, cells, nbt)
    }
}

internal fun subtract(a: ArcSchematicPoint, b: ArcSchematicPoint): ArcSchematicPoint = ArcSchematicPoint(
    Math.subtractExact(a.x, b.x), Math.subtractExact(a.y, b.y), Math.subtractExact(a.z, b.z),
)

internal fun transformPoint(point: ArcSchematicPoint, transform: ArcSchematicTransform): ArcSchematicPoint {
    val x = if (transform.mirrorX) Math.negateExact(point.x) else point.x
    return when (Math.floorMod(transform.quarterTurns, 4)) {
        1 -> ArcSchematicPoint(Math.negateExact(point.z), point.y, x)
        2 -> ArcSchematicPoint(Math.negateExact(x), point.y, Math.negateExact(point.z))
        3 -> ArcSchematicPoint(point.z, point.y, Math.negateExact(x))
        else -> ArcSchematicPoint(x, point.y, point.z)
    }
}
