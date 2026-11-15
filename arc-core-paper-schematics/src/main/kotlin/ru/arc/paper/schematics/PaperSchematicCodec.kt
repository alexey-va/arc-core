package ru.arc.paper.schematics

import net.kyori.adventure.nbt.*
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Chest
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.structure.Mirror
import org.bukkit.block.structure.StructureRotation
import ru.arc.paper.api.ArcSchematicCacheSettings
import ru.arc.paper.api.ArcSchematicFailure
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicTransform
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Detached source state. Native bindings are created and discarded on Paper, never retained here. */
internal data class SchematicState(
    val text: String,
    val dataVersion: Int = -1,
    val template: ByteArray? = null,
    val legacyId: Int = -1,
    val legacyData: Int = 0,
    val companionData: Int = -1,
    val legacyNbt: CompoundBinaryTag? = null,
)

internal data class DecodedSchematic(
    val minimum: ArcSchematicPoint,
    val maximum: ArcSchematicPoint,
    val origin: ArcSchematicPoint,
    val palette: List<SchematicState>,
    val indices: IntArray,
    val blockEntities: Map<Int, CompoundBinaryTag>,
    val hasBiomes: Boolean,
    val entityCount: Int,
    val dataVersion: Int = -1,
)

/** Decode on a worker; canonicalize/transform only on the owning Paper thread. */
internal interface SchematicCodec {
    fun decode(bytes: SchematicBytes): DecodedSchematic
    fun canonicalize(state: SchematicState): SchematicPaletteEntry
    fun transform(entry: SchematicPaletteEntry, transform: ArcSchematicTransform): SchematicPaletteEntry
}

/** Sponge v1/v2/v3 and MCEdit Alpha, without a WorldEdit registry or plugin. */
internal class PaperSchematicCodec(private val settings: ArcSchematicCacheSettings) : SchematicCodec {
    override fun decode(bytes: SchematicBytes): DecodedSchematic {
        // Keep this guard at the codec boundary too: tests and future internal callers cannot bypass it.
        NbtPreflight(settings.maximumInflatedBytes, settings.maximumVolume).check(bytes.bytes)
        val root = ByteArrayInputStream(bytes.bytes).use {
            BinaryTagIO.reader(settings.maximumInflatedBytes.toLong()).readNamed(it, BinaryTagIO.Compression.GZIP)
        }
        val nested = root.value["Schematic"] as? CompoundBinaryTag
        val tag = nested ?: root.value
        return when {
            nested != null && tag.getInt("Version") == 3 -> sponge(tag, 3)
            nested == null && tag.getInt("Version") in 1..2 -> sponge(tag, tag.getInt("Version"))
            nested == null && root.key == "Schematic" && tag.getString("Materials") == "Alpha" -> legacy(tag)
            else -> malformed("Unsupported schematic format")
        }
    }

