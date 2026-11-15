package ru.arc.paper.schematics

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.UnsafeValues
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Chest
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.structure.Mirror
import org.bukkit.block.structure.StructureRotation
import ru.arc.paper.api.ArcSchematicCacheSettings
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicTransform
import java.lang.classfile.ClassFile
import java.lang.classfile.instruction.InvokeInstruction

class PaperSchematicTransformTest : FreeSpec({
    "compiled legacy lookup never invokes the Material enumeration that Paper rewrites to modern-only values" {
        val codec = PaperSchematicCodec::class.java
        for (type in listOf(codec) + codec.declaredClasses) {
            val bytes = checkNotNull(type.getResourceAsStream("/${type.name.replace('.', '/')}.class")).use { it.readBytes() }
            val invokes = ClassFile.of().parse(bytes).methods().flatMap { it.code().orElse(null)?.elementList().orEmpty() }
                .filterIsInstance<InvokeInstruction>()
            invokes.any { it.owner().asInternalName() == "org/bukkit/Material" && it.name().equalsString("values") } shouldBe false
        }
    }

    @Suppress("DEPRECATION")
    "legacy stone, chest and both sign IDs reach the native block-data converter without registry classification" {
        mockkStatic(Bukkit::class)
        try {
            val unsafe = mockk<UnsafeValues>()
            every { Bukkit.isPrimaryThread() } returns true
            every { Bukkit.getUnsafe() } returns unsafe
            for ((id, bits, material) in listOf(Triple(1, 0, Material.LEGACY_STONE), Triple(54, 2, Material.LEGACY_CHEST),
                Triple(63, 0, Material.LEGACY_SIGN_POST), Triple(68, 2, Material.LEGACY_WALL_SIGN))) {
                val data = mockk<BlockData>()
                val expected = when (id) {
                    1 -> "minecraft:stone"
                    54 -> "minecraft:chest[facing=north,type=single,waterlogged=false]"
                    63 -> "minecraft:oak_sign[rotation=0,waterlogged=false]"
                    else -> "minecraft:oak_wall_sign[facing=north,waterlogged=false]"
                }
                every { unsafe.fromLegacy(material, bits.toByte()) } returns data
                every { data.asString } returns expected
                every { data.material } returns Material.STONE
                val state = SchematicState("", 1343, legacyId = id, legacyData = bits)
                PaperSchematicCodec(ArcSchematicCacheSettings.defaults()).canonicalize(state).text shouldBe expected
                verify(exactly = 1) { unsafe.fromLegacy(material, bits.toByte()) }
            }
            verify(exactly = 0) { unsafe.fromLegacy(any<org.bukkit.material.MaterialData>(), any<Boolean>()) }
        } finally { unmockkStatic(Bukkit::class) }
    }

    "all eight geometric transforms mirror local X first then rotate clockwise" {
        val point = ArcSchematicPoint(2, 3, -5)
        val plain = listOf(ArcSchematicPoint(2, 3, -5), ArcSchematicPoint(5, 3, 2), ArcSchematicPoint(-2, 3, 5), ArcSchematicPoint(-5, 3, -2))
        val mirrored = listOf(ArcSchematicPoint(-2, 3, -5), ArcSchematicPoint(5, 3, -2), ArcSchematicPoint(2, 3, 5), ArcSchematicPoint(-5, 3, 2))
        for (turns in -4..7) {
            transformPoint(point, ArcSchematicTransform(turns, false)) shouldBe plain[Math.floorMod(turns, 4)]
            transformPoint(point, ArcSchematicTransform(turns, true)) shouldBe mirrored[Math.floorMod(turns, 4)]
        }
    }

    "unknown modern palette states retain the historical air fallback" {
        mockkStatic(Bukkit::class)
        try {
            val unsafe = mockk<UnsafeValues>()
            val air = mockk<BlockData>()
            every { Bukkit.isPrimaryThread() } returns true
            every { Bukkit.getUnsafe() } returns unsafe
            every { unsafe.dataVersion } returns 4671
            every { Bukkit.createBlockData("example:removed_block") } throws IllegalArgumentException("unknown state")
            every { Bukkit.createBlockData("minecraft:air") } returns air
            every { air.asString } returns "minecraft:air"
            every { air.material } returns Material.AIR
            PaperSchematicCodec(ArcSchematicCacheSettings.defaults()).canonicalize(SchematicState("example:removed_block", 4671)) shouldBe
                SchematicPaletteEntry("minecraft:air", true, false)
        } finally { unmockkStatic(Bukkit::class) }
    }

    "palette uses the owned native mirror before rotation for doors and stairs" {
        mockkStatic(Bukkit::class)
        try {
            every { Bukkit.isPrimaryThread() } returns true
            for (data in listOf(mockk<Door>(relaxed = true), mockk<Stairs>(relaxed = true))) {
                val original = "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"
                val expected = "minecraft:oak_door[facing=east,half=lower,hinge=right,open=false,powered=false]"
                every { Bukkit.createBlockData(original) } returns data
                every { data.asString } returns expected
                every { data.material } returns Material.OAK_DOOR
                val source = SchematicPaletteEntry(original, false, false)
                PaperSchematicCodec(ArcSchematicCacheSettings.defaults()).transform(source, ArcSchematicTransform(1, true)).text shouldBe expected
                source.text shouldBe original
                verifyOrder { data.mirror(Mirror.FRONT_BACK); data.rotate(StructureRotation.CLOCKWISE_90) }
            }
        } finally { unmockkStatic(Bukkit::class) }
    }

    "reflection swaps chest pairs and leaves singles intact" {
        mockkStatic(Bukkit::class)
        try {
            every { Bukkit.isPrimaryThread() } returns true
            for ((before, after) in listOf(Chest.Type.LEFT to Chest.Type.RIGHT, Chest.Type.RIGHT to Chest.Type.LEFT, Chest.Type.SINGLE to Chest.Type.SINGLE)) {
                val data = mockk<Chest>(relaxed = true)
                val original = "minecraft:chest[facing=east,type=${before.name.lowercase()},waterlogged=false]"
                every { Bukkit.createBlockData(original) } returns data
                every { data.type } returns before
                every { data.material } returns Material.CHEST
                every { data.asString } returns "transformed"
                PaperSchematicCodec(ArcSchematicCacheSettings.defaults()).transform(SchematicPaletteEntry(original, false, false), ArcSchematicTransform(0, true))
                verifyOrder { data.mirror(Mirror.FRONT_BACK); data.type = after; data.rotate(StructureRotation.NONE) }
            }
        } finally { unmockkStatic(Bukkit::class) }
    }
})
