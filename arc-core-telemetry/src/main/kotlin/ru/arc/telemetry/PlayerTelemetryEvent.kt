package ru.arc.telemetry

import ru.arc.config.Config
import java.util.UUID

/** Immutable, bounded observation. Attribute values are semantic codes, never player-entered text. */
data class PlayerTelemetryEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val occurredAt: Long,
    val server: String,
    val playerId: String? = null,
    val playerName: String? = null,
    val sessionId: String? = null,
    val source: String,
    val event: String,
    val subject: String? = null,
    val operationId: String? = null,
    val world: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val qa: Boolean = false,
    val attributes: Map<String, String> = emptyMap(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    init {
        validate()
    }

    /** Revalidates decoded/untrusted values and snapshots attributes in stable key order. */
    fun validatedCopy(): PlayerTelemetryEvent {
        validate()
        return copy(
            eventId = canonicalUuid(eventId, "eventId"),
            playerId = playerId?.let { canonicalUuid(it, "playerId") },
            sessionId = sessionId?.let { canonicalUuid(it, "sessionId") },
            attributes = attributes.toSortedMap().toMap(),
        )
    }

    /** Conservative reservation estimate; identifiers and attribute strings are restricted to ASCII. */
    internal fun estimatedBytes(): Long =
        512L + listOfNotNull(
            eventId, server, playerId, playerName, sessionId, source, event,
            subject, operationId, world,
        ).sumOf { it.length.toLong() * 2L } + attributes.entries.sumOf {
            (it.key.length.toLong() + it.value.length.toLong()) * 2L + 16L
        }

    private fun validate() {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported player telemetry schema version" }
        canonicalUuid(eventId, "eventId")
        playerId?.let { canonicalUuid(it, "playerId") }
        sessionId?.let { canonicalUuid(it, "sessionId") }
        require(occurredAt in 1..MAX_EPOCH_MILLIS) { "occurredAt is outside the supported epoch range" }
        requireIdentifier(server, MAX_SERVER_LENGTH, "server")
        requireIdentifier(source, MAX_SOURCE_LENGTH, "source")
        requireIdentifier(event, MAX_EVENT_LENGTH, "event")
        subject?.let { requireIdentifier(it, MAX_SUBJECT_LENGTH, "subject") }
        operationId?.let { requireIdentifier(it, MAX_OPERATION_ID_LENGTH, "operationId") }
        playerName?.let {
            require(PLAYER_NAME.matches(it)) { "playerName must be a Minecraft username" }
        }
        world?.let { requireIdentifier(it, MAX_WORLD_LENGTH, "world") }
        val coordinates = listOf(x, y, z)
        require(coordinates.all { it == null } || (coordinates.all { it != null } && world != null)) {
            "Coordinates require a world and must be supplied as a complete triple"
        }
        x?.let { require(it.isFinite() && kotlin.math.abs(it) <= MAX_HORIZONTAL_COORDINATE) { "x is outside the supported coordinate range" } }
        z?.let { require(it.isFinite() && kotlin.math.abs(it) <= MAX_HORIZONTAL_COORDINATE) { "z is outside the supported coordinate range" } }
        y?.let { require(it.isFinite() && kotlin.math.abs(it) <= MAX_VERTICAL_COORDINATE) { "y is outside the supported coordinate range" } }
        require(attributes.size <= MAX_ATTRIBUTES) { "Too many player telemetry attributes" }
        attributes.forEach { (key, value) ->
            require(ATTRIBUTE_KEY.matches(key)) { "Invalid player telemetry attribute key" }
            require(value.length <= MAX_ATTRIBUTE_VALUE_LENGTH && ATTRIBUTE_VALUE.matches(value)) {
                "Invalid player telemetry attribute value"
            }
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_ATTRIBUTES = 32
        const val MAX_ATTRIBUTE_VALUE_LENGTH = 160
        private const val MAX_SERVER_LENGTH = 32
        private const val MAX_SOURCE_LENGTH = 48
        private const val MAX_EVENT_LENGTH = 64
        private const val MAX_SUBJECT_LENGTH = 96
        private const val MAX_OPERATION_ID_LENGTH = 128
        private const val MAX_WORLD_LENGTH = 64
        private const val MAX_HORIZONTAL_COORDINATE = 30_000_000.0
        private const val MAX_VERTICAL_COORDINATE = 100_000.0
        private const val MAX_EPOCH_MILLIS = 253_402_300_799_999L
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]*")
        private val PLAYER_NAME = Regex("[A-Za-z0-9_]{3,16}")
        private val ATTRIBUTE_KEY = Regex("[a-z][A-Za-z0-9_.-]{0,31}")
        private val ATTRIBUTE_VALUE = Regex("[A-Za-z0-9_ .,:+=/#@-]*")

        private fun requireIdentifier(value: String, maximumLength: Int, field: String) {
            require(value.length <= maximumLength && IDENTIFIER.matches(value)) { "$field is not a bounded safe identifier" }
        }

        private fun canonicalUuid(value: String, field: String): String {
            require(UUID_PATTERN.matches(value)) { "$field must be a canonical UUID" }
            return runCatching { UUID.fromString(value).toString() }
                .getOrElse { throw IllegalArgumentException("$field must be a canonical UUID", it) }
        }
    }
}