    private fun sponge(tag: CompoundBinaryTag, version: Int): DecodedSchematic {
        val (width, height, length) = dimensions(tag)
        val offset = vector(tag, "Offset")
        val metadata = tag.getCompound("Metadata")
        val origin: ArcSchematicPoint
        val minimum: ArcSchematicPoint
        if (version == 3) {
            origin = vector(metadata.getCompound("WorldEdit"), "Origin")
            minimum = add(offset, origin)
        } else {
            minimum = offset
            val weOffset = if (metadata["WEOffsetX"] != null) ArcSchematicPoint(
                metadata.requiredInt("WEOffsetX"), metadata.requiredInt("WEOffsetY"), metadata.requiredInt("WEOffsetZ"),
            ) else ZERO
            origin = subtract(minimum, weOffset)
        }
        val dataVersion = if (version == 1) 1631 else tag.requiredInt("DataVersion")
        val blocks = if (version == 3) tag.requiredCompound("Blocks") else tag
        val rawPalette = blocks.requiredCompound("Palette")
        if (version < 3 && tag["PaletteMax"] != null && tag.requiredInt("PaletteMax") != rawPalette.size()) {
            malformed("Schematic palette size differs from PaletteMax")
        }
        val palette = ArrayList<SchematicState>(rawPalette.size())
        val ids = HashMap<Int, Int>()
        for ((text, value) in rawPalette) {
            val id = (value as? IntBinaryTag)?.value() ?: malformed("Schematic palette ID is not an integer")
            if (id < 0 || ids.put(id, palette.size) != null) malformed("Duplicate or negative schematic palette ID")
            palette += SchematicState(text, dataVersion, schematicPaletteTemplate(text, dataVersion))
        }
        if (palette.isEmpty()) malformed("Schematic block palette is empty")
        val volume = width * height * length
        val indices = IntArray(volume)
        val data = blocks.requiredBytes(if (version == 3) "Data" else "BlockData")
        var cursor = 0
        repeat(volume) { source ->
            checkDecodeInterrupted(source)
            var id = 0
            var shift = 0
            while (true) {
                if (cursor == data.size || shift >= 35) malformed("Truncated or oversized schematic varint")
                val byte = data[cursor++].toInt() and 255
                if (shift == 28 && byte and 0xf8 != 0) malformed("Schematic palette index overflows an integer")
                id = id or ((byte and 127) shl shift)
                if (byte and 128 == 0) break
                shift += 7
            }
            indices[orderedIndex(source, width, length)] = ids[id] ?: malformed("Schematic block references an absent palette entry")
        }
        if (cursor != data.size) malformed("Schematic block data exceeds its declared volume")
        val blockEntities = HashMap<Int, CompoundBinaryTag>()
        val entityList = blocks["BlockEntities"] ?: if (version < 3) blocks["TileEntities"] else null
        for (entity in compounds(entityList)) {
            val local = vector(entity, "Pos", required = true)
            val index = localIndex(local, width, height, length)
            val position = add(minimum, local)
            val body = if (version == 3) entity.getCompound("Data") else entity.remove("Id").remove("Pos")
            val id = entity["Id"] as? StringBinaryTag ?: malformed("Schematic block entity has no string ID")
            blockEntities[index] = body.put("id", id).putInt("x", position.x).putInt("y", position.y).putInt("z", position.z)
        }
        return DecodedSchematic(minimum, add(minimum, ArcSchematicPoint(width - 1, height - 1, length - 1)), origin,
            palette, indices, blockEntities, if (version == 3) tag["Biomes"] != null else tag["BiomeData"] != null,
            compounds(tag["Entities"]).size, dataVersion)
    }

    @Suppress("DEPRECATION")
    private fun legacy(tag: CompoundBinaryTag): DecodedSchematic {
        val (width, height, length) = dimensions(tag)
        val volume = width * height * length
        val blocks = tag.requiredBytes("Blocks")
        val data = tag.requiredBytes("Data")
        val extra = tag.getByteArray("AddBlocks")
        if (blocks.size != volume || data.size != volume || extra.size > (volume + 1) / 2) malformed("Legacy schematic arrays differ from their declared volume")
        val hasOrigin = listOf("WEOriginX", "WEOriginY", "WEOriginZ", "WEOffsetX", "WEOffsetY", "WEOffsetZ").all { tag[it] is IntBinaryTag }
        val minimum = if (hasOrigin) ArcSchematicPoint(tag.getInt("WEOriginX"), tag.getInt("WEOriginY"), tag.getInt("WEOriginZ")) else ZERO
        val origin = if (hasOrigin) subtract(minimum, ArcSchematicPoint(tag.getInt("WEOffsetX"), tag.getInt("WEOffsetY"), tag.getInt("WEOffsetZ"))) else ZERO
        fun id(index: Int): Int {
            val high = if (index / 2 < extra.size) ((extra[index / 2].toInt() ushr (4 * (index and 1))) and 15) shl 8 else 0
            return (blocks[index].toInt() and 255) or high
        }
        val entities = HashMap<Int, CompoundBinaryTag>()
        for (entity in compounds(tag["TileEntities"])) {
            val local = ArcSchematicPoint(entity.requiredInt("x"), entity.requiredInt("y"), entity.requiredInt("z"))
            val position = add(minimum, local)
            entities[localIndex(local, width, height, length)] = legacyBlockEntityNbt(entity).putInt("x", position.x).putInt("y", position.y).putInt("z", position.z)
        }
        val palette = linkedMapOf<SchematicState, Int>()
        val indices = IntArray(volume)
        repeat(volume) { source ->
            checkDecodeInterrupted(source)
            val id = id(source)
            val bits = data[source].toInt() and 15
            val y = source / (width * length)
            val other = if (bits and 8 == 0) source + width * length else source - width * length
            val paired = id == 175 || id in LEGACY_DOORS
            val companion = if (paired && other in 0 until volume && id(other) == id &&
                (if (bits and 8 == 0) y + 1 < height else y > 0)) data[other].toInt() and 15 else -1
            val state = SchematicState("", 1343, legacyId = id, legacyData = bits, companionData = companion,
                legacyNbt = legacyStateNbt(id, entities[orderedIndex(source, width, length)]))
            indices[orderedIndex(source, width, length)] = palette.getOrPut(state) { palette.size }
            // These legacy tile entities became ordinary block-state properties in 1.13.
            if (id == 25 || id == 140) entities.remove(orderedIndex(source, width, length))
        }
        return DecodedSchematic(minimum, add(minimum, ArcSchematicPoint(width - 1, height - 1, length - 1)), origin,
            palette.keys.toList(), indices, entities, false, compounds(tag["Entities"]).size, 1343)
    }

