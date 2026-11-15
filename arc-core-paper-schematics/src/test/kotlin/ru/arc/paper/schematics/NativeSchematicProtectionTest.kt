package ru.arc.paper.schematics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import net.kyori.adventure.nbt.CompoundBinaryTag
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
    test("unsupported NBT binding rejects the complete paste before even an earlier ordinary block writes") {
        nativeScenario { _, world, native ->
            val zero = ArcSchematicPoint(0, 0, 0)
            val snapshot = SchematicSnapshot(Any(), zero, ArcSchematicPoint(1, 0, 0), zero, "0".repeat(64), false, 0,
                listOf(SchematicPaletteEntry("minecraft:stone", false, false), SchematicPaletteEntry("minecraft:chest", false, false)),
                intArrayOf(0, 1), mapOf(1 to CompoundBinaryTag.builder().putString("id", "minecraft:chest").build()), 1343)
            every { native.requireBlockEntities() } throws ArcSchematicException(ArcSchematicFailure.UNSUPPORTED_FORMAT, "Missing native signature")
            val work = NativeSchematicMutation.paste("builder", snapshot, ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)),
                ArcSchematicPasteOptions(false, false, false))
            shouldThrow<ArcSchematicException> { work.step(10) }.reason shouldBe ArcSchematicFailure.UNSUPPORTED_FORMAT
            verify(exactly = 1) { native.requireBlockEntities() }
            verify(exactly = 0) { native.write(any(), any(), any(), any()) }
        }
    }

    test("paced paste waits for readiness, skips native no-ops and admits one unit per interval without blocking ordinary paste") {
        nativeScenario { _, world, nativeWorld ->
            val scheduler = TestTaskScheduler()
            var tick = 0
            var ready = false
            var readinessChecks = 0
            val writes = mutableListOf<Pair<Int, ArcSchematicPoint>>()
            every { nativeWorld.write(any(), any(), any(), any()) } answers {
                val position = secondArg<ArcSchematicPoint>()
                writes += tick to position
                position.x != 1
            }
            val accepted = mutableListOf<Pair<Int, ArcSchematicPoint>>()
            var observedBeforeCompletion = true
            lateinit var paced: NativeSchematicMutation
            paced = NativeSchematicMutation.paste("builder", protectionSnapshot(3),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false),
                Consumer {
                    observedBeforeCompletion = observedBeforeCompletion && !paced.completion.isDone
                    accepted += tick to it.position
                }, BooleanSupplier { readinessChecks++; ready }, 5)
            val ordinary = NativeSchematicMutation.paste("ordinary", protectionSnapshot(2),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(9, 64, 1)), ArcSchematicPasteOptions(false, false, false))
            SchematicMainThread(scheduler, ArcSchematicCacheSettings.defaults().copy(blocksPerTick = 32)).use { main ->
                fun advance(ticks: Int = 1) { repeat(ticks) { tick++; scheduler.tick() } }
                main.enqueueMutation(paced, paced.completion)
                main.enqueueMutation(ordinary, ordinary.completion)
                advance()
                readinessChecks shouldBe 1
                accepted shouldBe emptyList()
                ordinary.completion.join().changed shouldBe 2
                writes.map { it.second.x } shouldBe listOf(9, 10)
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
                writes.map { it.first to it.second.x } shouldBe listOf(1 to 9, 1 to 10, 2 to 1, 2 to 2, 8 to 3)
            }
        }
    }

    test("paced paste accepts a native pair as one unit and observes both halves before completion") {
        nativeScenario { _, world, nativeWorld ->
            val scheduler = TestTaskScheduler()
            val target = ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1))
            val options = ArcSchematicPasteOptions(false, false, false)
            val ready = BooleanSupplier { true }
            shouldThrow<IllegalArgumentException> {
                NativeSchematicMutation.paste("builder", pairSnapshot(), target, options, Consumer {}, ready, 0)
            }
            val accepted = mutableListOf<ArcSchematicPoint>()
            var observedBeforeCompletion = true
            lateinit var paced: NativeSchematicMutation
            paced = NativeSchematicMutation.paste("builder", pairSnapshot(), target, options,
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
                verify(exactly = 2) { nativeWorld.write(any(), any(), any(), any()) }
            }
        }
    }

    test("a throwing readiness gate fails before the native unit writes") {
        nativeScenario { _, world, nativeWorld ->
            val scheduler = TestTaskScheduler()
            val problem = IllegalStateException("readiness failed")
            val work = NativeSchematicMutation.paste("builder", protectionSnapshot(1),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false),
                Consumer {}, BooleanSupplier { throw problem }, 5)
            SchematicMainThread(scheduler, ArcSchematicCacheSettings.defaults()).use { main ->
                val completion = main.enqueueMutation(work, work.completion)
                scheduler.tick()
                val failure = shouldThrow<CompletionException> { completion.join() }.cause as ArcSchematicException
                failure.reason shouldBe ArcSchematicFailure.IO_ERROR
                failure.cause shouldBe problem
                main.queuedMutations() shouldBe 0
                verify(exactly = 0) { nativeWorld.write(any(), any(), any(), any()) }
            }
        }
    }

    test("cancellation and provider close release paused work without writing") {
        nativeScenario { _, world, nativeWorld ->
            val scheduler = TestTaskScheduler()
            var observations = 0
            fun paused(x: Int) = NativeSchematicMutation.paste("builder", protectionSnapshot(1),
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
                verify(exactly = 0) { nativeWorld.write(any(), any(), any(), any()) }
            }
        }
    }

    test("accepted write observations use world coordinates and exclude no-ops, denied and unperformed cancelled writes") {
        nativeScenario { paper, world, nativeWorld ->
            val calls = mutableListOf<ArcSchematicPoint>()
            every { nativeWorld.write(any(), any(), any(), any()) } answers {
                secondArg<ArcSchematicPoint>().let { calls += it; it.x == 1 }
            }
            val plugin = paper.createSimplePlugin("ObservedProtection")
            paper.server.servicesManager.register(ArcBlockProtectionService::class.java,
                ArcBlockProtectionService { id, x, _, _ -> id == world.uid && x == 3 }, plugin, ServicePriority.Normal)
            val accepted = mutableListOf<ArcSchematicBlock>()
            val observer = Consumer<ArcSchematicBlock> { cell ->
                Bukkit.isPrimaryThread() shouldBe true
                calls.contains(ArcSchematicPoint(cell.position.x, cell.position.y, cell.position.z)) shouldBe true
                accepted += cell
            }
            val snapshot = protectionSnapshot(3)
            val work = NativeSchematicMutation.paste("builder", snapshot,
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false), observer)
            val denied = shouldThrow<ArcSchematicException> { work.step(4) }
            denied.reason shouldBe ArcSchematicFailure.PROTECTED_AREA
            work.fail(denied)
            accepted shouldBe listOf(ArcSchematicBlock(ArcSchematicPoint(1, 64, 1), "minecraft:stone", false, false))
            calls shouldBe listOf(ArcSchematicPoint(1, 64, 1), ArcSchematicPoint(2, 64, 1))

            every { nativeWorld.write(any(), any(), any(), any()) } answers {
                calls += secondArg<ArcSchematicPoint>(); true
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
        nativeScenario { _, world, nativeWorld ->
            var observations = 0
            val work = NativeSchematicMutation.paste("builder", protectionSnapshot(3),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false),
                Consumer { observations++; throw IllegalStateException("cosmetic observer failed") })
            work.step(2) shouldBe 2
            work.step(10) shouldBe 2
            work.completion.join().let { it.changed shouldBe 3; it.skipped shouldBe 0; it.cancelled shouldBe false }
            observations shouldBe 1
            verify(exactly = 3) { nativeWorld.write(any(), any(), any(), any()) }
        }
    }

    test("observer cancellation follows the completed native batch and prevents later writes") {
        nativeScenario { _, world, nativeWorld ->
            val accepted = mutableListOf<ArcSchematicBlock>()
            lateinit var work: NativeSchematicMutation
            work = NativeSchematicMutation.paste("builder", protectionSnapshot(3),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false),
                Consumer { accepted += it; work.cancel() })
            work.step(3) shouldBe 3 // One chunk check and two accepted writes before observing this batch.
            work.completion.join().let { it.changed shouldBe 2; it.cancelled shouldBe true }
            accepted.map { it.position } shouldBe listOf(ArcSchematicPoint(1, 64, 1), ArcSchematicPoint(2, 64, 1))
            work.step(10) shouldBe 0
            verify(exactly = 2) { nativeWorld.write(any(), any(), any(), any()) }
        }
    }

    for (vertical in listOf(false, true)) test("a matching pair writes primary first without yielding with one block left vertical=$vertical") {
        nativeScenario { _, world, nativeWorld ->
            val written = mutableListOf<ArcSchematicPoint>()
            val accepted = mutableListOf<ArcSchematicPoint>()
            every { nativeWorld.write(any(), any(), any(), any()) } answers { written += secondArg<ArcSchematicPoint>(); true }
            val work = NativeSchematicMutation.paste("builder", pairSnapshot(vertical),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false),
                Consumer { accepted += ArcSchematicPoint(it.position.x, it.position.y, it.position.z) })
            work.step(2) shouldBe 3 // One chunk check, then both halves even though only one unit remains.
            written shouldBe if (vertical) listOf(ArcSchematicPoint(1, 64, 1), ArcSchematicPoint(1, 65, 1))
                else listOf(ArcSchematicPoint(2, 64, 1), ArcSchematicPoint(1, 64, 1))
            work.step(1) shouldBe 1 // Bounded bookkeeping visit, without writing the companion again.
            work.completion.join().let { it.changed shouldBe 2; it.skipped shouldBe 0 }
            written.size shouldBe 2
            accepted shouldBe written
        }
    }

    test("denied companion protection rejects both halves before the primary write") {
        nativeScenario { paper, world, nativeWorld ->
            val plugin = paper.createSimplePlugin("PairProtection")
            paper.server.servicesManager.register(ArcBlockProtectionService::class.java,
                ArcBlockProtectionService { id, x, _, _ -> id == world.uid && x == 1 }, plugin, ServicePriority.Normal)
            val work = NativeSchematicMutation.paste("builder", pairSnapshot(),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false))
            shouldThrow<ArcSchematicException> { work.step(2) }.reason shouldBe ArcSchematicFailure.PROTECTED_AREA
            verify(exactly = 0) { nativeWorld.write(any(), any(), any(), any()) }
        }
    }

    test("a companion chunk unloaded after preflight prevents either half from being written") {
        nativeScenario { _, world, nativeWorld ->
            world.loadChunk(1, 0)
            val work = NativeSchematicMutation.paste("builder", pairSnapshot(),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(15, 64, 1)), ArcSchematicPasteOptions(false, false, false))
            work.step(2) shouldBe 2
            world.unloadChunk(1, 0)
            world.isChunkLoaded(1, 0) shouldBe false
            shouldThrow<ArcSchematicException> { work.step(1) }.reason shouldBe ArcSchematicFailure.WORLD_UNAVAILABLE
            verify(exactly = 0) { nativeWorld.write(any(), any(), any(), any()) }
        }
    }

    test("a cropped half never writes its missing companion outside the supplied snapshot") {
        nativeScenario { _, world, nativeWorld ->
            val crop = pairSnapshot().cropped(ArcSchematicPoint(1, 0, 0), ArcSchematicPoint(1, 0, 0)) {}
            val work = NativeSchematicMutation.paste("builder", crop,
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false))
            work.step(10) shouldBe 2
            work.completion.join().changed shouldBe 1
            verify(exactly = 1) { nativeWorld.write(world, ArcSchematicPoint(2, 64, 1), any(), any()) }
            verify(exactly = 1) { nativeWorld.write(any(), any(), any(), any()) }
        }
    }

    test("pair overshoot exhausts the aggregate tick before a queued follower receives budget") {
        nativeScenario { _, world, nativeWorld ->
            val scheduler = TestTaskScheduler()
            val pair = NativeSchematicMutation.paste("builder", pairSnapshot(),
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
                verify(exactly = 2) { nativeWorld.write(any(), any(), any(), any()) }
                scheduler.tick()
                budgets shouldBe listOf(2)
                pair.completion.join().changed shouldBe 2
            }
        }
    }

    test("a second native write failure retains the existing partial failure contract without rollback") {
        nativeScenario { _, world, nativeWorld ->
            val written = mutableListOf<ArcSchematicPoint>()
            every { nativeWorld.write(any(), any(), any(), any()) } answers {
                written += secondArg<ArcSchematicPoint>()
                if (written.size == 2) throw IllegalStateException("second half failed")
                true
            }
            val work = NativeSchematicMutation.paste("builder", pairSnapshot(),
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false))
            val problem = shouldThrow<IllegalStateException> { work.step(2) }
            work.fail(problem)
            val failure = shouldThrow<CompletionException> { work.completion.join() }.cause as ArcSchematicException
            failure.reason shouldBe ArcSchematicFailure.IO_ERROR
            failure.message shouldBe "Schematic mutation stopped after 1 changes and 0 skips; no rollback was performed"
            work.step(10) shouldBe 0
            written shouldBe listOf(ArcSchematicPoint(2, 64, 1), ArcSchematicPoint(1, 64, 1))
        }
    }

    test("paste rechecks protection between bounded batches and stops before the newly protected block") {
        nativeScenario { paper, world, nativeWorld ->
            val snapshot = protectionSnapshot(3)
            val work = NativeSchematicMutation.paste("builder", snapshot,
                ArcSchematicPasteTarget(world.uid, ArcSchematicPoint(1, 64, 1)), ArcSchematicPasteOptions(false, false, false))
            work.step(2) shouldBe 2 // One loaded chunk check and one block.
            verify(exactly = 1) { nativeWorld.write(any(), any(), any(), any()) }
            val plugin = paper.createSimplePlugin("Protection")
            paper.server.servicesManager.register(ArcBlockProtectionService::class.java,
                ArcBlockProtectionService { id, x, _, _ -> id == world.uid && x == 2 }, plugin, ServicePriority.Normal)
            val failure = shouldThrow<ArcSchematicException> { work.step(4) }
            failure.reason shouldBe ArcSchematicFailure.PROTECTED_AREA
            work.fail(failure)
            (shouldThrow<CompletionException> { work.completion.join() }.cause as ArcSchematicException).reason shouldBe ArcSchematicFailure.PROTECTED_AREA
            work.step(10) shouldBe 0
            verify(exactly = 1) { nativeWorld.write(any(), any(), any(), any()) }
        }
    }

    test("connection repair checks the library owner immediately before native mutation") {
        nativeScenario { paper, world, nativeWorld ->
            val fence = world.getBlockAt(1, 64, 1).apply { type = Material.OAK_FENCE }
            val cell = ArcSchematicBlock(ArcSchematicPoint(1, 64, 1), fence.blockData.asString, false, false)
            val plugin = paper.createSimplePlugin("Protection")
            paper.server.servicesManager.register(ArcBlockProtectionService::class.java, object : ArcBlockProtectionService {
                override fun isProtected(worldId: UUID, x: Int, y: Int, z: Int) = true
                override fun permitsSchematicMutation(owner: String, worldId: UUID, x: Int, y: Int, z: Int) = owner == "city"
            }, plugin, ServicePriority.Normal)
            val denied = NativeSchematicMutation.repair("builder", world.uid, listOf(cell))
            shouldThrow<ArcSchematicException> { denied.step(10) }.reason shouldBe ArcSchematicFailure.PROTECTED_AREA
            verify(exactly = 0) { nativeWorld.repair(any()) }
            val allowed = NativeSchematicMutation.repair("city", world.uid, listOf(cell))
            allowed.step(10)
            allowed.completion.join().changed shouldBe 1
            verify(exactly = 1) { nativeWorld.repair(fence) }
        }
    }
})

