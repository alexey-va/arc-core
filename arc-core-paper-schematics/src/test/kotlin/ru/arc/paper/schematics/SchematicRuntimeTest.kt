package ru.arc.paper.schematics

import com.sk89q.worldedit.world.block.BlockState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.enginehub.linbus.tree.LinCompoundTag
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcSchematicCacheSettings
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import ru.arc.paper.api.ArcSchematicIds
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicTransform
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory

class SchematicRuntimeTest : FreeSpec({
    "IDs and lazy root resolution preserve legacy files while rejecting traversal and descendant links" {
        ArcSchematicIds.isValid("civs/house") shouldBe true
        ArcSchematicIds.isValid("nested/legacy.schematic") shouldBe true
        listOf("", "../house", "/house", "civs//house", "civs/./house", "civs\\house", "house\n").forEach { ArcSchematicIds.isValid(it) shouldBe false }
        val root = createTempDirectory("schematic-paths-")
        val outside = createTempDirectory("schematic-outside-")
        try {
            Files.createDirectories(root.resolve("civs"))
            Files.write(root.resolve("civs/house.schem"), schematicNbt())
            val files = SchematicFiles(settings())
            files.resolve(root, "civs/house").path shouldBe root.resolve("civs/house.schem").toRealPath()
            Files.createSymbolicLink(outside.resolve("root-link"), root)
            files.resolve(outside.resolve("root-link"), "civs/house").path shouldBe root.resolve("civs/house.schem").toRealPath()
            Files.createSymbolicLink(root.resolve("escape"), outside)
            shouldThrow<ArcSchematicException> { files.resolve(root, "escape/house") }.reason shouldBe ArcSchematicFailure.INVALID_ID
            Files.write(root.resolve("civs/house.schematic"), schematicNbt())
            shouldThrow<ArcSchematicException> { files.resolve(root, "civs/house") }.reason shouldBe ArcSchematicFailure.INVALID_ID
            files.resolve(root, "civs/house.schematic").path.fileName.toString() shouldBe "house.schematic"
        } finally { root.toFile().deleteRecursively(); outside.toFile().deleteRecursively() }
    }

    "streaming preflight rejects inflated bytes, declared arrays and nested dimension shadowing before decode" {
        NbtPreflight(4_096, 10).check(schematicNbt())
        shouldThrow<ArcSchematicException> { NbtPreflight(4_096, 10).check(schematicNbt(width = 11)) }.reason shouldBe ArcSchematicFailure.LIMIT_EXCEEDED
        val shadowed = gzipNbt {
            writeByte(10); writeUTF("")
            writeByte(10); writeUTF("Schematic")
            dimensions(65_535, 65_535, 65_535)
            writeByte(0)
            dimensions(1, 1, 1)
            writeByte(0)
        }
        shouldThrow<ArcSchematicException> { NbtPreflight(4_096, 10).check(shadowed) }.reason shouldBe ArcSchematicFailure.LIMIT_EXCEEDED
        val declared = gzipNbt {
            writeByte(10); writeUTF("Schematic"); dimensions(1, 1, 1)
            writeByte(7); writeUTF("Blocks"); writeInt(Int.MAX_VALUE); writeByte(0)
        }
        shouldThrow<ArcSchematicException> { NbtPreflight(4_096, 10).check(declared) }.reason shouldBe ArcSchematicFailure.LIMIT_EXCEEDED
        val inflated = gzipNbt {
            writeByte(10); writeUTF("Schematic"); dimensions(1, 1, 1)
            repeat(2_000) { writeByte(1); writeUTF("Padding"); writeByte(0) }
            writeByte(0)
        }
        shouldThrow<ArcSchematicException> { NbtPreflight(4_096, 10).check(inflated) }.reason shouldBe ArcSchematicFailure.LIMIT_EXCEEDED
        val amplified = gzipNbt {
            writeByte(10); writeUTF("Schematic"); dimensions(1, 1, 1)
            writeByte(9); writeUTF("Metadata"); writeByte(10); writeInt(128)
            repeat(128) { writeByte(0) }
            writeByte(0)
        }
        // Far below the byte cap, but empty compounds would amplify object allocation.
        shouldThrow<ArcSchematicException> { NbtPreflight(4_096, 10).check(amplified) }.reason shouldBe ArcSchematicFailure.LIMIT_EXCEEDED
        val unicodePayload = gzipNbt {
            writeByte(10); writeUTF("Schematic"); dimensions(1, 1, 1)
            writeByte(8); writeUTF("Name")
            val utf8 = "House \uD83C\uDFE0".toByteArray(Charsets.UTF_8)
            writeShort(utf8.size); write(utf8)
            writeByte(0)
        }
        NbtPreflight(4_096, 10).check(unicodePayload)
    }

    "snapshot retains air, barriers, exact historical digest and immutable indexed access" {
        val native = mockk<BlockState>()
        val snapshot = SchematicSnapshot(
            Any(), ArcSchematicPoint(10, 20, 30), ArcSchematicPoint(11, 20, 31), ArcSchematicPoint(11, 20, 30),
            "a".repeat(64), false, 0,
            listOf(
                SchematicPaletteEntry("minecraft:stone", false, false, native),
                SchematicPaletteEntry("minecraft:air", true, false, native),
                SchematicPaletteEntry("minecraft:barrier", false, true, native),
            ),
            intArrayOf(0, 1, 2, 0), mapOf(0 to LinCompoundTag.builder().putString("id", "minecraft:chest").putString("CustomName", "retained").build()),
        )
        snapshot.volume shouldBe 4
        snapshot.nonBarrierCount shouldBe 3
        snapshot.contentSha256 shouldBe sha("2:1:2\n0:0:0:minecraft:stone\n1:0:0:minecraft:barrier\n1:0:1:minecraft:stone\n".toByteArray())
        (snapshot.retainedBytes > 4L * 4) shouldBe true
        shouldThrow<UnsupportedOperationException> { (snapshot.blocks() as MutableList).clear() }
        val turned = snapshot.transformed(ArcSchematicTransform(1, true), snapshot.palette)
        turned.origin shouldBe ArcSchematicPoint(0, 0, 0)
        turned.blockAt(0, 0, 1).blockData shouldBe "minecraft:stone"
        turned.blockAt(-1, 0, 0).blockData shouldBe "minecraft:stone"
        snapshot.blockAt(10, 20, 31).isAir shouldBe true
        turned.nbt(turned.index(0, 0, 1)) shouldBe snapshot.nbt(0)
    }

    "one tick pump shares palette and mutation budgets across requests" {
        val scheduler = TestTaskScheduler()
        SchematicMainThread(scheduler, settings().copy(palettePerTick = 2, blocksPerTick = 3)).use { main ->
            val converted = AtomicInteger()
            val a = main.palette(listOf(1, 2, 3)) { converted.incrementAndGet(); it }
            val b = main.palette(listOf(4, 5, 6)) { converted.incrementAndGet(); it }
            val work = AtomicInteger()
            repeat(2) {
                main.enqueueMutation(object : SchematicMainThread.Work {
                    var left = 4
                    override val done get() = left == 0
                    override fun step(budget: Int): Int = minOf(left, budget).also { left -= it; work.addAndGet(it) }
                    override fun fail(problem: Throwable) { throw problem }
                })
            }
            scheduler.tick()
            converted.get() shouldBe 2
            work.get() shouldBe 3
            a.isDone shouldBe false
            b.isDone shouldBe false
            scheduler.tick(2)
            a.join() shouldBe listOf(1, 2, 3)
            b.join() shouldBe listOf(4, 5, 6)
            work.get() shouldBe 8
        }
        scheduler.timerCount() shouldBe 0
    }

    "a failed partial batch cannot spend the remaining tick budget a second time" {
        val scheduler = TestTaskScheduler()
        SchematicMainThread(scheduler, settings().copy(palettePerTick = 2)).use { main ->
            val attempts = AtomicInteger()
            val failed = main.palette(listOf(1, 2)) {
                attempts.incrementAndGet()
                check(it != 2) { "Conversion failed after partial work" }
                it
            }
            val next = main.palette(listOf(3, 4)) { attempts.incrementAndGet(); it }
            scheduler.tick()
            failed.isCompletedExceptionally shouldBe true
            next.isDone shouldBe false
            attempts.get() shouldBe 2
            scheduler.tick()
            next.join() shouldBe listOf(3, 4)
            attempts.get() shouldBe 4
        }
    }

    "cancelled native work releases its queue reference before caller completion without a tick" {
        val scheduler = TestTaskScheduler()
        SchematicMainThread(scheduler, settings()).use { main ->
            repeat(32) {
                val work = NativeSchematicMutation.repair(java.util.UUID.randomUUID(), emptyList())
                val completion = main.enqueueMutation(work, work.completion)
                main.queuedMutations() shouldBe 1
                completion.whenComplete { _, _ -> main.queuedMutations() shouldBe 0 }
                work.cancel() shouldBe true
                completion.join().cancelled shouldBe true
                main.queuedMutations() shouldBe 0
            }
            val alreadyCancelled = NativeSchematicMutation.repair(java.util.UUID.randomUUID(), emptyList())
            alreadyCancelled.cancel()
            main.enqueueMutation(alreadyCancelled, alreadyCancelled.completion).join().cancelled shouldBe true
            main.queuedMutations() shouldBe 0
        }
    }

    "loading is lazy, single flight and transient decode permits cover the main-thread palette wait" {
        fixture(settings().copy(workerThreads = 1)) { root, scheduler, codec, service ->
            val library = service.open("one", root)
            codec.decodes.get() shouldBe 0
            service.diagnostics().entries shouldBe 0
            val one = library.load("one")
            val duplicate = library.load("one.schem")
            val two = library.load("two")
            awaitCondition { codec.decodes.get() == 1 && service.diagnostics().pendingRequests == 3 }
            codec.threads.single().startsWith("arc-schematic-") shouldBe true
            one.isDone shouldBe false
            codec.decodes.get() shouldBe 1
            duplicate.cancel(false)
            pumpUntil(scheduler) { one.isDone && two.isDone }
            one.join().fileSha256 shouldBe sha(Files.readAllBytes(root.resolve("one.schem")))
            two.join().volume shouldBe 1
            codec.decodes.get() shouldBe 2
            val hit = library.load("one")
            pumpUntil(scheduler) { hit.isDone }
            hit.join().fileSha256 shouldBe one.join().fileSha256
            codec.decodes.get() shouldBe 2
            library.close()
            service.diagnostics().activeLibraries shouldBe 0
        }
    }

    "fresh fingerprints and invalidation never mix cached content with changed file bytes" {
        fixture(settings()) { root, scheduler, codec, service ->
            val library = service.open("owner", root)
            val initial = library.load("one")
            pumpUntil(scheduler) { initial.isDone }
            val oldSha = initial.join().fileSha256
            Files.write(root.resolve("one.schem"), schematicNbt(variant = 7))
            val changed = library.loadVerified("one", oldSha)
            pumpUntil(scheduler) { changed.isDone }
            (shouldThrow<CompletionException> { changed.join() }.cause as ArcSchematicException).reason shouldBe ArcSchematicFailure.HASH_MISMATCH
            codec.decodes.get() shouldBe 2
            val verified = library.loadVerified("one", sha(Files.readAllBytes(root.resolve("one.schem"))))
            pumpUntil(scheduler) { verified.isDone }
            verified.join().fileSha256 shouldBe sha(Files.readAllBytes(root.resolve("one.schem")))
            codec.decodes.get() shouldBe 2
            Files.delete(root.resolve("one.schem"))
            library.invalidate("one").get(5, TimeUnit.SECONDS) shouldBe true
            service.diagnostics().entries shouldBe 0
        }
    }

    "verified acquisition does not join older decoded bytes even when file metadata is unchanged" {
        fixture(settings()) { root, scheduler, codec, service ->
            val path = root.resolve("one.schem")
            val initialBytes = Files.readAllBytes(path)
            val modified = Files.getLastModifiedTime(path)
            val library = service.open("fresh", root)
            val previous = library.load("one")
            awaitCondition { codec.decodes.get() == 1 }
            val replacement = schematicNbt(variant = 7)
            replacement.size shouldBe initialBytes.size
            Files.write(path, replacement)
            Files.setLastModifiedTime(path, modified)
            val checked = library.loadVerified("one", sha(initialBytes))
            awaitCondition { codec.decodes.get() == 2 }
            pumpUntil(scheduler) { previous.isDone && checked.isDone }
            previous.join().fileSha256 shouldBe sha(initialBytes)
            (shouldThrow<CompletionException> { checked.join() }.cause as ArcSchematicException).reason shouldBe ArcSchematicFailure.HASH_MISMATCH
        }
    }

    "transforms share the same end-to-end heavy pipeline bound as decoding" {
        fixture(settings().copy(workerThreads = 1)) { root, scheduler, _, service ->
            val library = service.open("transform", root)
            val loaded = library.load("one")
            pumpUntil(scheduler) { loaded.isDone }
            val snapshot = loaded.join()
            val futures = (0..3).map { library.transform(snapshot, ArcSchematicTransform(it, false)) }
            awaitCondition { service.pipelineCounts() == (1 to 3) }
            futures.none { it.isDone } shouldBe true
            pumpUntil(scheduler) { futures.all { it.isDone } }
            futures.forEach { it.join().nonBarrierCount shouldBe 1 }
            service.pipelineCounts() shouldBe (0 to 0)
        }
    }

    "cache weight and real expiry are global across roots and release entries without another load" {
        val now = AtomicLong()
        fixture(settings().copy(maximumEntries = 1, ttlMillis = 1_000), now) { root, scheduler, _, service ->
            val a = service.open("a", root)
            val b = service.open("b", root)
            val first = a.load("one")
            pumpUntil(scheduler) { first.isDone }
            val next = b.load("two")
            pumpUntil(scheduler) { next.isDone }
            service.diagnostics().entries shouldBe 1
            (service.diagnostics().cachedBytes > 0) shouldBe true
            now.set(TimeUnit.SECONDS.toNanos(2))
            scheduler.tick(20)
            service.diagnostics().entries shouldBe 0
            service.diagnostics().cachedBytes shouldBe 0
            a.close(); b.close()
        }
        fixture(settings().copy(maximumCachedBytes = 1)) { root, scheduler, _, service ->
            val library = service.open("no-retention", root)
            val result = library.load("one")
            pumpUntil(scheduler) { result.isDone }
            result.join().volume shouldBe 1
            service.diagnostics().entries shouldBe 0
        }
    }

    "bounded pending admission, lease close and provider close terminate waiting work without late retention" {
        fixture(settings().copy(maximumPendingRequests = 1)) { root, scheduler, codec, service ->
            val library = service.open("owner", root)
            val pending = library.load("one")
            awaitCondition { codec.decodes.get() == 1 }
            val rejected = library.load("two")
            (shouldThrow<CompletionException> { rejected.join() }.cause as ArcSchematicException).reason shouldBe ArcSchematicFailure.BUSY
            library.close()
            (shouldThrow<CompletionException> { pending.join() }.cause as ArcSchematicException).reason shouldBe ArcSchematicFailure.CLOSED
            service.close()
            scheduler.tick(5)
            service.diagnostics().closed shouldBe true
            service.diagnostics().entries shouldBe 0
            service.diagnostics().pendingRequests shouldBe 0
            scheduler.timerCount() shouldBe 0
        }
    }
})