    override fun canonicalize(state: SchematicState): SchematicPaletteEntry {
        check(Bukkit.isPrimaryThread()) { "Schematic palettes require the Paper primary thread" }
        val data = if (state.legacyId >= 0) applyLegacyStateNbt(legacyData(state), state) else if (state.dataVersion in 0 until Bukkit.getUnsafe().dataVersion) {
            nativeSchematicState(checkNotNull(state.template)).blockData
        } else try {
            Bukkit.createBlockData(state.text)
        } catch (_: IllegalArgumentException) {
            // Preserve the former reader's unknown-state fallback (for example removed/mod blocks).
            Bukkit.createBlockData("minecraft:air")
        }
        return entry(data)
    }

    @Suppress("DEPRECATION")
    private fun legacyData(state: SchematicState): BlockData {
        val material = LEGACY_MATERIALS[state.legacyId] ?: return Bukkit.createBlockData("minecraft:air")
        fun convert(bits: Int) = Bukkit.getUnsafe().fromLegacy(material, bits.toByte())
        val data = convert(state.legacyData)
        if (state.companionData < 0) return data
        if (data is Door) {
            val lower = convert(if (state.legacyData and 8 == 0) state.legacyData else state.companionData) as Door
            val upperBits = if (state.legacyData and 8 != 0) state.legacyData else state.companionData
            data.facing = lower.facing
            data.isOpen = lower.isOpen
            data.hinge = if (upperBits and 1 != 0) Door.Hinge.RIGHT else Door.Hinge.LEFT
            data.isPowered = upperBits and 2 != 0
        } else if (state.legacyId == 175 && state.legacyData and 8 != 0) {
            return convert(state.companionData).apply { (this as Bisected).half = Bisected.Half.TOP }
        }
        return data
    }

    override fun transform(entry: SchematicPaletteEntry, transform: ArcSchematicTransform): SchematicPaletteEntry {
        check(Bukkit.isPrimaryThread()) { "Schematic transforms require the Paper primary thread" }
        // createBlockData returns an owned clone. Snapshot palettes contain only detached strings.
        val data = Bukkit.createBlockData(entry.text)
        if (transform.mirrorX) {
            val stairShape = (data as? Stairs)?.shape
            data.mirror(Mirror.FRONT_BACK)
            // A geometric reflection reverses stair handedness regardless of facing. Native
            // stairs have facing-dependent mirror behavior; preserve the full-volume contract.
            if (data is Stairs) data.shape = when (checkNotNull(stairShape)) {
                Stairs.Shape.INNER_LEFT -> Stairs.Shape.INNER_RIGHT
                Stairs.Shape.INNER_RIGHT -> Stairs.Shape.INNER_LEFT
                Stairs.Shape.OUTER_LEFT -> Stairs.Shape.OUTER_RIGHT
                Stairs.Shape.OUTER_RIGHT -> Stairs.Shape.OUTER_LEFT
                Stairs.Shape.STRAIGHT -> Stairs.Shape.STRAIGHT
            }
            // Native mirrors preserve chest pair side; a reflection must reverse its handedness.
            if (data is Chest) data.type = when (data.type) {
                Chest.Type.LEFT -> Chest.Type.RIGHT
                Chest.Type.RIGHT -> Chest.Type.LEFT
                Chest.Type.SINGLE -> Chest.Type.SINGLE
            }
        }
        data.rotate(when (Math.floorMod(transform.quarterTurns, 4)) {
            1 -> StructureRotation.CLOCKWISE_90
            2 -> StructureRotation.CLOCKWISE_180
            3 -> StructureRotation.COUNTERCLOCKWISE_90
            else -> StructureRotation.NONE
        })
        return entry(data)
    }