data class PlayerTelemetryCursor(val occurredAt: Long, val eventId: String) {
    init {
        require(occurredAt in 1..253_402_300_799_999L) { "Cursor timestamp is outside the supported epoch range" }
        require(eventId.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) {
            "Cursor eventId must be a canonical UUID"
        }
    }
}

data class PlayerTelemetryQuery(
    val fromInclusive: Long,
    val untilExclusive: Long,
    val playerId: String? = null,
    val playerName: String? = null,
    val sessionId: String? = null,
    val server: String? = null,
    val source: String? = null,
    val event: String? = null,
    val subject: String? = null,
    val includeQa: Boolean = false,
    val limit: Int = 100,
    val cursor: PlayerTelemetryCursor? = null,
) {
    init {
        validateBounds(fromInclusive, untilExclusive)
        validateFilters(playerId, playerName, sessionId, server, source, event, subject)
        require(limit in 1..MAX_QUERY_LIMIT) { "Query limit must be between 1 and $MAX_QUERY_LIMIT" }
        cursor?.let { require(it.occurredAt in fromInclusive until untilExclusive) { "Cursor must fall inside the requested bounds" } }
    }
}

data class PlayerTelemetrySummaryQuery(
    val fromInclusive: Long,
    val untilExclusive: Long,
    val playerId: String? = null,
    val playerName: String? = null,
    val sessionId: String? = null,
    val server: String? = null,
    val source: String? = null,
    val event: String? = null,
    val subject: String? = null,
    val includeQa: Boolean = false,
    val limit: Int = 5_000,
) {
    init {
        validateBounds(fromInclusive, untilExclusive)
        validateFilters(playerId, playerName, sessionId, server, source, event, subject)
        require(limit in 1..MAX_SUMMARY_GROUPS) { "Summary limit must be between 1 and $MAX_SUMMARY_GROUPS" }
    }
}

data class PlayerTelemetryPage(
    val fromInclusive: Long,
    val untilExclusive: Long,
    val coverageFrom: Long?,
    val coverageGapFrom: Long?,
    val retainedFrom: Long,
    val nodesObserved: Long = 0L,
    val oldestNodeLastSeenAt: Long? = null,
    val oldestNodeCoverageThrough: Long? = null,
    val historyCovered: Boolean,
    val events: List<PlayerTelemetryEvent>,
    val hasMore: Boolean,
    val nextCursor: PlayerTelemetryCursor?,
    /** True only when pagination has reached the last row; compare coverageFrom for history limits. */
    val complete: Boolean = !hasMore,
)

data class PlayerTelemetrySummaryRow(
    val utcDay: String,
    val server: String,
    val source: String,
    val event: String,
    val count: Long,
    val uniquePlayers: Long,
)

data class PlayerTelemetrySummary(
    val fromInclusive: Long,
    val untilExclusive: Long,
    val coverageFrom: Long?,
    val coverageGapFrom: Long?,
    val retainedFrom: Long,
    val nodesObserved: Long = 0L,
    val oldestNodeLastSeenAt: Long? = null,
    val oldestNodeCoverageThrough: Long? = null,
    val historyCovered: Boolean,
    val rows: List<PlayerTelemetrySummaryRow>,
    val hasMore: Boolean,
)

