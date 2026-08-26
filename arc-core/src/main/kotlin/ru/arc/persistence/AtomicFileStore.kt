package ru.arc.persistence

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Bounded, atomic and read-back-verified storage below one trusted root.
 *
 * The relative path may not escape [root]. Existing symbolic links below the
 * root are rejected. Writes force the temporary file, rename in the same
 * directory, force the directory entry, clean the temporary file on every exit
 * path, then decode and validate the committed bytes before returning. The
 * target filesystem must support an atomic replace; the store never silently
 * downgrades its durability contract.
 */
class AtomicFileStore<T>(
    root: Path,
    relativePath: Path,
    private val maxBytes: Long,
    private val encode: (T) -> ByteArray,
    private val decode: (ByteArray) -> T,
    private val validate: (T) -> Unit = {},
) {
    private val root: Path
    val path: Path

    init {
        require(maxBytes in 1..MAX_ALLOWED_BYTES) { "Atomic file size limit must be between 1 byte and 1 GiB" }
        require(!relativePath.isAbsolute) { "Atomic file path must be relative to its trusted root" }
        require(relativePath.nameCount > 0) { "Atomic file path must not be empty" }
        require(relativePath.none { it.toString() == ".." }) { "Atomic file path must not contain parent traversal" }
        val normalizedRoot = root.toAbsolutePath().normalize()
        Files.createDirectories(normalizedRoot)
        this.root = normalizedRoot.toRealPath()
        path = this.root.resolve(relativePath).normalize()
        require(path.startsWith(this.root) && path != this.root) { "Atomic file path escapes its trusted root" }
    }

    @Synchronized
    fun loadOrNull(): T? {
        ensureSafePath(createParents = false)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Atomic file target is not a regular file: $path" }
        val size = Files.size(path)
        require(size in 0..maxBytes) { "Atomic file $path exceeds its configured size limit" }
        val bytes = Files.readAllBytes(path)
        require(bytes.size.toLong() == size) { "Atomic file $path changed while it was being read" }
        return decode(bytes).also(validate)
    }

    fun loadOrDefault(defaultValue: () -> T): T = loadOrNull() ?: defaultValue().also(validate)

    /** Commits [value] and returns the decoded, validated readback. */
    @Synchronized
    fun write(value: T): T {
        validate(value)
        ensureSafePath(createParents = true)
        val bytes = encode(value)
        require(bytes.size.toLong() <= maxBytes) { "Atomic file $path exceeds its configured size limit" }
        decode(bytes).also(validate)
        val parent = requireNotNull(path.parent)
        val temporary = Files.createTempFile(parent, ".${path.fileName}-", ".tmp")
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            forceDirectory(parent)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return requireNotNull(loadOrNull()) { "Atomic file disappeared after commit: $path" }
    }

    @Synchronized
    fun deleteIfExists(): Boolean {
        ensureSafePath(createParents = false)
        val deleted = Files.deleteIfExists(path)
        if (deleted) forceDirectory(requireNotNull(path.parent))
        return deleted
    }

    private fun ensureSafePath(createParents: Boolean) {
        val parent = requireNotNull(path.parent)
        if (createParents) Files.createDirectories(parent)
        var cursor = root
        root.relativize(parent).forEach { segment ->
            cursor = cursor.resolve(segment)
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(cursor)) { "Atomic file path crosses a symbolic link: $cursor" }
                require(Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) { "Atomic file parent is not a directory: $cursor" }
            }
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(path)) { "Atomic file target must not be a symbolic link: $path" }
        }
    }

    private fun forceDirectory(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }

    companion object {
        private const val MAX_ALLOWED_BYTES = 1024L * 1024L * 1024L
    }
}
