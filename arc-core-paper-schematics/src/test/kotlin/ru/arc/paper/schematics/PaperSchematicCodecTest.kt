package ru.arc.paper.schematics

import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.nbt.*
import ru.arc.paper.api.ArcSchematicCacheSettings
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicTransform
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class PaperSchematicCodecTest : FreeSpec({
    "Sponge versions retain the historical origin and convert X-Z-Y source order to Y-X-Z snapshot order" {
        for (version in 1..3) {
            val decoded = decode(spongeFixture(version))
            decoded.minimum shouldBe if (version == 3) ArcSchematicPoint(13, 24, 35) else ArcSchematicPoint(3, 4, 5)
            decoded.origin shouldBe if (version == 3) ArcSchematicPoint(10, 20, 30) else ArcSchematicPoint(10, 12, 14)
            decoded.maximum shouldBe if (version == 3) ArcSchematicPoint(14, 24, 36) else ArcSchematicPoint(4, 4, 6)
            decoded.indices.map { decoded.palette[it].text } shouldBe listOf("minecraft:stone", "minecraft:barrier", "minecraft:air", "minecraft:stone")
            decoded.hasBiomes shouldBe true
            decoded.entityCount shouldBe 1
            val nbt = decoded.blockEntities.getValue(2)
            nbt.getString("id") shouldBe "minecraft:chest"
            nbt.getString("CustomName") shouldBe "unchanged"
            nbt["Id"] shouldBe null
            nbt["Pos"] shouldBe null
            nbt.getInt("x") shouldBe decoded.minimum.x + 1
            nbt.getInt("y") shouldBe decoded.minimum.y
            nbt.getInt("z") shouldBe decoded.minimum.z
        }
    }

    "MCEdit keeps absolute minimum, origin, numeric IDs, high nibbles and two-block metadata" {
        val body = CompoundBinaryTag.builder().putString("Materials", "Alpha")
            .putShort("Width", 1).putShort("Height", 2).putShort("Length", 1)
            .putInt("WEOriginX", 100).putInt("WEOriginY", 64).putInt("WEOriginZ", -30)
            .putInt("WEOffsetX", -4).putInt("WEOffsetY", 1).putInt("WEOffsetZ", -2)
            .putByteArray("Blocks", byteArrayOf(64, 64)).putByteArray("Data", byteArrayOf(5, 11)).build()
        val decoded = decode(body)
        decoded.minimum shouldBe ArcSchematicPoint(100, 64, -30)
        decoded.origin shouldBe ArcSchematicPoint(104, 63, -28)
        decoded.palette.map { Triple(it.legacyId, it.legacyData, it.companionData) } shouldBe listOf(Triple(64, 5, 11), Triple(64, 11, 5))
        val extended = decode(body.putByteArray("AddBlocks", byteArrayOf(0x21)))
        extended.palette.map { it.legacyId } shouldBe listOf(320, 576)
    }

    "legacy NBT variants survive flattening and retired tile payloads become block properties" {
        val note = CompoundBinaryTag.builder().putString("id", "Music").putInt("x", 0).putInt("y", 0).putInt("z", 0)
            .putByte("note", 17).build()
        val pot = CompoundBinaryTag.builder().putString("id", "FlowerPot").putInt("x", 1).putInt("y", 0).putInt("z", 0)
            .putString("Item", "minecraft:red_flower").putInt("Data", 7).build()
        val chest = CompoundBinaryTag.builder().putString("id", "Chest").putInt("x", 2).putInt("y", 0).putInt("z", 0)
            .put("Items", ListBinaryTag.empty()).build()
        val source = CompoundBinaryTag.builder().putString("Materials", "Alpha")
            .putShort("Width", 3).putShort("Height", 1).putShort("Length", 1)
            .putByteArray("Blocks", byteArrayOf(25, 140.toByte(), 54)).putByteArray("Data", byteArrayOf(0, 0, 2))
            .put("TileEntities", ListBinaryTag.from(listOf(note, pot, chest))).build()
        val decoded = decode(source)
        decoded.palette[decoded.indices[0]].legacyNbt?.getByte("note") shouldBe 17.toByte()
        val potState = decoded.palette[decoded.indices[1]]
        legacyVariant(potState.legacyId, potState.legacyData, checkNotNull(potState.legacyNbt)) shouldBe "minecraft:potted_pink_tulip"
        decoded.blockEntities.keys shouldBe setOf(2)
        decoded.blockEntities.getValue(2).getString("id") shouldBe "minecraft:chest"
        legacyVariant(26, 0, CompoundBinaryTag.builder().putInt("color", 11).build()) shouldBe "minecraft:blue_bed"
        legacyVariant(176, 0, CompoundBinaryTag.builder().putInt("Base", 0).build()) shouldBe "minecraft:black_banner"
        legacyVariant(177, 2, CompoundBinaryTag.builder().putInt("Base", 15).build()) shouldBe "minecraft:white_wall_banner"
        legacyVariant(144, 1, CompoundBinaryTag.builder().putByte("SkullType", 3).build()) shouldBe "minecraft:player_head"
        legacyVariant(144, 2, CompoundBinaryTag.builder().putByte("SkullType", 1).build()) shouldBe "minecraft:wither_skeleton_wall_skull"
        legacyVariant(140, 0, CompoundBinaryTag.builder().putInt("Item", 6).putInt("Data", 5).build()) shouldBe "minecraft:potted_dark_oak_sapling"
        legacyBlockEntityNbt(CompoundBinaryTag.builder().putString("id", "Control").build()).getString("id") shouldBe "minecraft:command_block"
    }

    "sparse palette IDs use bounded varints and reject absent, overflowing, truncated or excess cells" {
        val original = spongeFixture(2)
        val sparse = original.put("Palette", CompoundBinaryTag.builder().putInt("minecraft:stone", 128).build())
            .putInt("PaletteMax", 1).putByteArray("BlockData", byteArrayOf(-128, 1, -128, 1, -128, 1, -128, 1))
        decode(sparse).indices.toList() shouldBe listOf(0, 0, 0, 0)
        for (bytes in listOf(byteArrayOf(), byteArrayOf(127, 0, 0, 0), byteArrayOf(-128), byteArrayOf(0, 0, 0, 0, 0),
            byteArrayOf(-1, -1, -1, -1, 127))) {
            shouldThrow<ArcSchematicException> { decode(original.putByteArray("BlockData", bytes)) }
                .reason shouldBe ArcSchematicFailure.UNSUPPORTED_FORMAT
        }
        shouldThrow<ArcSchematicException> { decode(original.putInt("PaletteMax", 100)) }
            .reason shouldBe ArcSchematicFailure.UNSUPPORTED_FORMAT
    }

    "limits and block-entity positions are checked without initializing Bukkit or WorldEdit" {
        shouldThrow<ArcSchematicException> { decode(spongeFixture(2), ArcSchematicCacheSettings.defaults().copy(maximumVolume = 3)) }
            .reason shouldBe ArcSchematicFailure.LIMIT_EXCEEDED
        val invalidEntity = CompoundBinaryTag.builder().putString("Id", "minecraft:chest").putIntArray("Pos", intArrayOf(2, 0, 0)).build()
        shouldThrow<ArcSchematicException> { decode(spongeFixture(2).put("BlockEntities", ListBinaryTag.from(listOf(invalidEntity)))) }
            .reason shouldBe ArcSchematicFailure.UNSUPPORTED_FORMAT
    }

    "legacy sign normalization retains JSON components and wraps every other line as untruncated literal text" {
        val component = "{\"text\":\"click\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/say retained\"}}"
        for (literal in listOf("", "plain old text", "{broken", "[\"array\"]", "\"quoted\"", "line\nwith \"quotes\" and \\", "x".repeat(12_000))) {
            val source = CompoundBinaryTag.builder().putString("id", "Sign").putString("Text1", literal).putString("Text2", component).build()
            val normalized = legacyBlockEntityNbt(source)
            normalized.getString("id") shouldBe "minecraft:sign"
            JsonParser.parseString(normalized.getString("Text1")).asJsonObject.get("text").asString shouldBe literal
            normalized.getString("Text2") shouldBe component
            normalized["Text3"] shouldBe null
            source.getString("Text1") shouldBe literal
        }
    }

    "native palette templates contain only source-version states and cannot mislabel current NBT placement states" {
        val bytes = schematicPaletteTemplate("minecraft:chest[facing=west,type=left,waterlogged=false]", 2975)
        val native = ByteArrayInputStream(bytes).use { BinaryTagIO.reader().read(it, BinaryTagIO.Compression.GZIP) }
        native.getInt("DataVersion") shouldBe 2975
        native.getList("size").map { (it as IntBinaryTag).value() } shouldBe listOf(1, 1, 1)
        native.getList("palette").getCompound(0).getCompound("Properties").getString("type") shouldBe "left"
        native.getList("blocks").getCompound(0)["nbt"] shouldBe null
    }

    "old block-entity NBT remains separate from transformed current palettes and relocates only root coordinates" {
        val body = CompoundBinaryTag.builder().putString("id", "minecraft:chest").putInt("x", 12).putInt("y", 34).putInt("z", 56)
            .put("nested", CompoundBinaryTag.builder().putInt("x", 777).build()).build()
        val position = ArcSchematicPoint(12, 34, 56)
        val originalPalette = listOf(SchematicPaletteEntry("minecraft:chest[facing=west,type=left,waterlogged=false]", false, false))
        val snapshot = SchematicSnapshot(Any(), position, position, position, "0".repeat(64), false, 0, originalPalette, intArrayOf(0), mapOf(0 to body), 1343)
        val transformedPalette = listOf(SchematicPaletteEntry("minecraft:chest[facing=south,type=right,waterlogged=false]", false, false))
        val transformed = snapshot.transformed(ArcSchematicTransform(1, true), transformedPalette)
        val prepared = transformed.prepareBlockEntities().getValue(0)
        prepared.dataVersion shouldBe 1343
        val nbt = ByteArrayInputStream(prepared.bytes).use { BinaryTagIO.reader().read(it) }
        nbt.getString("id") shouldBe "minecraft:chest"
        nbt["palette"] shouldBe null
        nbt.getInt("x") shouldBe 0
        nbt.getInt("y") shouldBe 0
        nbt.getInt("z") shouldBe 0
        nbt.getCompound("nested").getInt("x") shouldBe 777
        body.getInt("x") shouldBe 12
    }

    "inspection retains Lin tag spelling and every nested executable string used by ArcEvents policy" {
        val policy = Regex("(?i)(run_command|suggest_command|minecraft:(?:command_block|chain_command_block|repeating_command_block)|(?:^|[,{ ])command[=:])")
        for ((key, text) in listOf("Command" to "say denied", "Text1" to "{\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op player\"}}",
            "components" to "suggest_command", "id" to "minecraft:chain_command_block")) {
            val body = CompoundBinaryTag.builder().put("nested", ListBinaryTag.from(listOf(CompoundBinaryTag.builder().putString(key, text).build()))).build()
            policy.containsMatchIn(schematicNbtInspection(body)) shouldBe true
        }
        schematicNbtInspection(CompoundBinaryTag.builder().putString("CustomName", "safe").build()) shouldBe "LinCompoundTag{CustomName=LinStringTag[safe]}"
        schematicNbtInspection(ByteArrayBinaryTag.byteArrayBinaryTag(1, -2)) shouldBe "LinByteArrayTag[[1, -2]]"
        schematicNbtInspection(ListBinaryTag.from(listOf(IntBinaryTag.intBinaryTag(3)))) shouldBe "LinListTag[LinIntTag[3]]"
        policy.containsMatchIn(schematicNbtInspection(CompoundBinaryTag.builder().putString("CustomName", "safe").build())) shouldBe false
    }

    System.getenv("ARC_SCHEMATIC_FIXTURE_DIRECTORY")?.let { directory ->
        "private live files decode without a server registry or WorldEdit" {
            for (index in 0..1) {
                val path = Path.of(directory, "fixture-$index.schem")
                val files = SchematicFiles(ArcSchematicCacheSettings.defaults())
                val acquired = files.read(files.resolve(path.parent, path.fileName.toString()))
                val decoded = PaperSchematicCodec(ArcSchematicCacheSettings.defaults()).decode(acquired)
                decoded.indices.isNotEmpty() shouldBe true
                println("private fixture=$index sha256=${acquired.sha256} size=${decoded.maximum.x-decoded.minimum.x+1},${decoded.maximum.y-decoded.minimum.y+1},${decoded.maximum.z-decoded.minimum.z+1} minimum=${decoded.minimum} origin=${decoded.origin} palette=${decoded.palette.size} blockEntities=${decoded.blockEntities.size} dataVersion=${decoded.dataVersion}")
            }
        }
    }
})

