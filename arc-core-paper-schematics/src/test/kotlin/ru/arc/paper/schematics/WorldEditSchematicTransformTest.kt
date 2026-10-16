package ru.arc.paper.schematics

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.transform.BlockTransformExtent
import com.sk89q.worldedit.math.Vector3
import com.sk89q.worldedit.math.transform.Transform
import com.sk89q.worldedit.world.block.BlockState
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Chest
import org.bukkit.block.data.type.Door
import org.bukkit.block.structure.Mirror
import ru.arc.paper.api.ArcSchematicCacheSettings
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicTransform

class WorldEditSchematicTransformTest : FreeSpec({
    "native state transform and cell geometry agree for every rotation and reflection" {
        val points = listOf(
            ArcSchematicPoint(0, 0, -1), ArcSchematicPoint(1, 0, 0),
            ArcSchematicPoint(0, 0, 1), ArcSchematicPoint(-1, 0, 0),
            ArcSchematicPoint(2, 3, -5), ArcSchematicPoint(-7, -2, 11),
        )
        for (mirror in listOf(false, true)) for (quarterTurns in -4..7) {
            val transform = ArcSchematicTransform(quarterTurns, mirror)
            for (point in points) {
                val native = transform.worldEditTransform().apply(Vector3.at(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())).toBlockPoint()
                transformPoint(point, transform) shouldBe ArcSchematicPoint(native.x(), native.y(), native.z())
            }
        }
        // Asymmetric point detects changing reflection/rotation order; north must become east.
        transformPoint(ArcSchematicPoint(2, 3, -5), ArcSchematicTransform(1, true)) shouldBe ArcSchematicPoint(5, 3, -2)
        transformPoint(ArcSchematicPoint(0, 0, -1), ArcSchematicTransform(1, false)) shouldBe ArcSchematicPoint(1, 0, 0)
    }

    "palette transforms never mutate adapter-owned BlockData and repeat from the same immutable source" {
        val source = mockk<BlockState>()
        val transformed = mockk<BlockState>()
        val shared = mockk<BlockData>(relaxed = true)
        val output = mockk<BlockData>()
        val originalText = "minecraft:spruce_stairs[facing=north,half=bottom,shape=inner_left,waterlogged=false]"
        val transformedText = "minecraft:spruce_stairs[facing=east,half=bottom,shape=inner_right,waterlogged=false]"
        every { shared.asString } returns originalText
        every { output.asString } returns transformedText
        every { output.material } returns Material.SPRUCE_STAIRS
        mockkStatic(BukkitAdapter::class, BlockTransformExtent::class)
        try {
            // FAWE's adapter may return this same object on every access.
            every { BukkitAdapter.adapt(source) } returns shared
            every { BukkitAdapter.adapt(transformed) } returns output
            every { BlockTransformExtent.transform(source, any<Transform>()) } answers {
                val north = secondArg<Transform>().apply(Vector3.at(0.0, 0.0, -1.0))
                north shouldBe Vector3.at(1.0, 0.0, 0.0)
                transformed
            }
            val codec = WorldEditSchematicCodec(ArcSchematicCacheSettings.defaults())
            val entry = SchematicPaletteEntry(originalText, false, false, source)
            repeat(4) {
                val result = codec.transform(entry, ArcSchematicTransform(1, true))
                result.text shouldBe transformedText
                result.native shouldBe transformed
                entry.text shouldBe originalText
                entry.native shouldBe source
            }
            verify(exactly = 4) { BlockTransformExtent.transform(source, any<Transform>()) }
            verify(exactly = 0) { shared.rotate(any()); shared.mirror(any()); output.rotate(any()); output.mirror(any()) }
        } finally {
            unmockkStatic(BukkitAdapter::class, BlockTransformExtent::class)
        }
    }

    "door reflection uses an owned native clone before the common rotation" {
        val source = mockk<BlockState>()
        val mirrored = mockk<BlockState>()
        val transformed = mockk<BlockState>()
        val shared = mockk<Door>(relaxed = true)
        val owned = mockk<Door>(relaxed = true)
        val output = mockk<BlockData>()
        val originalText = "minecraft:spruce_door[facing=north,half=lower,hinge=left,open=true,powered=false]"
        val transformedText = "minecraft:spruce_door[facing=east,half=lower,hinge=right,open=true,powered=false]"
        every { shared.clone() } returns owned
        every { output.asString } returns transformedText
        every { output.material } returns Material.SPRUCE_DOOR
        mockkStatic(BukkitAdapter::class, BlockTransformExtent::class)
        try {
            every { BukkitAdapter.adapt(source) } returns shared
            every { BukkitAdapter.adapt(owned) } returns mirrored
            every { BukkitAdapter.adapt(transformed) } returns output
            every { BlockTransformExtent.transform(mirrored, any<Transform>()) } answers {
                // Reflection is already native; the remaining affine must only rotate.
                secondArg<Transform>().apply(Vector3.at(1.0, 0.0, 0.0)) shouldBe Vector3.at(0.0, 0.0, 1.0)
                transformed
            }
            val codec = WorldEditSchematicCodec(ArcSchematicCacheSettings.defaults())
            val entry = SchematicPaletteEntry(originalText, false, false, source)
            val result = codec.transform(entry, ArcSchematicTransform(1, true))
            result.text shouldBe transformedText
            result.native shouldBe transformed
            entry.text shouldBe originalText
            entry.native shouldBe source
            verifyOrder {
                shared.clone()
                owned.mirror(Mirror.FRONT_BACK)
                BukkitAdapter.adapt(owned)
                BlockTransformExtent.transform(mirrored, any<Transform>())
                BukkitAdapter.adapt(transformed)
            }
            verify(exactly = 0) { shared.rotate(any()); shared.mirror(any()); output.rotate(any()); output.mirror(any()) }
        } finally {
            unmockkStatic(BukkitAdapter::class, BlockTransformExtent::class)
        }
    }

    "chest reflection swaps pair sides on an owned clone and preserves single chests" {
        mockkStatic(BukkitAdapter::class, BlockTransformExtent::class)
        try {
            for ((original, expected) in listOf(
                Chest.Type.LEFT to Chest.Type.RIGHT,
                Chest.Type.RIGHT to Chest.Type.LEFT,
                Chest.Type.SINGLE to Chest.Type.SINGLE,
            )) {
                val source = mockk<BlockState>()
                val mirrored = mockk<BlockState>()
                val shared = mockk<Chest>(relaxed = true)
                val owned = mockk<Chest>(relaxed = true)
                val output = mockk<BlockData>()
                val originalText = "minecraft:chest[facing=east,type=${original.name.lowercase()},waterlogged=false]"
                val expectedText = "minecraft:chest[facing=west,type=${expected.name.lowercase()},waterlogged=false]"
                every { shared.type } returns original
                every { shared.clone() } returns owned
                every { output.asString } returns expectedText
                every { output.material } returns Material.CHEST
                every { BukkitAdapter.adapt(source) } returns shared
                every { BukkitAdapter.adapt(owned) } returns mirrored
                every { BukkitAdapter.adapt(mirrored) } returns output
                every { BlockTransformExtent.transform(mirrored, any<Transform>()) } answers {
                    secondArg<Transform>().isIdentity shouldBe true
                    mirrored
                }
                val entry = SchematicPaletteEntry(originalText, false, false, source)
                val result = WorldEditSchematicCodec(ArcSchematicCacheSettings.defaults())
                    .transform(entry, ArcSchematicTransform(0, true))
                result.text shouldBe expectedText
                entry.text shouldBe originalText
                entry.native shouldBe source
                verifyOrder {
                    shared.clone()
                    owned.mirror(Mirror.FRONT_BACK)
                    owned.type = expected
                    BukkitAdapter.adapt(owned)
                    BlockTransformExtent.transform(mirrored, any<Transform>())
                }
                verify(exactly = 0) { shared.type = any(); shared.mirror(any()); shared.rotate(any()) }
            }
        } finally {
            unmockkStatic(BukkitAdapter::class, BlockTransformExtent::class)
        }
    }
})
