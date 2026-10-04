package ru.arc.telemetry

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ru.arc.persistence.AtomicFileStore
import ru.arc.persistence.DurableAcknowledgementOutcome
import ru.arc.persistence.DurableRecordJournal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.TreeMap
import java.util.TreeSet

internal data class PlayerTelemetryOutboxRecord(
    val recordId: String,
    val events: List<PlayerTelemetryEvent>,
    val encodedBytes: Long,
) {
    val oldestAt: Long get() = events.minOfOrNull(PlayerTelemetryEvent::occurredAt) ?: Long.MAX_VALUE
}

internal data class PlayerTelemetryOutboxSnapshot(
    val eventCount: Long,
    val byteCount: Long,
    val oldestAt: Long?,
    val coverageFrom: Long,
    val coverageGapFrom: Long?,
    val knownDropCount: Long,
    val corruptRecordCount: Int,
    val uncleanPreviousRun: Boolean,
)

private data class StoredBatchMetadata(
    val eventCount: Long,
    val byteCount: Long,
    val oldestAt: Long,
)

/** Narrow test seam for the one-file-per-batch durable journal. All methods block and belong on the journal executor. */
internal interface PlayerTelemetryOutbox : AutoCloseable {
    fun recover(now: Long): PlayerTelemetryOutboxSnapshot
    fun stats(): PlayerTelemetryOutboxSnapshot
    fun commit(batchId: String, events: List<PlayerTelemetryEvent>): PlayerTelemetryOutboxRecord
    fun next(now: Long): PlayerTelemetryOutboxRecord?
    fun acknowledge(record: PlayerTelemetryOutboxRecord): DurableAcknowledgementOutcome
    fun noteGap(at: Long, droppedEvents: Long): PlayerTelemetryOutboxSnapshot
    fun markCleanShutdown(at: Long)
    fun corruptRecordCount(): Int
    override fun close() = Unit
}

/** Gson codec shared by the local journal and SQL hash check. Attributes are sorted before encoding. */
internal class PlayerTelemetryJsonCodec(private val gson: Gson) {
    private val eventListType = TypeToken.getParameterized(List::class.java, PlayerTelemetryEvent::class.java).type

    fun encodeEvent(event: PlayerTelemetryEvent): ByteArray =
        gson.toJson(event.validatedCopy()).toByteArray(StandardCharsets.UTF_8)

    fun hash(event: PlayerTelemetryEvent): String =
        MessageDigest.getInstance("SHA-256")
            .digest(encodeEvent(event))
            .joinToString("") { "%02x".format(it) }

    fun encodeBatch(events: List<PlayerTelemetryEvent>): ByteArray =
        gson.toJson(events.map(PlayerTelemetryEvent::validatedCopy)).toByteArray(StandardCharsets.UTF_8)

    fun encodeAttributes(event: PlayerTelemetryEvent): String = gson.toJson(event.attributes.toSortedMap())

    fun decodeBatch(bytes: ByteArray): List<PlayerTelemetryEvent> =
        gson.fromJson<List<PlayerTelemetryEvent>>(String(bytes, StandardCharsets.UTF_8), eventListType)
            .also(::validateBatch)
            .map(PlayerTelemetryEvent::validatedCopy)

    fun validateBatch(events: List<PlayerTelemetryEvent>) {
        require(events.isNotEmpty() && events.size <= MAX_BATCH_EVENTS) { "Outbox batch size is invalid" }
        events.forEach(PlayerTelemetryEvent::validateForStorage)
        require(events.map(PlayerTelemetryEvent::eventId).toSet().size == events.size) {
            "Outbox batch contains duplicate event ids"
        }
    }

    companion object {
        const val MAX_BATCH_EVENTS = 2_000
    }
}

internal data class PlayerTelemetryCoverageState(
    val schemaVersion: Int = 1,
    val coverageFrom: Long,
    val currentRunStartedAt: Long,
    val lastCleanShutdownAt: Long? = null,
    val coverageGapFrom: Long? = null,
    val knownDropCount: Long = 0,
    val cleanShutdown: Boolean = false,
)

/**
 * File-backed implementation. Construction and every operation do filesystem IO, so the store creates and calls it
 * only on its dedicated journal executor. Pending batches are never age-pruned; SQL retention applies after delivery.
 */
