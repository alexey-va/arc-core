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
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcBlockProtectionService
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicCacheSettings
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
    for (vertical in listOf(false, true)) test("a matching pair writes primary first without yielding with one block left vertical=$vertical") {
        nativeScenario { _, world, nativeWorld, nativeState ->
            val written = mutableListOf<BlockVector3>()
            every { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) } answers { written += firstArg<BlockVector3>(); true }
            val work = NativeSchematicMutation.paste("builder", pairSnapshot(nativeState, vertical),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false))
            work.step(2) shouldBe 3 // One chunk check, then both halves even though only one unit remains.
            written shouldBe if (vertical) listOf(BlockVector3.at(1, 64, 1), BlockVector3.at(1, 65, 1))
                else listOf(BlockVector3.at(2, 64, 1), BlockVector3.at(1, 64, 1))
            work.step(1) shouldBe 1 // Bounded bookkeeping visit, without writing the companion again.
            work.completion.join().let { it.changed shouldBe 2; it.skipped shouldBe 0 }
            written.size shouldBe 2
        }
    }

    test("denied companion protection rejects both halves before the primary write") {
        nativeScenario { paper, world, nativeWorld, nativeState ->
            val plugin = paper.createSimplePlugin("PairProtection")
            paper.server.servicesManager.register(ArcBlockProtectionService::class.java,
                ArcBlockProtectionService { id, x, _, _ -> id == world.uid && x == 1 }, plugin, ServicePriority.Normal)
            val work = NativeSchematicMutation.paste("builder", pairSnapshot(nativeState),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false))
            shouldThrow<ArcSchematicException> { work.step(2) }.reason shouldBe ArcSchematicFailure.PROTECTED_AREA
            verify(exactly = 0) { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) }
        }
    }

    test("a companion chunk unloaded after preflight prevents either half from being written") {
        nativeScenario { _, world, nativeWorld, nativeState ->
            world.loadChunk(1, 0)
            val work = NativeSchematicMutation.paste("builder", pairSnapshot(nativeState),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(15, 64, 1)), ArcSchematicPasteOptions(false, false, false))
            work.step(2) shouldBe 2
            world.unloadChunk(1, 0)
            world.isChunkLoaded(1, 0) shouldBe false
            shouldThrow<ArcSchematicException> { work.step(1) }.reason shouldBe ArcSchematicFailure.WORLD_UNAVAILABLE
            verify(exactly = 0) { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) }
        }
    }

    test("a cropped half never writes its missing companion outside the supplied snapshot") {
        nativeScenario { _, world, nativeWorld, nativeState ->
            val crop = pairSnapshot(nativeState).cropped(ArcSchematicPoint(1, 0, 0), ArcSchematicPoint(1, 0, 0)) {}
            val work = NativeSchematicMutation.paste("builder", crop,
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false))
            work.step(10) shouldBe 2
            work.completion.join().changed shouldBe 1
            verify(exactly = 1) { nativeWorld.setBlock(BlockVector3.at(2, 64, 1), any<BaseBlock>(), any<SideEffectSet>()) }
            verify(exactly = 1) { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) }
        }
    }

    test("pair overshoot exhausts the aggregate tick before a queued follower receives budget") {
        nativeScenario { _, world, nativeWorld, nativeState ->
            val scheduler = TestTaskScheduler()
            val pair = NativeSchematicMutation.paste("builder", pairSnapshot(nativeState),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false))
            SchematicMainThread(scheduler, ArcSchematicCacheSettings.defaults().copy(blocksPerTick = 2)).use { main ->
                val budgets = mutableListOf<Int>()
                main.enqueueMutation(pair)
                main.enqueueMutation(object : SchematicMainThread.Work {
                    override val done get() = budgets.isNotEmpty()
                    override fun step(budget: Int): Int { budgets += budget; return 1 }
                    override fun fail(problem: Throwable) { throw problem }
                })
                scheduler.tick()
                budgets shouldBe emptyList()
                verify(exactly = 2) { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) }
                scheduler.tick()
                budgets shouldBe listOf(2)
                pair.completion.join().changed shouldBe 2
            }
        }
    }

    test("a second native write failure retains the existing partial failure contract without rollback") {
        nativeScenario { _, world, nativeWorld, nativeState ->
            val written = mutableListOf<BlockVector3>()
            every { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) } answers {
                written += firstArg<BlockVector3>()
                if (written.size == 2) throw IllegalStateException("second half failed")
                true
            }
            val work = NativeSchematicMutation.paste("builder", pairSnapshot(nativeState),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false))
            val problem = shouldThrow<IllegalStateException> { work.step(2) }
            work.fail(problem)
            val failure = shouldThrow<CompletionException> { work.completion.join() }.cause as ArcSchematicException
            failure.reason shouldBe ArcSchematicFailure.IO_ERROR
            failure.message shouldBe "Schematic mutation stopped after 1 changes and 0 skips; no rollback was performed"
            work.step(10) shouldBe 0
            written shouldBe listOf(BlockVector3.at(2, 64, 1), BlockVector3.at(1, 64, 1))
        }
    }

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

private fun pairSnapshot(native: BlockState, vertical: Boolean = false) = SchematicSnapshot(
    Any(), ArcSchematicPoint(0, 0, 0), if (vertical) ArcSchematicPoint(0, 1, 0) else ArcSchematicPoint(1, 0, 0),
    ArcSchematicPoint(0, 0, 0), "0".repeat(64), false, 0,
    (if (vertical) listOf("minecraft:oak_door[half=lower]", "minecraft:oak_door[half=upper]")
    else listOf("minecraft:red_bed[facing=west,part=head]", "minecraft:red_bed[facing=west,part=foot]"))
        .map { SchematicPaletteEntry(it, false, false, native) }, intArrayOf(0, 1), emptyMap(),
)

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