data class PlayerTelemetrySettings(
    val batchSize: Int = 250,
    val captureQueueCapacity: Int = 20_000,
    val maxOutboxEvents: Int = 250_000,
    val maxOutboxBytes: Long = 512L * 1024L * 1024L,
    val maxRecordBytes: Long = 1024L * 1024L,
    val flushIntervalMillis: Long = 250,
    val retryIntervalMillis: Long = 1_000,
    val retentionDays: Int = 90,
    val shutdownTimeoutMillis: Long = 5_000,
    val maxQueryLimit: Int = 500,
    val maxSummaryGroups: Int = 5_000,
    val retentionBatchSize: Int = 1_000,
) {
    init {
        require(batchSize in 1..2_000) { "Telemetry batch size must be between 1 and 2000" }
        require(captureQueueCapacity in 1..1_000_000) { "Capture queue capacity is outside the supported range" }
        require(maxOutboxEvents >= batchSize) { "Outbox event capacity must fit one batch" }
        require(maxOutboxBytes >= maxRecordBytes && maxRecordBytes in 1_024..64L * 1024L * 1024L) {
            "Outbox and journal record byte bounds are invalid"
        }
        require(flushIntervalMillis in 25..60_000) { "Flush interval must be between 25ms and 60s" }
        require(retryIntervalMillis in 100..300_000) { "Retry interval must be between 100ms and 5m" }
        require(retentionDays in 1..3_650) { "Retention must be between 1 and 3650 days" }
        require(shutdownTimeoutMillis in 1..120_000) { "Shutdown drain timeout is outside the supported range" }
        require(maxQueryLimit in 1..500) { "Maximum query page size cannot exceed 500" }
        require(maxSummaryGroups in 1..50_000) { "Maximum summary groups is outside the supported range" }
        require(retentionBatchSize in 1..10_000) { "Retention batch size is outside the supported range" }
    }

    companion object {
        const val MAX_QUERY_LIMIT = 500
        const val MAX_SUMMARY_GROUPS = 50_000

        /** Reads only the canonical telemetry keys and rejects malformed values rather than silently defaulting. */
        fun from(config: Config): PlayerTelemetrySettings = PlayerTelemetrySettings(
            batchSize = config.strictInt("writer.batch-size", 250),
            captureQueueCapacity = config.strictInt("writer.capture-queue-capacity", 20_000),
            maxOutboxEvents = config.strictInt("writer.max-outbox-events", 250_000),
            maxOutboxBytes = config.strictLong("writer.max-outbox-bytes", 512L * 1024L * 1024L),
            maxRecordBytes = config.strictLong("writer.max-record-bytes", 1024L * 1024L),
            flushIntervalMillis = config.strictLong("writer.flush-interval-ms", 250L),
            retryIntervalMillis = config.strictLong("writer.retry-interval-ms", 1_000L),
            retentionDays = config.strictInt("retention-days", 90),
            shutdownTimeoutMillis = config.strictLong("shutdown-timeout-ms", 5_000L),
            maxQueryLimit = config.strictInt("queries.max-limit", 500),
            maxSummaryGroups = config.strictInt("queries.max-summary-groups", 5_000),
            retentionBatchSize = config.strictInt("retention-batch-size", 1_000),
        )
    }
}

private fun validateBounds(fromInclusive: Long, untilExclusive: Long) {
    require(fromInclusive in 1..253_402_300_799_999L) { "Query start is outside the supported epoch range" }
    require(untilExclusive > fromInclusive && untilExclusive <= 253_402_300_799_999L) {
        "Query end must be after its start and inside the supported epoch range"
    }
}

private fun validateFilters(
    playerId: String?, playerName: String?, sessionId: String?, server: String?,
    source: String?, event: String?, subject: String?,
) {
    playerId?.let { require(UUID.fromString(it).toString() == it.lowercase()) { "playerId must be a canonical UUID" } }
    sessionId?.let { require(UUID.fromString(it).toString() == it.lowercase()) { "sessionId must be a canonical UUID" } }
    playerName?.let { require(Regex("[A-Za-z0-9_]{3,16}").matches(it)) { "playerName must be a Minecraft username" } }
    listOfNotNull(
        server?.let { it to 32 }, source?.let { it to 48 }, event?.let { it to 64 }, subject?.let { it to 96 },
    ).forEach { (value, max) ->
        require(value.length <= max && Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]*").matches(value)) {
            "Telemetry filter must be a bounded safe identifier"
        }
    }
}

private fun Config.strictInt(path: String, default: Int): Int {
    if (!exists(path)) return default
    return stringOrNull(path)?.toIntOrNull()
        ?: throw IllegalArgumentException("Telemetry config '$path' must be an integer")
}

private fun Config.strictLong(path: String, default: Long): Long {
    if (!exists(path)) return default
    return stringOrNull(path)?.toLongOrNull()
        ?: throw IllegalArgumentException("Telemetry config '$path' must be an integer")
}

private const val MAX_QUERY_LIMIT = 500
private const val MAX_SUMMARY_GROUPS = 50_000