internal class FilePlayerTelemetryOutbox(
    root: Path,
    private val settings: PlayerTelemetrySettings,
    private val codec: PlayerTelemetryJsonCodec,
    private val gson: Gson,
) : PlayerTelemetryOutbox {
    private val journal =
        DurableRecordJournal(
            root = root,
            relativeDirectory = Path.of("player-telemetry", "outbox"),
            maxRecordBytes = settings.maxRecordBytes,
            encode = codec::encodeBatch,
            decode = codec::decodeBatch,
            validate = codec::validateBatch,
        )
    private val coverage =
        AtomicFileStore(
            root = root,
            relativePath = Path.of("player-telemetry", "coverage.json"),
            maxBytes = 4_096,
            encode = { gson.toJson(it).toByteArray(StandardCharsets.UTF_8) },
            decode = { gson.fromJson(String(it, StandardCharsets.UTF_8), PlayerTelemetryCoverageState::class.java) },
            validate = ::validateCoverage,
        )
    private var coverageState: PlayerTelemetryCoverageState? = null
    private val corruptIds = linkedSetOf<String>()
    private val recordIds = TreeSet<String>()
    private val recordMetadata = mutableMapOf<String, StoredBatchMetadata>()
    private val oldestTimes = TreeMap<Long, Int>()
    private var pendingEventCount = 0L
    private var pendingByteCount = 0L
    private var corruptByteCount = 0L
    private var pendingCorruptionGap: Long? = null

    @Synchronized
    override fun recover(now: Long): PlayerTelemetryOutboxSnapshot {
        require(now > 0L) { "Recovery timestamp must be positive" }
        val previous = coverage.loadOrNull()
        val gapFrom =
            if (previous != null && !previous.cleanShutdown) {
                listOfNotNull(previous.coverageGapFrom, previous.currentRunStartedAt).minOrNull()
            } else {
                previous?.coverageGapFrom
            }
        val state =
            PlayerTelemetryCoverageState(
                coverageFrom = previous?.coverageFrom ?: now,
                currentRunStartedAt = now,
                lastCleanShutdownAt = previous?.lastCleanShutdownAt,
                coverageGapFrom = gapFrom,
                knownDropCount = previous?.knownDropCount ?: 0L,
                cleanShutdown = false,
            )
        coverageState = coverage.write(state)

        var count = 0L
        var bytes = 0L
        var corrupt = 0
        recordIds.clear()
        recordMetadata.clear()
        oldestTimes.clear()
        pendingEventCount = 0L
        pendingByteCount = 0L
        corruptByteCount = 0L
        corruptIds.clear()
        pendingCorruptionGap = null
        listRecordIds().forEach { id ->
            val path = journal.directory.resolve("$id.json")
            var recordBytes = 0L
            try {
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Outbox record is not a regular file" }
                recordBytes = Files.size(path)
                val events = requireNotNull(journal.loadOrNull(id)) { "Outbox record disappeared during recovery" }
                codec.validateBatch(events)
                val metadata = StoredBatchMetadata(events.size.toLong(), recordBytes, events.minOf(PlayerTelemetryEvent::occurredAt))
                recordIds += id
                recordMetadata[id] = metadata
                addOldest(metadata.oldestAt)
                count += metadata.eventCount
                bytes += metadata.byteCount
            } catch (_: Exception) {
                corruptIds += id
                corrupt++
                if (recordBytes > 0L) corruptByteCount += recordBytes
            }
        }
        pendingEventCount = count
        pendingByteCount = bytes
        if (corrupt > 0) {
            val conservativeGap = state.coverageGapFrom?.let { minOf(it, state.coverageFrom) } ?: state.coverageFrom
            coverageState = coverage.write(state.copy(coverageGapFrom = conservativeGap))
        }
        return PlayerTelemetryOutboxSnapshot(
            eventCount = count,
            byteCount = bytes + corruptByteCount,
            oldestAt = oldestTimes.firstKeyOrNull(),
            coverageFrom = state.coverageFrom,
            coverageGapFrom = coverageState?.coverageGapFrom,
            knownDropCount = state.knownDropCount,
            corruptRecordCount = corrupt,
            uncleanPreviousRun = previous != null && !previous.cleanShutdown,
        )
    }

    @Synchronized
    override fun stats(): PlayerTelemetryOutboxSnapshot {
        persistPendingCorruptionGap()
        val current = requireNotNull(coverageState) { "Outbox must recover before reading stats" }
        return PlayerTelemetryOutboxSnapshot(
            eventCount = pendingEventCount,
            byteCount = pendingByteCount + corruptByteCount,
            oldestAt = oldestTimes.firstKeyOrNull(),
            coverageFrom = current.coverageFrom,
            coverageGapFrom = current.coverageGapFrom,
            knownDropCount = current.knownDropCount,
            corruptRecordCount = corruptIds.size,
            uncleanPreviousRun = false,
        )
    }

    @Synchronized
    override fun commit(batchId: String, events: List<PlayerTelemetryEvent>): PlayerTelemetryOutboxRecord {
        codec.validateBatch(events)
        val snapshot = events.map(PlayerTelemetryEvent::validatedCopy)
        val encoded = codec.encodeBatch(snapshot)
        require(encoded.size.toLong() <= settings.maxRecordBytes) { "Outbox batch exceeds the configured record byte limit" }
        require(batchId !in corruptIds) { "Cannot overwrite a corrupt outbox batch" }
        if (batchId in recordIds) {
            val existing = requireNotNull(journal.loadOrNull(batchId)) { "Committed outbox batch disappeared" }
            require(sameEvents(existing, snapshot)) { "Outbox batch id was reused with different events" }
            return PlayerTelemetryOutboxRecord(batchId, existing, Files.size(journal.directory.resolve("$batchId.json")))
        }
        val existingPath = journal.directory.resolve("$batchId.json")
        if (Files.exists(existingPath, LinkOption.NOFOLLOW_LINKS)) {
            val existing = requireNotNull(journal.loadOrNull(batchId)) { "Existing outbox batch is unreadable" }
            require(sameEvents(existing, snapshot)) { "Outbox batch id was reused with different events" }
            val existingRecord = PlayerTelemetryOutboxRecord(batchId, existing, Files.size(existingPath))
            val metadata = StoredBatchMetadata(existing.size.toLong(), existingRecord.encodedBytes, existingRecord.oldestAt)
            recordIds += batchId
            recordMetadata[batchId] = metadata
            pendingEventCount += metadata.eventCount
            pendingByteCount += metadata.byteCount
            addOldest(metadata.oldestAt)
            return existingRecord
        }
        journal.commit(batchId, snapshot)
        val record = PlayerTelemetryOutboxRecord(batchId, snapshot, encoded.size.toLong())
        val metadata = StoredBatchMetadata(snapshot.size.toLong(), encoded.size.toLong(), record.oldestAt)
        check(recordIds.add(batchId)) { "Outbox batch id already exists" }
        recordMetadata[batchId] = metadata
        pendingEventCount += metadata.eventCount
        pendingByteCount += metadata.byteCount
        addOldest(metadata.oldestAt)
        return record
    }

    @Synchronized
    override fun next(now: Long): PlayerTelemetryOutboxRecord? {
        while (recordIds.isNotEmpty()) {
            val id = recordIds.first()
            var recordBytes = 0L
            try {
                val path = journal.directory.resolve("$id.json")
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Outbox record is not a regular file" }
                recordBytes = Files.size(path)
                val events = requireNotNull(journal.loadOrNull(id)) { "Outbox record disappeared while sending" }
                codec.validateBatch(events)
                return PlayerTelemetryOutboxRecord(id, events.map(PlayerTelemetryEvent::validatedCopy), recordBytes)
            } catch (_: Exception) {
                recordIds.remove(id)
                corruptIds += id
                corruptByteCount += recordBytes
                val coverageStart = requireNotNull(coverageState).coverageFrom
                recordMetadata.remove(id)?.let { metadata ->
                    pendingEventCount -= metadata.eventCount
                    pendingByteCount -= metadata.byteCount
                    removeOldest(metadata.oldestAt)
                }
                pendingCorruptionGap = pendingCorruptionGap?.let { minOf(it, coverageStart) } ?: coverageStart
                persistPendingCorruptionGap()
            }
        }
        return null
    }

    @Synchronized
    override fun acknowledge(record: PlayerTelemetryOutboxRecord): DurableAcknowledgementOutcome =
        journal.acknowledgeExactly(record.recordId, record.events) { expected, current ->
            expected.size == current.size && expected.zip(current).all { (left, right) ->
                codec.hash(left) == codec.hash(right)
            }
        }.also { outcome ->
            if (outcome == DurableAcknowledgementOutcome.ACKNOWLEDGED ||
                outcome == DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED
            ) {
                recordIds.remove(record.recordId)
                recordMetadata.remove(record.recordId)?.let { metadata ->
                    pendingEventCount -= metadata.eventCount
                    pendingByteCount -= metadata.byteCount
                    removeOldest(metadata.oldestAt)
                }
            } else if (outcome == DurableAcknowledgementOutcome.CONTENT_MISMATCH) {
                recordIds.remove(record.recordId)
                corruptIds += record.recordId
                recordMetadata.remove(record.recordId)?.let { metadata ->
                    pendingEventCount -= metadata.eventCount
                    pendingByteCount -= metadata.byteCount
                    removeOldest(metadata.oldestAt)
                }
                corruptByteCount += record.encodedBytes
                val coverageStart = requireNotNull(coverageState).coverageFrom
                pendingCorruptionGap = pendingCorruptionGap?.let { minOf(it, coverageStart) } ?: coverageStart
                persistPendingCorruptionGap()
            }
        }

    @Synchronized
    override fun noteGap(at: Long, droppedEvents: Long): PlayerTelemetryOutboxSnapshot {
        require(droppedEvents >= 0) { "Dropped event count cannot be negative" }
        val current = requireNotNull(coverageState) { "Outbox must recover before recording a coverage gap" }
        val gap = current.coverageGapFrom?.let { minOf(it, at) } ?: at
        coverageState = coverage.write(
            current.copy(
                coverageGapFrom = gap,
                knownDropCount = current.knownDropCount + droppedEvents,
                cleanShutdown = false,
            ),
        )
        return stats()
    }

    @Synchronized
    override fun markCleanShutdown(at: Long) {
        persistPendingCorruptionGap()
        val current = requireNotNull(coverageState) { "Outbox must recover before clean shutdown" }
        coverageState = coverage.write(current.copy(lastCleanShutdownAt = at, cleanShutdown = true))
    }

    @Synchronized
    override fun corruptRecordCount(): Int = corruptIds.size

    private fun listRecordIds(): List<String> =
        Files.list(journal.directory).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".json") }
                .map { path -> path.fileName.toString().removeSuffix(".json") }
                .sorted()
                .toList()
        }

    private fun addOldest(at: Long) {
        oldestTimes[at] = (oldestTimes[at] ?: 0) + 1
    }

    private fun removeOldest(at: Long) {
        val remaining = (oldestTimes[at] ?: return) - 1
        if (remaining == 0) oldestTimes.remove(at) else oldestTimes[at] = remaining
    }

    private fun sameEvents(left: List<PlayerTelemetryEvent>, right: List<PlayerTelemetryEvent>): Boolean =
        left.size == right.size && left.zip(right).all { (old, new) -> codec.hash(old) == codec.hash(new) }

    private fun persistPendingCorruptionGap() {
        val gapAt = pendingCorruptionGap ?: return
        val current = requireNotNull(coverageState) { "Outbox must recover before recording a corruption gap" }
        val gap = current.coverageGapFrom?.let { minOf(it, gapAt) } ?: gapAt
        coverageState = coverage.write(current.copy(coverageGapFrom = gap, cleanShutdown = false))
        pendingCorruptionGap = null
    }

    private fun TreeMap<Long, Int>.firstKeyOrNull(): Long? = if (isEmpty()) null else firstKey()

    private fun validateCoverage(state: PlayerTelemetryCoverageState) {
        require(state.schemaVersion == 1) { "Unsupported player telemetry coverage version" }
        require(state.coverageFrom > 0 && state.currentRunStartedAt > 0) { "Invalid telemetry coverage timestamp" }
        require(state.lastCleanShutdownAt == null || state.lastCleanShutdownAt > 0) { "Invalid clean shutdown timestamp" }
        require(state.coverageGapFrom == null || state.coverageGapFrom > 0) { "Invalid telemetry coverage gap" }
        require(state.knownDropCount >= 0) { "Invalid telemetry drop count" }
    }
}

internal fun PlayerTelemetryEvent.validateForStorage() {
    // Constructing a copy invokes the public model's full validation even for values decoded by Gson.
    validatedCopy()
}
