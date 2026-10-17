package ru.arc.paper.schematics

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extension.platform.Capability
import com.sk89q.worldedit.extension.platform.Platform
import com.sk89q.worldedit.extension.platform.PlatformManager
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.util.SideEffect
import com.sk89q.worldedit.util.SideEffectSet
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.block.BlockState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcBlockProtectionService
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import ru.arc.paper.api.ArcSchematicPasteOptions
import ru.arc.paper.api.ArcSchematicPasteTarget
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletionException

class NativeSchematicProtectionTest : FunSpec({
    test("paste rechecks protection between bounded batches and stops before the newly protected block") {
        nativeScenario { paper, world, nativeWorld, nativeState ->
            val snapshot = protectionSnapshot(3, nativeState)
            val work = NativeSchematicMutation.paste("builder", snapshot,
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false))
            work.step(2) shouldBe 2 // One loaded chunk check and one block.
            verify(exactly = 1) { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) }
            val plugin = paper.createSimplePlugin("Protection")
            paper.server.servicesManager.register(ArcBlockProtectionService::class.java,
                ArcBlockProtectionService { id, x, _, _ -> id == world.uid && x == 2 }, plugin, ServicePriority.Normal)
            val failure = shouldThrow<ArcSchematicException> { work.step(4) }
            failure.reason shouldBe ArcSchematicFailure.PROTECTED_AREA
            work.fail(failure)
            (shouldThrow<CompletionException> { work.completion.join() }.cause as ArcSchematicException).reason shouldBe ArcSchematicFailure.PROTECTED_AREA
            work.step(10) shouldBe 0
            verify(exactly = 1) { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) }
        }
    }

    test("connection repair checks the library owner immediately before native mutation") {
        nativeScenario { paper, world, nativeWorld, _ ->
            val fence = world.getBlockAt(1, 64, 1).apply { type = Material.OAK_FENCE }
            val cell = ArcSchematicBlock(ArcSchematicPoint(1, 64, 1), fence.blockData.asString, false, false)
            val plugin = paper.createSimplePlugin("Protection")
            paper.server.servicesManager.register(ArcBlockProtectionService::class.java, object : ArcBlockProtectionService {
                override fun isProtected(worldId: UUID, x: Int, y: Int, z: Int) = true
                override fun permitsSchematicMutation(owner: String, worldId: UUID, x: Int, y: Int, z: Int) = owner == "city"
            }, plugin, ServicePriority.Normal)
            val denied = NativeSchematicMutation.repair("builder", world.uid, listOf(cell))
            shouldThrow<ArcSchematicException> { denied.step(10) }.reason shouldBe ArcSchematicFailure.PROTECTED_AREA
            verify(exactly = 0) { nativeWorld.setBlock(any(), any<BlockState>(), any<SideEffectSet>()) }
            val allowed = NativeSchematicMutation.repair("city", world.uid, listOf(cell))
            allowed.step(10)
            allowed.completion.join().changed shouldBe 1
            verify(exactly = 1) { nativeWorld.setBlock(BlockVector3.at(1, 64, 1), any<BlockState>(), any<SideEffectSet>()) }
        }
    }
})

private fun protectionSnapshot(sizeX: Int, native: BlockState) = SchematicSnapshot(
    Any(), ArcSchematicPoint(0, 0, 0), ArcSchematicPoint(sizeX - 1, 0, 0), ArcSchematicPoint(0, 0, 0),
    "0".repeat(64), false, 0, listOf(SchematicPaletteEntry("minecraft:stone", false, false, native)), IntArray(sizeX), emptyMap(),
)

private fun nativeScenario(block: (MockBukkitTestRuntime, World, com.sk89q.worldedit.world.World, BlockState) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        failOnUnsupportedMockBukkitOperation {
            val world = paper.addSimpleWorld("schematic")
            world.loadChunk(0, 0)
            val nativeWorld = mockk<com.sk89q.worldedit.world.World>()
            val nativeState = mockk<BlockState>()
            every { nativeState.toBaseBlock() } returns mockk<BaseBlock>()
            every { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) } returns true
            every { nativeWorld.setBlock(any(), any<BlockState>(), any<SideEffectSet>()) } returns true
            mockkStatic(WorldEdit::class, BukkitAdapter::class)
            try {
                val worldEdit = mockk<WorldEdit>()
                val manager = mockk<PlatformManager>()
                val platform = mockk<Platform>()
                every { WorldEdit.getInstance() } returns worldEdit
                every { worldEdit.platformManager } returns manager
                every { manager.queryCapability(Capability.WORLD_EDITING) } returns platform
                every { platform.supportedSideEffects } returns SideEffect.values().toSet()
                every { BukkitAdapter.adapt(world as World) } returns nativeWorld
                every { BukkitAdapter.adapt(any<BlockData>()) } returns nativeState
                block(paper, world, nativeWorld, nativeState)
            } finally {
                unmockkStatic(WorldEdit::class, BukkitAdapter::class)
            }
        }
    }
}
