package ru.arc.paper.schematics

import ru.arc.paper.api.ArcSchematicCacheSettings
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import ru.arc.paper.api.ArcSchematicIds
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPInputStream

internal fun failure(reason: ArcSchematicFailure, message: String): Nothing = throw ArcSchematicException(reason, message)

internal data class FileIdentity(val size: Long, val modified: FileTime, val key: Any?)
internal data class SchematicFile(val path: Path, val identity: FileIdentity)
internal data class SchematicBytes(val file: SchematicFile, val bytes: ByteArray, val sha256: String)

/** Every method is blocking and is invoked only by the provider's bounded worker dispatcher. */
internal class SchematicFiles(private val settings: ArcSchematicCacheSettings) {
    fun resolve(root: Path, id: String): SchematicFile {
        val candidates = candidates(root, id)
        val present = candidates.filter { Files.exists(it, NOFOLLOW_LINKS) }
        if (present.isEmpty()) failure(ArcSchematicFailure.NOT_FOUND, "Schematic does not exist: $id")
        if (present.size != 1) failure(ArcSchematicFailure.INVALID_ID, "Ambiguous schematic ID; specify its exact suffix")
        return inspect(present.single())
    }

    fun candidates(root: Path, id: String): List<Path> {
        if (!ArcSchematicIds.isValid(id)) failure(ArcSchematicFailure.INVALID_ID, "Invalid schematic ID")
        val realRoot = try { root.toRealPath() } catch (_: java.nio.file.NoSuchFileException) {
            failure(ArcSchematicFailure.NOT_FOUND, "Schematic root does not exist")
        }
        if (!Files.isDirectory(realRoot, NOFOLLOW_LINKS)) failure(ArcSchematicFailure.INVALID_ID, "Schematic root is not a directory")
        val explicit = id.endsWith(".schem") || id.endsWith(".schematic")
        val candidates = if (explicit) listOf(realRoot.resolve(id)) else listOf(realRoot.resolve("$id.schem"), realRoot.resolve("$id.schematic"))
        candidates.forEach { ensureSafe(realRoot, it) }
        return candidates
    }

    fun read(file: SchematicFile): SchematicBytes {
        if (file.path.toRealPath() != file.path) failure(ArcSchematicFailure.INVALID_ID, "Schematic path changed to a symbolic link")
        val output = ByteArrayOutputStream(minOf(file.identity.size.toInt(), 65_536))
        FileChannel.open(file.path, READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(8_192)
            while (true) {
                if (Thread.currentThread().isInterrupted) failure(ArcSchematicFailure.CLOSED, "Schematic load interrupted")
                val count = channel.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > settings.maximumFileBytes) failure(ArcSchematicFailure.LIMIT_EXCEEDED, "Schematic file is too large")
                output.write(buffer.array(), 0, count)
                buffer.clear()
            }
        }
        val after = inspect(file.path)
        if (file.path.toRealPath() != file.path) failure(ArcSchematicFailure.INVALID_ID, "Schematic path changed to a symbolic link")
        if (after.identity != file.identity || output.size().toLong() != file.identity.size) {
            failure(ArcSchematicFailure.FILE_CHANGED, "Schematic changed during acquisition; retry")
        }
        val bytes = output.toByteArray()
        NbtPreflight(settings.maximumInflatedBytes, settings.maximumVolume).check(bytes)
        return SchematicBytes(after, bytes, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
    }

    private fun inspect(path: Path): SchematicFile {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.isSymbolicLink) failure(ArcSchematicFailure.INVALID_ID, "Schematic must be a regular file")
        if (attributes.size() !in 1..settings.maximumFileBytes.toLong()) failure(ArcSchematicFailure.LIMIT_EXCEEDED, "Schematic file size is outside its bound")
        return SchematicFile(path, FileIdentity(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey()))
    }

    private fun ensureSafe(root: Path, path: Path) {
        if (!path.normalize().startsWith(root)) failure(ArcSchematicFailure.INVALID_ID, "Schematic path escapes its root")
        var cursor = root
        for (part in root.relativize(path)) {
            cursor = cursor.resolve(part)
            if (Files.isSymbolicLink(cursor)) failure(ArcSchematicFailure.INVALID_ID, "Schematic path crosses a symbolic link")
        }
    }
}

/**
 * Streaming, allocation-bounded NBT validation before WorldEdit allocates a clipboard.
 * Array/list declarations are checked before reading/skipping their contents.
 */
internal class NbtPreflight(private val maximumBytes: Int, private val maximumVolume: Int) {
    private var nestedDimensions: IntArray? = null
    private var remainingNodes = 0L

