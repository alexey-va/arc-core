package ru.arc.paper.player

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
import org.bukkit.Server
import org.bukkit.UnsafeValues
import org.bukkit.World
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PaperOfflinePlayerDataTest : FreeSpec({
    val roots = mutableListOf<Path>()
    fun directory(): Path = Files.createTempDirectory("offline-player-data-test").also(roots::add)
    afterSpec { roots.forEach { it.toFile().deleteRecursively() } }

    "native capture uses the authoritative primary world and performs no directory IO" {
        MockBukkitTestRuntime.open().use {
            val absentWorld = directory().resolve("not-created-world")
            val server = mockk<Server>()
            val unsafe = mockk<UnsafeValues>()
            val world = mockk<World>()
            every { server.isPrimaryThread } returns true
            every { server.unsafe } returns unsafe
            every { unsafe.mainLevelName } returns "authoritative-primary"
            every { server.getWorld("authoritative-primary") } returns world
            every { world.worldFolder } returns absentWorld.toFile()
            val source = PaperOfflinePlayerData.capture(server)
            source.playerDataDirectory shouldBe absentWorld.resolve("playerdata")
            Files.exists(absentWorld) shouldBe false
            source.listPlayerIds() shouldBe emptySet()
        }
    }

    "large directory inventory retains every valid UUID even when its data is corrupt" {
        val directory = directory()
        val ids = (0 until 4_096).map { UUID(0, it.toLong()) }.toSet()
        ids.forEach { Files.write(directory.resolve("$it.dat"), byteArrayOf(1, 2, 3)) }
        Files.write(directory.resolve("invalid.dat"), byteArrayOf())
        Files.write(directory.resolve("${UUID.randomUUID()}.dat_old"), byteArrayOf())
        Files.write(directory.resolve("${UUID.randomUUID()}.DAT"), byteArrayOf())
        val validId = ids.first()
        writeName(directory, validId, "KnownName")
        val source = PaperOfflinePlayerData(directory)
        source.listPlayerIds() shouldBe ids
        val result = source.names(listOf(validId, ids.last()))
        result.names shouldBe mapOf(validId to "KnownName", ids.last() to null)
        result.failures.keys shouldBe setOf(ids.last())
        source.listPlayerIds() shouldBe ids
    }

    "missing names and files are null and duplicate requested IDs are read once" {
        val directory = directory()
        val named = UUID.randomUUID()
        val missingName = UUID.randomUUID()
        val missingFile = UUID.randomUUID()
        writeName(directory, named, "Player_Name")
        BinaryTagIO.writer().write(CompoundBinaryTag.empty(), directory.resolve("$missingName.dat"), BinaryTagIO.Compression.GZIP)
        var reads = 0
        val source = PaperOfflinePlayerData(directory, 1_024, 4_096) { path ->
            reads++
            Files.newInputStream(path)
        }
        val result = source.names(listOf(named, missingName, missingFile, named))
        result.names shouldBe mapOf(named to "Player_Name", missingName to null, missingFile to null)
        result.failures shouldBe emptyMap()
        reads shouldBe 2
    }

    "delayed name IO remains on the caller's worker and never affects inventory enumeration" {
        val directory = directory()
        val id = UUID.randomUUID()
        writeName(directory, id, "DelayedName")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requestingThread = Thread.currentThread()
        val source = PaperOfflinePlayerData(directory, 1_024, 4_096) { path ->
            (Thread.currentThread() !== requestingThread) shouldBe true
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Files.newInputStream(path)
        }
        BoundedAsyncExecutor("offline-player-data-test").use { worker ->
            val pending = worker.submit { source.names(listOf(id)) }
            try {
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                pending.isDone shouldBe false
                source.listPlayerIds() shouldBe setOf(id)
            } finally { release.countDown() }
            pending.get(5, TimeUnit.SECONDS).names shouldBe mapOf(id to "DelayedName")
        }
    }

    "compressed and expanded limits reject oversized files without removing their UUIDs" {
        val directory = directory()
        val oversizedCompressed = UUID.randomUUID()
        val oversizedExpanded = UUID.randomUUID()
        val healthy = UUID.randomUUID()
        Files.write(directory.resolve("$oversizedCompressed.dat"), ByteArray(2_049))
        val large = CompoundBinaryTag.builder().putByteArray("unrelated", ByteArray(16_384)).build()
        BinaryTagIO.writer().write(large, directory.resolve("$oversizedExpanded.dat"), BinaryTagIO.Compression.GZIP)
        writeName(directory, healthy, "Healthy")
        val source = PaperOfflinePlayerData(directory, 2_048, 1_024)
        val ids = setOf(oversizedCompressed, oversizedExpanded, healthy)
        source.listPlayerIds() shouldBe ids
        val result = source.names(ids)
        result.names shouldBe mapOf(oversizedCompressed to null, oversizedExpanded to null, healthy to "Healthy")
        result.failures.keys shouldBe setOf(oversizedCompressed, oversizedExpanded)
        result.failures[oversizedCompressed] shouldBe PaperOfflinePlayerData.ReadFailure.SIZE_LIMIT
    }

    "library depth bound rejects malformed deep NBT while preserving other names" {
        val directory = directory()
        val deepId = UUID.randomUUID()
        val healthy = UUID.randomUUID()
        var deep = CompoundBinaryTag.empty()
        repeat(600) { deep = CompoundBinaryTag.builder().put("nested", deep).build() }
        BinaryTagIO.writer().write(deep, directory.resolve("$deepId.dat"), BinaryTagIO.Compression.GZIP)
        writeName(directory, healthy, "StillReadable")
        val result = PaperOfflinePlayerData(directory).names(listOf(deepId, healthy))
        result.names shouldBe mapOf(deepId to null, healthy to "StillReadable")
        result.failures.keys shouldBe setOf(deepId)
    }
})

private fun writeName(directory: Path, id: UUID, name: String) {
    val tag = CompoundBinaryTag.builder()
        .put("bukkit", CompoundBinaryTag.builder().putString("lastKnownName", name).build())
        .build()
    BinaryTagIO.writer().write(tag, directory.resolve("$id.dat"), BinaryTagIO.Compression.GZIP)
}