private fun pairSnapshot(vertical: Boolean = false) = SchematicSnapshot(
    Any(), ArcSchematicPoint(0, 0, 0), if (vertical) ArcSchematicPoint(0, 1, 0) else ArcSchematicPoint(1, 0, 0),
    ArcSchematicPoint(0, 0, 0), "0".repeat(64), false, 0,
    (if (vertical) listOf("minecraft:oak_door[half=lower]", "minecraft:oak_door[half=upper]")
    else listOf("minecraft:red_bed[facing=west,part=head]", "minecraft:red_bed[facing=west,part=foot]"))
        .map { SchematicPaletteEntry(it, false, false) }, intArrayOf(0, 1), emptyMap(),
)

private fun protectionSnapshot(sizeX: Int) = SchematicSnapshot(
    Any(), ArcSchematicPoint(0, 0, 0), ArcSchematicPoint(sizeX - 1, 0, 0), ArcSchematicPoint(0, 0, 0),
    "0".repeat(64), false, 0, listOf(SchematicPaletteEntry("minecraft:stone", false, false)), IntArray(sizeX), emptyMap(),
)

private fun nativeScenario(block: (MockBukkitTestRuntime, World, PaperSchematicBlocks) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        failOnUnsupportedMockBukkitOperation {
            val world = paper.addSimpleWorld("schematic")
            world.loadChunk(0, 0)
            val native = mockk<PaperSchematicBlocks>(relaxed = true)
            every { native.write(any(), any(), any(), any()) } returns true
            every { native.repair(any()) } returns true
            mockkConstructor(PaperSchematicBlocks::class)
            try {
                every { anyConstructed<PaperSchematicBlocks>().requireRepair() } answers { native.requireRepair() }
                every { anyConstructed<PaperSchematicBlocks>().requireBlockEntities() } answers { native.requireBlockEntities() }
                every { anyConstructed<PaperSchematicBlocks>().write(any(), any(), any(), any()) } answers {
                    native.write(firstArg(), secondArg(), thirdArg(), arg(3))
                }
                every { anyConstructed<PaperSchematicBlocks>().repair(any()) } answers { native.repair(firstArg()) }
                block(paper, world, native)
            } finally { unmockkConstructor(PaperSchematicBlocks::class) }
        }
    }
}