    private fun entry(data: BlockData) = SchematicPaletteEntry(data.asString, data.material.isAir, data.material == Material.BARRIER)
    private fun dimensions(tag: CompoundBinaryTag): IntArray {
        val sides = intArrayOf(tag.requiredShort("Width"), tag.requiredShort("Height"), tag.requiredShort("Length"))
        if (sides.any { it <= 0 } || sides.fold(1L) { product, side -> product * side } > settings.maximumVolume) {
            failure(ArcSchematicFailure.LIMIT_EXCEEDED, "Schematic dimensions exceed the configured volume")
        }
        return sides
    }

    companion object {
        private val ZERO = ArcSchematicPoint(0, 0, 0)
        private val LEGACY_DOORS = setOf(64, 71, 193, 194, 195, 196, 197)
        // Paper rewrites Material.values() (also Kotlin entries) to modern-only values in plugins.
        // Class.enumConstants keeps legacy IDs; numeric block IDs avoid registry-backed isBlock.
        @Suppress("DEPRECATION")
        private val LEGACY_MATERIALS by lazy {
            Material::class.java.enumConstants.filter { it.isLegacy && it.id in 0..255 }.associateBy { it.id }
        }
    }
}

internal fun malformed(message: String): Nothing = failure(ArcSchematicFailure.UNSUPPORTED_FORMAT, message)
private fun CompoundBinaryTag.requiredInt(key: String) = (get(key) as? IntBinaryTag)?.value() ?: malformed("Schematic $key is not an integer")
private fun CompoundBinaryTag.requiredShort(key: String) = ((get(key) as? ShortBinaryTag)?.value()?.toInt() ?: malformed("Schematic $key is not a short")) and 65535
private fun CompoundBinaryTag.requiredBytes(key: String) = (get(key) as? ByteArrayBinaryTag)?.value() ?: malformed("Schematic $key is not a byte array")
private fun CompoundBinaryTag.requiredCompound(key: String) = get(key) as? CompoundBinaryTag ?: malformed("Schematic $key is not a compound")
private fun compounds(tag: BinaryTag?): List<CompoundBinaryTag> {
    if (tag == null) return emptyList()
    if (tag !is ListBinaryTag || tag.any { it !is CompoundBinaryTag }) malformed("Schematic entity data is not a compound list")
    return tag.map { it as CompoundBinaryTag }
}
private fun vector(tag: CompoundBinaryTag, key: String, required: Boolean = false): ArcSchematicPoint {
    val value = tag[key] ?: if (required) malformed("Schematic $key is missing") else return ArcSchematicPoint(0, 0, 0)
    val parts = (value as? IntArrayBinaryTag)?.value() ?: malformed("Schematic $key is not an integer vector")
    if (parts.size != 3) malformed("Schematic $key must have three coordinates")
    return ArcSchematicPoint(parts[0], parts[1], parts[2])
}
private fun add(a: ArcSchematicPoint, b: ArcSchematicPoint) = ArcSchematicPoint(Math.addExact(a.x, b.x), Math.addExact(a.y, b.y), Math.addExact(a.z, b.z))
private fun orderedIndex(source: Int, width: Int, length: Int): Int = ((source / (width * length)) * width + source % width) * length + (source / width) % length
private fun localIndex(local: ArcSchematicPoint, width: Int, height: Int, length: Int): Int {
    if (local.x !in 0 until width || local.y !in 0 until height || local.z !in 0 until length) malformed("Schematic block entity is outside its declared volume")
    return (local.y * width + local.x) * length + local.z
}
private fun checkDecodeInterrupted(index: Int) {
    if (index % 16_384 == 0 && Thread.currentThread().isInterrupted) failure(ArcSchematicFailure.CLOSED, "Schematic decode interrupted")
}