private fun settings() = ArcSchematicCacheSettings.defaults().copy(maximumFileBytes = 4_096, maximumInflatedBytes = 8_192, maximumVolume = 32)

private fun fixture(
    settings: ArcSchematicCacheSettings,
    now: AtomicLong = AtomicLong(),
    body: (java.nio.file.Path, TestTaskScheduler, FakeCodec, SharedSchematicService) -> Unit,
) {
    val root = createTempDirectory("schematic-runtime-")
    val scheduler = TestTaskScheduler()
    val codec = FakeCodec()
    try {
        Files.write(root.resolve("one.schem"), schematicNbt())
        Files.write(root.resolve("two.schem"), schematicNbt(variant = 2))
        SharedSchematicService(settings, scheduler, codec, now::get).use { service -> body(root, scheduler, codec, service) }
    } finally { scheduler.close(); root.toFile().deleteRecursively() }
}

private class FakeCodec : SchematicCodec {
    val decodes = AtomicInteger()
    val threads = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val state = mockk<BlockState>()
    override fun decode(bytes: SchematicBytes): DecodedSchematic {
        threads += Thread.currentThread().name
        decodes.incrementAndGet()
        return DecodedSchematic(ArcSchematicPoint(0, 0, 0), ArcSchematicPoint(0, 0, 0), ArcSchematicPoint(0, 0, 0), listOf(state), intArrayOf(0), emptyMap(), false, 0)
    }
    override fun canonicalize(state: BlockState) = SchematicPaletteEntry("minecraft:stone", false, false, state)
    override fun transform(entry: SchematicPaletteEntry, transform: ArcSchematicTransform) = entry
}

private fun pumpUntil(scheduler: TestTaskScheduler, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Schematic future did not finish" }
        scheduler.tick()
        Thread.sleep(1)
    }
}

private fun awaitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition()) { check(System.nanoTime() < deadline); Thread.sleep(1) }
}

private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
private fun schematicNbt(width: Int = 1, variant: Int = 1) = gzipNbt {
    writeByte(10); writeUTF("Schematic"); dimensions(width, 1, 1)
    writeByte(1); writeUTF("Variant"); writeByte(variant)
    writeByte(0)
}
private fun DataOutputStream.dimensions(x: Int, y: Int, z: Int) {
    listOf("Width" to x, "Height" to y, "Length" to z).forEach { (name, size) -> writeByte(2); writeUTF(name); writeShort(size) }
}
private fun gzipNbt(body: DataOutputStream.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(GZIPOutputStream(bytes)).use { it.body() }
    return bytes.toByteArray()
}
