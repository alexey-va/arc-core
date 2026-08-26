package ru.arc.persistence

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** One committed journal record and its stable storage identifier. */
data class DurableRecord<T : Any>(
    val recordId: String,
    val value: T,
)

/**
 * Durable, bounded, one-file-per-record storage below a trusted root.
 *
 * [commit] does not return until [AtomicFileStore] has atomically replaced the
 * target, forced its directory entry, decoded the committed bytes and validated
 * the readback. [acknowledge] is intentionally idempotent so recovery may repeat
 * it after an unknown shutdown outcome. Record identifiers are filename-safe and
 * never interpreted as paths.
 *
 * Every operation performs blocking filesystem I/O. Paper and Velocity consumers
 * must call it from an owned storage executor, or explicitly freeze the affected
 * state while a required pre-mutation durability barrier is committed.
 */
class DurableRecordJournal<T : Any>(
    root: Path,
    relativeDirectory: Path,
    private val maxRecordBytes: Long,
    private val encode: (T) -> ByteArray,
    private val decode: (ByteArray) -> T,
    private val validate: (T) -> Unit = {},
) {
    private val root: Path
    private val relativeDirectory: Path
    val directory: Path

    init {
        require(maxRecordBytes in 1..MAX_RECORD_BYTES) { "Durable record byte limit must be between 1 and $MAX_RECORD_BYTES" }
        require(!relativeDirectory.isAbsolute) { "Durable journal directory must be relative to its trusted root" }
        require(relativeDirectory.nameCount > 0) { "Durable journal directory must not be empty" }
        require(relativeDirectory.none { it.toString() == ".." }) {
            "Durable journal directory must not contain parent traversal"
        }
        val normalizedRoot = root.toAbsolutePath().normalize()
        Files.createDirectories(normalizedRoot)
        this.root = normalizedRoot.toRealPath()
        this.relativeDirectory = relativeDirectory.normalize()
        directory = this.root.resolve(this.relativeDirectory).normalize()
        require(directory.startsWith(this.root) && directory != this.root) {
            "Durable journal directory escapes its trusted root"
        }
        ensureSafeDirectory()
    }

    /** Commits [value] and returns its decoded, validated durable readback. */
    @Synchronized
    fun commit(recordId: String, value: T): T = store(recordId).write(value)

    @Synchronized
    fun loadOrNull(recordId: String): T? = store(recordId).loadOrNull()

    /** Deletes a committed record. Repeated acknowledgement returns `false`. */
    @Synchronized
    fun acknowledge(recordId: String): Boolean = store(recordId).deleteIfExists()

    /** Loads every committed record in stable identifier order. */
    @Synchronized
    fun loadAll(): List<DurableRecord<T>> {
        ensureSafeDirectory()
        return Files.list(directory).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(RECORD_SUFFIX) }
                .map { path ->
                    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        "Durable journal record is not a regular file: $path"
                    }
                    val recordId = path.fileName.toString().removeSuffix(RECORD_SUFFIX).also(::validateRecordId)
                    DurableRecord(recordId, requireNotNull(store(recordId).loadOrNull()))
                }
                .sorted(compareBy(DurableRecord<T>::recordId))
                .toList()
        }
    }

    private fun store(recordId: String): AtomicFileStore<T> {
        validateRecordId(recordId)
        return AtomicFileStore(
            root = root,
            relativePath = relativeDirectory.resolve("$recordId$RECORD_SUFFIX"),
            maxBytes = maxRecordBytes,
            encode = encode,
            decode = decode,
            validate = validate,
        )
    }

    private fun ensureSafeDirectory() {
        var cursor = root
        relativeDirectory.forEach { segment ->
            cursor = cursor.resolve(segment)
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(cursor)) { "Durable journal directory crosses a symbolic link: $cursor" }
                require(Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    "Durable journal path is not a directory: $cursor"
                }
            } else {
                Files.createDirectory(cursor)
            }
        }
    }

    private fun validateRecordId(recordId: String) {
        require(recordId.matches(RECORD_ID)) {
            "Durable journal record id must be 1..128 filename-safe ASCII characters"
        }
    }

    private companion object {
        const val MAX_RECORD_BYTES = 1024L * 1024L * 1024L
        val RECORD_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        const val RECORD_SUFFIX = ".json"
    }
}