private fun decode(tag: CompoundBinaryTag, settings: ArcSchematicCacheSettings = ArcSchematicCacheSettings.defaults()): DecodedSchematic {
    val bytes = ByteArrayOutputStream().also { BinaryTagIO.writer().writeNamed(java.util.Map.entry("Schematic", tag), it, BinaryTagIO.Compression.GZIP) }.toByteArray()
    return PaperSchematicCodec(settings).decode(SchematicBytes(SchematicFile(Path.of("synthetic.schem"), FileIdentity(bytes.size.toLong(), FileTime.fromMillis(0), null)), bytes, "0".repeat(64)))
}

private fun spongeFixture(version: Int): CompoundBinaryTag {
    val entity = CompoundBinaryTag.builder().putString("Id", "minecraft:chest").putIntArray("Pos", intArrayOf(1, 0, 0))
    val body = CompoundBinaryTag.builder().putString("CustomName", "unchanged").build()
    if (version == 3) entity.put("Data", body) else entity.put(body)
    val blocks = CompoundBinaryTag.builder().put("Palette", CompoundBinaryTag.builder().putInt("minecraft:stone", 0).putInt("minecraft:air", 1).putInt("minecraft:barrier", 2).build())
        .putByteArray(if (version == 3) "Data" else "BlockData", byteArrayOf(0, 1, 2, 0))
        .put("BlockEntities", ListBinaryTag.from(listOf(entity.build()))).build()
    val schematic = CompoundBinaryTag.builder().putInt("Version", version).putInt("DataVersion", 2975)
        .putShort("Width", 2).putShort("Height", 1).putShort("Length", 2).putIntArray("Offset", intArrayOf(3, 4, 5))
        .put("Metadata", CompoundBinaryTag.builder().putInt("WEOffsetX", -7).putInt("WEOffsetY", -8).putInt("WEOffsetZ", -9)
            .put("WorldEdit", CompoundBinaryTag.builder().putIntArray("Origin", intArrayOf(10, 20, 30)).build()).build())
        .put("Entities", ListBinaryTag.from(listOf(CompoundBinaryTag.builder().putString("Id", "minecraft:pig").build())))
    if (version == 3) schematic.put("Blocks", blocks).put("Biomes", CompoundBinaryTag.empty())
    else schematic.put(blocks).putInt("PaletteMax", 3).putByteArray("BiomeData", byteArrayOf(0, 0, 0, 0))
    return if (version == 3) CompoundBinaryTag.builder().put("Schematic", schematic.build()).build() else schematic.build()
}
