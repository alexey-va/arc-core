package ru.arc.paper.schematics

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
import ru.arc.paper.api.ArcSchematicPoint

class PaperSchematicBlocksTest : FreeSpec({
    "plain native writes skip equal states and never request physics" {
        mockkStatic(Bukkit::class)
        try {
            every { Bukkit.isPrimaryThread() } returns true
            val world = mockk<World>()
            val block = mockk<Block>(relaxed = true)
            val desired = mockk<BlockData>()
            val previous = mockk<BlockData>()
            every { Bukkit.createBlockData("minecraft:stone") } returns desired
            every { desired.createBlockState() } returns mockk<BlockState>()
            every { world.getBlockAt(1, 64, 2) } returns block
            every { block.blockData } returnsMany listOf(previous, desired)
            val native = PaperSchematicBlocks()
            native.write(world, ArcSchematicPoint(1, 64, 2), "minecraft:stone", null) shouldBe true
            native.write(world, ArcSchematicPoint(1, 64, 2), "minecraft:stone", null) shouldBe false
            verify(exactly = 1) { block.setBlockData(desired, false) }
            verify(exactly = 0) { block.setBlockData(any(), true) }
        } finally { unmockkStatic(Bukkit::class) }
    }

    "old NBT loads only into the owned current-state copy and cannot contaminate an empty container" {
        mockkStatic(Bukkit::class)
        mockkObject(NativeBlockEntityNbt)
        try {
            every { Bukkit.isPrimaryThread() } returns true
            val world = mockk<World>()
            val block = mockk<Block>(relaxed = true)
            val desired = mockk<BlockData>()
            val empty = mockk<TileState>()
            val retainedPlaced = mockk<TileState>(relaxed = true)
            val emptyPlaced = mockk<TileState>(relaxed = true)
            val binding = mockk<NativeBlockEntityNbt.Binding>(relaxed = true)
            val payload = PreparedSchematicBlockEntity(1343, byteArrayOf(1))
            every { Bukkit.createBlockData("minecraft:chest") } returns desired
            every { desired.createBlockState() } returns empty
            every { world.getBlockAt(1, 64, 2) } returns block
            every { NativeBlockEntityNbt.binding() } returns binding
            var copies = 0
            every { empty.copy(any()) } answers {
                firstArg<org.bukkit.Location>().let { it.world shouldBe world; it.blockX shouldBe 1; it.blockY shouldBe 64; it.blockZ shouldBe 2 }
                if (copies++ == 0) retainedPlaced else emptyPlaced
            }
            every { retainedPlaced.update(true, false) } returns true
            every { emptyPlaced.update(true, false) } returns true
            val native = PaperSchematicBlocks()
            native.write(world, ArcSchematicPoint(1, 64, 2), "minecraft:chest", payload) shouldBe true
            native.write(world, ArcSchematicPoint(1, 64, 2), "minecraft:chest", null) shouldBe true
            verify(exactly = 1) { desired.createBlockState(); binding.load(retainedPlaced, payload) }
            verify(exactly = 0) { binding.load(empty, any()); binding.load(emptyPlaced, any()) }
            verify(exactly = 2) { empty.copy(any()) }
            for (placed in listOf(retainedPlaced, emptyPlaced)) {
                verify(exactly = 1) { placed.blockData = desired; placed.update(true, false) }
                verify(exactly = 0) { placed.update(any(), true) }
            }
            verify(exactly = 0) { block.setBlockData(any(), any()) }
        } finally {
            unmockkObject(NativeBlockEntityNbt)
            unmockkStatic(Bukkit::class)
        }
    }
})