/** Only source-version palette states may enter a source-version vanilla structure. */
internal fun schematicPaletteTemplate(text: String, dataVersion: Int): ByteArray {
    val name = text.substringBefore('[')
    val state = CompoundBinaryTag.builder().putString("Name", name)
    if ('[' in text) {
        if (!text.endsWith(']')) malformed("Malformed schematic block state")
        val properties = CompoundBinaryTag.builder()
        for (part in text.substringAfter('[').dropLast(1).split(',')) {
            val separator = part.indexOf('=')
            if (separator <= 0 || separator == part.lastIndex) malformed("Malformed schematic block property")
            properties.putString(part.substring(0, separator), part.substring(separator + 1))
        }
        state.put("Properties", properties.build())
    }
    fun vector(value: Int) = ListBinaryTag.from(List(3) { IntBinaryTag.intBinaryTag(value) })
    val block = CompoundBinaryTag.builder().put("pos", vector(0)).putInt("state", 0)
    val structure = CompoundBinaryTag.builder().putInt("DataVersion", if (dataVersion >= 0) dataVersion else Int.MAX_VALUE)
        .put("size", vector(1)).put("palette", ListBinaryTag.from(listOf(state.build())))
        .put("blocks", ListBinaryTag.from(listOf(block.build()))).put("entities", ListBinaryTag.empty()).build()
    return ByteArrayOutputStream().also { BinaryTagIO.writer().write(structure, it, BinaryTagIO.Compression.GZIP) }.toByteArray()
}

/** Worker-owned bytes keep source NBT separate from the already-current, possibly transformed palette. */
internal data class PreparedSchematicBlockEntity(val dataVersion: Int, val bytes: ByteArray)

internal fun schematicBlockEntityPayload(dataVersion: Int, tag: CompoundBinaryTag): PreparedSchematicBlockEntity {
    val relocated = tag.putInt("x", 0).putInt("y", 0).putInt("z", 0)
    val bytes = ByteArrayOutputStream().also { BinaryTagIO.writer().write(relocated, it) }.toByteArray()
    return PreparedSchematicBlockEntity(dataVersion, bytes)
}

/** Stable historical Lin tag spelling for policy visitors; compound key order is not significant. */
internal fun schematicNbtInspection(tag: BinaryTag): String = when (tag) {
    is CompoundBinaryTag -> tag.joinToString(", ", "LinCompoundTag{", "}") { "${it.key}=${schematicNbtInspection(it.value)}" }
    is ListBinaryTag -> tag.joinToString(", ", "LinListTag[", "]", transform = ::schematicNbtInspection)
    is ByteArrayBinaryTag -> "LinByteArrayTag[${tag.value().contentToString()}]"
    is IntArrayBinaryTag -> "LinIntArrayTag[${tag.value().contentToString()}]"
    is LongArrayBinaryTag -> "LinLongArrayTag[${tag.value().contentToString()}]"
    is StringBinaryTag -> "LinStringTag[${tag.value()}]"
    is ByteBinaryTag -> "LinByteTag[${tag.value()}]"
    is ShortBinaryTag -> "LinShortTag[${tag.value()}]"
    is IntBinaryTag -> "LinIntTag[${tag.value()}]"
    is LongBinaryTag -> "LinLongTag[${tag.value()}]"
    is FloatBinaryTag -> "LinFloatTag[${tag.value()}]"
    is DoubleBinaryTag -> "LinDoubleTag[${tag.value()}]"
    else -> "LinEndTag[null]"
}

internal fun schematicNbtBytes(tag: CompoundBinaryTag): Int = ByteArrayOutputStream().also { BinaryTagIO.writer().write(tag, it) }.size()