    fun check(compressed: ByteArray) {
        nestedDimensions = null
        // Empty list/compound elements cost little on disk but become Java
        // objects in WorldEdit. Bound their aggregate overhead before decoding.
        remainingNodes = maxOf(1L, maximumBytes.toLong() / 64L)
        try {
            DataInputStream(LimitedInput(GZIPInputStream(ByteArrayInputStream(compressed)), maximumBytes.toLong())).use { input ->
                if (input.readUnsignedByte() != 10) failure(ArcSchematicFailure.UNSUPPORTED_FORMAT, "Schematic NBT root is not a compound")
                node()
                skipString(input)
                val dimensions = IntArray(3)
                compound(input, 0, true, dimensions)
                validateDimensions(nestedDimensions ?: dimensions)
                if (input.read() != -1) failure(ArcSchematicFailure.UNSUPPORTED_FORMAT, "Trailing schematic NBT payload")
            }
        } catch (problem: ArcSchematicException) {
            throw problem
        } catch (problem: Exception) {
            throw ArcSchematicException(ArcSchematicFailure.UNSUPPORTED_FORMAT, "Malformed or unsupported schematic NBT").also { it.initCause(problem) }
        }
    }

    private fun compound(input: DataInputStream, depth: Int, schematic: Boolean, dimensions: IntArray) {
        if (depth > 64) failure(ArcSchematicFailure.LIMIT_EXCEEDED, "Schematic NBT nesting exceeds its bound")
        while (true) {
            val tag = input.readUnsignedByte()
            if (tag == 0) return
            node()
            val name = name(input)
            val dimension = if (schematic && tag == 2) when (name) { "Width" -> 0; "Height" -> 1; "Length" -> 2; else -> -1 } else -1
            when {
                dimension >= 0 -> dimensions[dimension] = input.readUnsignedShort()
                tag == 10 && depth == 0 && name == "Schematic" -> {
                    if (nestedDimensions != null) failure(ArcSchematicFailure.UNSUPPORTED_FORMAT, "Duplicate Schematic compound")
                    val nested = IntArray(3)
                    compound(input, depth + 1, true, nested)
                    validateDimensions(nested)
                    nestedDimensions = nested
                }
                tag == 10 -> compound(input, depth + 1, false, dimensions)
                else -> payload(input, tag, depth + 1, dimensions, false)
            }
        }
    }

    private fun validateDimensions(dimensions: IntArray) {
        val volume = dimensions.fold(1L) { value, side -> value * side }
        if (dimensions.any { it <= 0 } || volume > maximumVolume) {
            failure(ArcSchematicFailure.LIMIT_EXCEEDED, "Schematic dimensions exceed the configured volume")
        }
    }

    private fun payload(input: DataInputStream, tag: Int, depth: Int, dimensions: IntArray, countNode: Boolean) {
        if (depth > 64) failure(ArcSchematicFailure.LIMIT_EXCEEDED, "Schematic NBT nesting exceeds its bound")
        if (countNode) node()
        when (tag) {
            1 -> input.readByte()
            2 -> input.readShort()
            3, 5 -> input.readInt()
            4, 6 -> input.readLong()
            7, 11, 12 -> {
                val count = input.readInt()
                val bytes = count.toLong() * when (tag) { 11 -> 4; 12 -> 8; else -> 1 }
                if (count < 0 || bytes > maximumBytes) failure(ArcSchematicFailure.LIMIT_EXCEEDED, "Schematic NBT array exceeds its bound")
                input.skipNBytes(bytes)
            }
            8 -> skipString(input)
            9 -> {
                val element = input.readUnsignedByte()
                val count = input.readInt()
                if (count < 0 || count > remainingNodes || (element == 0 && count != 0)) failure(ArcSchematicFailure.LIMIT_EXCEEDED, "Schematic NBT list exceeds its node bound")
                repeat(count) { payload(input, element, depth + 1, dimensions, true) }
            }
            10 -> compound(input, depth, false, dimensions)
            else -> failure(ArcSchematicFailure.UNSUPPORTED_FORMAT, "Unknown schematic NBT tag")
        }
    }

    private fun node() {
        if (--remainingNodes < 0) failure(ArcSchematicFailure.LIMIT_EXCEEDED, "Schematic NBT exceeds its aggregate node bound")
    }

    private fun skipString(input: DataInputStream) = input.skipNBytes(input.readUnsignedShort().toLong())

    private fun name(input: DataInputStream): String {
        val size = input.readUnsignedShort()
        // Only these short ASCII schema keys affect preflight. Other names and
        // opaque string payloads need no UTF decoding or temporary allocation.
        if (size > 9) { input.skipNBytes(size.toLong()); return "" }
        return String(ByteArray(size).also(input::readFully), Charsets.UTF_8)
    }

    private class LimitedInput(input: InputStream, private val maximum: Long) : FilterInputStream(input) {
        private var read = 0L
        private fun count(amount: Long) {
            if (amount > 0) read += amount
            if (read > maximum) failure(ArcSchematicFailure.LIMIT_EXCEEDED, "Inflated schematic exceeds its byte bound")
        }
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, minOf(length.toLong(), maximum - read + 1).toInt()).also { count(it.toLong()) }
        override fun skip(amount: Long): Long = `in`.skip(minOf(amount, maximum - read + 1)).also(::count)
    }
}
