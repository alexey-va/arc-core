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
import org.bukkit.Bukkit
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
import java.util.function.BooleanSupplier
import java.util.function.Consumer

class NativeSchematicProtectionTest : FunSpec({
    test("paced paste waits for readiness, skips native no-ops and admits one unit per interval without blocking ordinary paste") {
        nativeScenario { _, world, nativeWorld, nativeState ->
            val scheduler = TestTaskScheduler()
            var tick = 0
            var ready = false
            var readinessChecks = 0
            val writes = mutableListOf<Pair<Int, BlockVector3>>()
            every { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) } answers {
                val position = firstArg<BlockVector3>()
                writes += tick to position
                position.x() != 1
            }
            val accepted = mutableListOf<Pair<Int, ArcSchematicPoint>>()
            var observedBeforeCompletion = true
            lateinit var paced: NativeSchematicMutation
            paced = NativeSchematicMutation.paste("builder", protectionSnapshot(3, nativeState),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false),
                Consumer {
                    observedBeforeCompletion = observedBeforeCompletion && !paced.completion.isDone
                    accepted += tick to it.position
                }, BooleanSupplier { readinessChecks++; ready }, 5)
            val ordinary = NativeSchematicMutation.paste("ordinary", protectionSnapshot(2, nativeState),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(9, 64, 1)), ArcSchematicPasteOptions(false, false, false))
            SchematicMainThread(scheduler, ArcSchematicCacheSettings.defaults().copy(blocksPerTick = 32)).use { main ->
                fun advance(ticks: Int = 1) { repeat(ticks) { tick++; scheduler.tick() } }
                main.enqueueMutation(paced, paced.completion)
                main.enqueueMutation(ordinary, ordinary.completion)
                advance()
                readinessChecks shouldBe 1
                accepted shouldBe emptyList()
                ordinary.completion.join().changed shouldBe 2
                writes.map { it.second.x() } shouldBe listOf(9, 10)
                paced.completion.isDone shouldBe false

                ready = true
                advance()
                accepted shouldBe listOf(2 to ArcSchematicPoint(2, 64, 1))
                readinessChecks shouldBe 3 // False once, then one no-op and one accepted unit.
                advance(4)
                readinessChecks shouldBe 3
                ready = false
                advance()
                readinessChecks shouldBe 4
                paced.completion.isDone shouldBe false
                ready = true
                advance()
                accepted shouldBe listOf(2 to ArcSchematicPoint(2, 64, 1), 8 to ArcSchematicPoint(3, 64, 1))
                paced.completion.join().let { it.changed shouldBe 2; it.skipped shouldBe 1; it.cancelled shouldBe false }
                observedBeforeCompletion shouldBe true
                main.queuedMutations() shouldBe 0
                writes.map { it.first to it.second.x() } shouldBe listOf(1 to 9, 1 to 10, 2 to 1, 2 to 2, 8 to 3)
            }
        }
    }

    test("paced paste accepts a native pair as one unit and observes both halves before completion") {
        nativeScenario { _, world, nativeWorld, nativeState ->
            val scheduler = TestTaskScheduler()
            val target = ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1))
            val options = ArcSchematicPasteOptions(false, false, false)
            val ready = BooleanSupplier { true }
            shouldThrow<IllegalArgumentException> {
                NativeSchematicMutation.paste("builder", pairSnapshot(nativeState), target, options, Consumer {}, ready, 0)
            }
            val accepted = mutableListOf<ArcSchematicPoint>()
            var observedBeforeCompletion = true
            lateinit var paced: NativeSchematicMutation
            paced = NativeSchematicMutation.paste("builder", pairSnapshot(nativeState), target, options,
                Consumer {
                    observedBeforeCompletion = observedBeforeCompletion && !paced.completion.isDone
                    accepted += it.position
                }, ready, 5)
            SchematicMainThread(scheduler, ArcSchematicCacheSettings.defaults()).use { main ->
                main.enqueueMutation(paced, paced.completion)
                scheduler.tick()
                paced.completion.join().changed shouldBe 2
                accepted shouldBe listOf(ArcSchematicPoint(2, 64, 1), ArcSchematicPoint(1, 64, 1))
                observedBeforeCompletion shouldBe true
                verify(exactly = 2) { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) }
            }
        }
    }

    test("a throwing readiness gate fails before the native unit writes") {
        nativeScenario { _, world, nativeWorld, nativeState ->
            val scheduler = TestTaskScheduler()
            val problem = IllegalStateException("readiness failed")
            val work = NativeSchematicMutation.paste("builder", protectionSnapshot(1, nativeState),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false),
                Consumer {}, BooleanSupplier { throw problem }, 5)
            SchematicMainThread(scheduler, ArcSchematicCacheSettings.defaults()).use { main ->
                val completion = main.enqueueMutation(work, work.completion)
                scheduler.tick()
                val failure = shouldThrow<CompletionException> { completion.join() }.cause as ArcSchematicException
                failure.reason shouldBe ArcSchematicFailure.IO_ERROR
                failure.cause shouldBe problem
                main.queuedMutations() shouldBe 0
                verify(exactly = 0) { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) }
            }
        }
    }

    test("cancellation and provider close release paused work without writing") {
        nativeScenario { _, world, nativeWorld, nativeState ->
            val scheduler = TestTaskScheduler()
            var observations = 0
            fun paused(x: Int) = NativeSchematicMutation.paste("builder", protectionSnapshot(1, nativeState),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(x, 64, 1)), ArcSchematicPasteOptions(false, false, false),
                Consumer { observations++ }, BooleanSupplier { false }, 5)
            val cancelled = paused(1)
            val closed = paused(5)
            SchematicMainThread(scheduler, ArcSchematicCacheSettings.defaults()).use { main ->
                val cancellation = main.enqueueMutation(cancelled, cancelled.completion)
                val closure = main.enqueueMutation(closed, closed.completion)
                scheduler.tick()
                main.queuedMutations() shouldBe 2
                cancelled.cancel() shouldBe true
                cancellation.join().let { it.changed shouldBe 0; it.cancelled shouldBe true }
                main.queuedMutations() shouldBe 1
                main.close()
                (shouldThrow<CompletionException> { closure.join() }.cause as ArcSchematicException).reason shouldBe ArcSchematicFailure.CLOSED
                main.queuedMutations() shouldBe 0
                scheduler.tick(3)
                observations shouldBe 0
                verify(exactly = 0) { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) }
            }
        }
    }

    test("accepted write observations use world coordinates and exclude no-ops, denied and unperformed cancelled writes") {
        nativeScenario { paper, world, nativeWorld, nativeState ->
            val calls = mutableListOf<BlockVector3>()
            every { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) } answers {
                firstArg<BlockVector3>().let { calls += it; it.x() == 1 }
            }
            val plugin = paper.createSimplePlugin("ObservedProtection")
            paper.server.servicesManager.register(ArcBlockProtectionService::class.java,
                ArcBlockProtectionService { id, x, _, _ -> id == world.uid && x == 3 }, plugin, ServicePriority.Normal)
            val accepted = mutableListOf<ArcSchematicBlock>()
            val observer = Consumer<ArcSchematicBlock> { cell ->
                Bukkit.isPrimaryThread() shouldBe true
                calls.contains(BlockVector3.at(cell.position.x, cell.position.y, cell.position.z)) shouldBe true
                accepted += cell
            }
            val snapshot = protectionSnapshot(3, nativeState)
            val work = NativeSchematicMutation.paste("builder", snapshot,
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false), observer)
            val denied = shouldThrow<ArcSchematicException> { work.step(4) }
            denied.reason shouldBe ArcSchematicFailure.PROTECTED_AREA
            work.fail(denied)
            accepted shouldBe listOf(ArcSchematicBlock(ArcSchematicPoint(1, 64, 1), "minecraft:stone", false, false))
            calls shouldBe listOf(BlockVector3.at(1, 64, 1), BlockVector3.at(2, 64, 1))

            every { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) } answers {
                calls += firstArg<BlockVector3>(); true
            }
            val cancelled = NativeSchematicMutation.paste("builder", snapshot,
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(5, 64, 1)), ArcSchematicPasteOptions(false, false, false), observer)
            cancelled.step(2) shouldBe 2
            cancelled.cancel() shouldBe true
            cancelled.step(10) shouldBe 0
            cancelled.completion.join().let { it.changed shouldBe 1; it.cancelled shouldBe true }
            accepted.map { it.position } shouldBe listOf(ArcSchematicPoint(1, 64, 1), ArcSchematicPoint(5, 64, 1))
            calls.size shouldBe 3
        }
    }

    test("a failed cosmetic observer is disabled without failing or retrying accepted writes") {
        nativeScenario { _, world, nativeWorld, nativeState ->
            var observations = 0
            val work = NativeSchematicMutation.paste("builder", protectionSnapshot(3, nativeState),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false),
                Consumer { observations++; throw IllegalStateException("cosmetic observer failed") })
            work.step(2) shouldBe 2
            work.step(10) shouldBe 2
            work.completion.join().let { it.changed shouldBe 3; it.skipped shouldBe 0; it.cancelled shouldBe false }
            observations shouldBe 1
            verify(exactly = 3) { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) }
        }
    }

    test("observer cancellation follows the completed native batch and prevents later writes") {
        nativeScenario { _, world, nativeWorld, nativeState ->
            val accepted = mutableListOf<ArcSchematicBlock>()
            lateinit var work: NativeSchematicMutation
            work = NativeSchematicMutation.paste("builder", protectionSnapshot(3, nativeState),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false),
                Consumer { accepted += it; work.cancel() })
            work.step(3) shouldBe 3 // One chunk check and two accepted writes before observing this batch.
            work.completion.join().let { it.changed shouldBe 2; it.cancelled shouldBe true }
            accepted.map { it.position } shouldBe listOf(ArcSchematicPoint(1, 64, 1), ArcSchematicPoint(2, 64, 1))
            work.step(10) shouldBe 0
            verify(exactly = 2) { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) }
        }
    }

    for (vertical in listOf(false, true)) test("a matching pair writes primary first without yielding with one block left vertical=$vertical") {
        nativeScenario { _, world, nativeWorld, nativeState ->
            val written = mutableListOf<BlockVector3>()
            val accepted = mutableListOf<BlockVector3>()
            every { nativeWorld.setBlock(any(), any<BaseBlock>(), any<SideEffectSet>()) } answers { written += firstArg<BlockVector3>(); true }
            val work = NativeSchematicMutation.paste("builder", pairSnapshot(nativeState, vertical),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false),
                Consumer { accepted += BlockVector3.at(it.position.x, it.position.y, it.position.z) })
            work.step(2) shouldBe 3 // One chunk check, then both halves even though only one unit remains.
            written shouldBe if (vertical) listOf(BlockVector3.at(1, 64, 1), BlockVector3.at(1, 65, 1))
                else listOf(BlockVector3.at(2, 64, 1), BlockVector3.at(1, 64, 1))
            work.step(1) shouldBe 1 // Bounded bookkeeping visit, without writing the companion again.
            work.completion.join().let { it.changed shouldBe 2; it.skipped shouldBe 0 }
            written.size shouldBe 2
            accepted shouldBe written
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
