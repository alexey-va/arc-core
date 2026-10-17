package ru.arc.paper.player

import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag
import org.bukkit.Server
import org.jetbrains.annotations.Blocking
import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * Stateless access to the authoritative Paper 1.21.11 primary-world playerdata directory.
 *
 * [capture] only captures the native path on the primary thread. Construction performs no IO;
 * [listPlayerIds] and [names] are blocking worker operations and never call Bukkit. The caller
 * owns its executor, lifecycle and online UUID/name overrides. Missing or unreadable name data
 * never removes a UUID from the directory inventory or the requested-name result.
 *
 * Each name read limits compressed input and decompressed bytes, and uses Adventure's bounded
 * NBT reader (including its fixed depth limit of 512). No native item deserialization occurs.
 */
class PaperOfflinePlayerData internal constructor(
    playerDataDirectory: Path,
    private val maximumCompressedBytes: Int,
    private val maximumNbtBytes: Int,
    private val openInput: (Path) -> InputStream,
) {
    val playerDataDirectory: Path = playerDataDirectory.toAbsolutePath().normalize()

    @JvmOverloads
    constructor(
        playerDataDirectory: Path,
        maximumCompressedBytes: Int = DEFAULT_MAXIMUM_COMPRESSED_BYTES,
        maximumNbtBytes: Int = DEFAULT_MAXIMUM_NBT_BYTES,
    ) : this(playerDataDirectory, maximumCompressedBytes, maximumNbtBytes, { Files.newInputStream(it) })

    init {
        require(maximumCompressedBytes in 1..MAXIMUM_ALLOWED_BYTES)
        require(maximumNbtBytes in 1..MAXIMUM_ALLOWED_BYTES)
    }

    /** Lists every parseable UUID `.dat` filename, independently of that file's contents. */
    @Blocking
    fun listPlayerIds(): Set<UUID> {
        val ids = linkedSetOf<UUID>()
        try {
            Files.newDirectoryStream(playerDataDirectory) { it.fileName.toString().endsWith(".dat") }.use { paths ->
                paths.forEach { path ->
                    runCatching { UUID.fromString(path.fileName.toString().removeSuffix(".dat")) }
                        .getOrNull()?.let(ids::add)
                }
            }
        } catch (_: NoSuchFileException) {
            // A new server may not have saved its first player yet.
        }
        return Collections.unmodifiableSet(ids)
    }

    /**
     * Reads `bukkit.lastKnownName` for exactly the requested IDs. A missing file/name returns
     * null; other failures additionally produce one payload-free diagnostic for that UUID.
     * Directory enumeration errors propagate from [listPlayerIds] rather than hiding population.
     */
    @Blocking
    fun names(requestedIds: Iterable<UUID>): Names {
        val names = linkedMapOf<UUID, String?>()
        val failures = linkedMapOf<UUID, ReadFailure>()
        requestedIds.forEach { id ->
            if (id in names) return@forEach
            names[id] = try {
                readName(playerDataDirectory.resolve("$id.dat"))
            } catch (_: NoSuchFileException) {
                null
            } catch (_: InputLimitExceeded) {
                failures[id] = ReadFailure.SIZE_LIMIT
                null
            } catch (_: Exception) {
                failures[id] = ReadFailure.UNREADABLE_DATA
                null
            }
        }
        return Names(Collections.unmodifiableMap(names), Collections.unmodifiableMap(failures))
    }

    private fun readName(path: Path): String? {
        if (Files.size(path) > maximumCompressedBytes) throw InputLimitExceeded()
        openInput(path).use { raw ->
            GZIPInputStream(LimitedInputStream(raw, maximumCompressedBytes)).use { gzip ->
                DataInputStream(LimitedInputStream(gzip, maximumNbtBytes)).use { input ->
                    val root = BinaryTagIO.reader(maximumNbtBytes.toLong()).read(input as java.io.DataInput)
                    val bukkit = root.get("bukkit") as? CompoundBinaryTag
                    return (bukkit?.get("lastKnownName") as? StringBinaryTag)?.value()
                }
            }
        }
    }

    data class Names(val names: Map<UUID, String?>, val failures: Map<UUID, ReadFailure>)

    enum class ReadFailure { SIZE_LIMIT, UNREADABLE_DATA }

    companion object {
        const val DEFAULT_MAXIMUM_COMPRESSED_BYTES: Int = 16 * 1024 * 1024
        const val DEFAULT_MAXIMUM_NBT_BYTES: Int = 64 * 1024 * 1024
        private const val MAXIMUM_ALLOWED_BYTES: Int = 512 * 1024 * 1024

        /** Captures Paper's primary-world path without listing or opening any file. */
        @JvmStatic
        @Suppress("DEPRECATION")
        fun capture(server: Server): PaperOfflinePlayerData {
            check(server.isPrimaryThread) { "Offline player data path must be captured on the primary thread" }
            val primaryName = server.unsafe.mainLevelName
            val world = requireNotNull(server.getWorld(primaryName)) { "Paper primary world is not loaded" }
            return PaperOfflinePlayerData(world.worldFolder.toPath().resolve("playerdata"))
        }
    }
}

private class InputLimitExceeded : IOException("Offline player data exceeds its configured byte limit")

/** Limits actual stream bytes as well as the NBT reader's allocation accounting. */
private class LimitedInputStream(input: InputStream, private val maximumBytes: Int) : FilterInputStream(input) {
    private var consumed = 0L

    override fun read(): Int = `in`.read().also { if (it >= 0) account(1) }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val boundedLength = minOf(length.toLong(), maximumBytes - consumed + 1).toInt()
        return `in`.read(bytes, offset, boundedLength).also { if (it > 0) account(it.toLong()) }
    }

    override fun skip(count: Long): Long =
        `in`.skip(minOf(count, maximumBytes - consumed + 1)).also(::account)

    private fun account(count: Long) {
        consumed += count
        if (consumed > maximumBytes) throw InputLimitExceeded()
    }
}
