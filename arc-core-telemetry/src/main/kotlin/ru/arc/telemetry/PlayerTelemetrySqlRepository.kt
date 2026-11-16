package ru.arc.telemetry

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlMigration
import ru.arc.sql.SqlRuntime
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal data class PlayerTelemetryCoverage(
    val coverageFrom: Long?,
    val coverageGapFrom: Long?,
    val retainedFrom: Long,
    val nodesObserved: Long,
    val oldestNodeLastSeenAt: Long?,
    val nodesWithCoverageThrough: Long,
    val oldestNodeCoverageThrough: Long?,
) {
    fun covers(from: Long, until: Long): Boolean =
        coverageFrom != null && from >= coverageFrom &&
            (coverageGapFrom == null || until <= coverageGapFrom) && from >= retainedFrom &&
            nodesObserved > 0 && oldestNodeLastSeenAt != null &&
            nodesWithCoverageThrough == nodesObserved && oldestNodeCoverageThrough != null &&
            until <= oldestNodeCoverageThrough
}

internal class PlayerTelemetrySqlRepository(
    val runtime: SqlRuntime,
    private val settings: PlayerTelemetrySettings,
    private val codec: PlayerTelemetryJsonCodec,
    private val gson: Gson,
    private val clockMillis: () -> Long,
) : PlayerTelemetrySql {
    private val attributesType = TypeToken.getParameterized(Map::class.java, String::class.java, String::class.java).type

    override fun migrate(): CompletableFuture<Unit> =
        runtime.executor.submit {
            // Version 2 belonged to the menu-only candidate. Leave any existing v2 table untouched.
            MySqlMigrator(runtime.dataSource, MIGRATION_NAMESPACE)
                .migrate(listOf(LEGACY_MIGRATION, COMPACT_MIGRATION))
            Unit
        }

    override fun registerServer(
        server: String,
        coverageFrom: Long,
        coverageGapFrom: Long?,
        knownDrops: Long,
    ): CompletableFuture<Unit> = runtime.executor.transaction { connection ->
        connection.prepareStatement(UPSERT_NODE).use { statement ->
            statement.setString(1, server)
            statement.setLong(2, coverageFrom)
            statement.setLong(3, clockMillis())
            if (coverageGapFrom == null) statement.setNull(4, Types.BIGINT) else statement.setLong(4, coverageGapFrom)
            statement.setLong(5, knownDrops)
            statement.executeUpdate()
        }
        Unit
    }

    override fun advanceCoverage(
        server: String,
        through: Long,
        coverageGapFrom: Long?,
        knownDrops: Long,
    ): CompletableFuture<Unit> = runtime.executor.transaction { connection ->
        connection.prepareStatement(ADVANCE_NODE_COVERAGE).use { statement ->
            statement.setLong(1, through)
            statement.setLong(2, through)
            if (coverageGapFrom == null) statement.setNull(3, Types.BIGINT) else statement.setLong(3, coverageGapFrom)
            if (coverageGapFrom == null) statement.setNull(4, Types.BIGINT) else statement.setLong(4, coverageGapFrom)
            if (coverageGapFrom == null) statement.setNull(5, Types.BIGINT) else statement.setLong(5, coverageGapFrom)
            statement.setLong(6, knownDrops)
            statement.setString(7, server)
            check(statement.executeUpdate() == 1) { "Player telemetry node is not registered" }
        }
        Unit
    }

    /** Preserve the capture schema's destination on replay and commit the whole outbox batch atomically. */
    override fun insertBatch(events: List<PlayerTelemetryEvent>): CompletableFuture<Unit> {
        require(events.isNotEmpty() && events.size <= settings.batchSize) { "SQL telemetry batch size is invalid" }
        val snapshots = events.map(PlayerTelemetryEvent::validatedCopy)
        require(snapshots.map(PlayerTelemetryEvent::eventId).toSet().size == snapshots.size) {
            "SQL telemetry batch contains duplicate event ids"
        }
        val hashes = snapshots.associate { it.eventId to codec.hash(it) }
        val batches = snapshots.groupBy { eventsTable(it.schemaVersion == PlayerTelemetryEvent.LEGACY_SCHEMA_VERSION) }
        return runtime.executor.transaction { connection ->
            for (table in EVENT_TABLES) {
                val batch = batches[table] ?: continue
                val compact = table == COMPACT_EVENTS_TABLE
                val formatColumns = if (compact) "`attributes`, `event_hash`, `event_count`"
                    else "`attributes_json`, `event_hash`, `schema_version`"
                val insertSql = "INSERT IGNORE INTO `$table` ($EVENT_COLUMNS, `ingested_at_ms`, $formatColumns) VALUES ($INSERT_VALUES)"
                connection.prepareStatement(insertSql).use { statement ->
                    batch.forEach { event ->
                        bind(statement, event, hashes.getValue(event.eventId), clockMillis(), compact)
                        statement.addBatch()
                    }
                    statement.executeBatch().forEach { changed ->
                        check(changed != Statement.EXECUTE_FAILED) { "SQL telemetry batch insert failed" }
                    }
                }
                val batchHashes = batch.associate { it.eventId to hashes.getValue(it.eventId) }
                val placeholders = batch.joinToString(",", "(", ")") { "?" }
                val hashesSql = "SELECT `event_id`, `event_hash` FROM `$table` WHERE `event_id` IN $placeholders"
                verifyHashes(connection.prepareStatement(hashesSql).use { statement ->
                    batch.forEachIndexed { index, event -> bindUuid(statement, index + 1, event.eventId, compact) }
                    statement.executeQuery().use { result ->
                        buildMap {
                            while (result.next()) {
                                val id = requireNotNull(readUuid(result, "event_id", compact))
                                val hash = if (compact) HEX.formatHex(result.getBytes("event_hash")) else result.getString("event_hash")
                                put(id, hash)
                            }
                        }
                    }
                }, batchHashes)
            }
        }
    }

    override fun query(request: PlayerTelemetryQuery): CompletableFuture<PlayerTelemetryPage> =
        runtime.executor.read { connection ->
            val table = eventsTable(request.legacy)
            val compact = !request.legacy
            val coverage = coverage(connection, request.server, request.legacy)
            val where = where(request)
            val cursorSql = if (request.cursor == null) "" else
                " AND (`occurred_at_ms` > ? OR (`occurred_at_ms` = ? AND `event_id` > ?))"
            val formatColumns = if (compact) "`attributes`" else "`attributes_json`, `schema_version`"
            val sql = "SELECT $EVENT_COLUMNS, $formatColumns FROM `$table` WHERE $where$cursorSql " +
                "ORDER BY `occurred_at_ms` ASC, `event_id` ASC LIMIT ?"
            val rows = connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                var index = bindFilters(statement, request)
                request.cursor?.let { cursor ->
                    statement.setLong(index++, cursor.occurredAt)
                    statement.setLong(index++, cursor.occurredAt)
                    bindUuid(statement, index++, cursor.eventId, compact)
                }
                statement.setInt(index, request.limit + 1)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(readEvent(result, compact)) } }
            }
            val hasMore = rows.size > request.limit
            val events = rows.take(request.limit)
            PlayerTelemetryPage(
                fromInclusive = request.fromInclusive,
                untilExclusive = request.untilExclusive,
                coverageFrom = coverage.coverageFrom,
                coverageGapFrom = coverage.coverageGapFrom,
                retainedFrom = coverage.retainedFrom,
                nodesObserved = coverage.nodesObserved,
                oldestNodeLastSeenAt = coverage.oldestNodeLastSeenAt,
                oldestNodeCoverageThrough = coverage.oldestNodeCoverageThrough,
                historyCovered = coverage.covers(request.fromInclusive, request.untilExclusive),
                events = events,
                hasMore = hasMore,
                nextCursor = events.lastOrNull()?.let { PlayerTelemetryCursor(it.occurredAt, it.eventId) },
                complete = !hasMore,
            )
        }

    override fun summary(request: PlayerTelemetrySummaryQuery): CompletableFuture<PlayerTelemetrySummary> =
        runtime.executor.read { connection ->
            val table = eventsTable(request.legacy)
            val coverage = coverage(connection, request.server, request.legacy)
            val where = where(request)
            val count = if (request.legacy) "COUNT(*)" else "SUM(`event_count`)"
            val sql =
                "SELECT DATE_FORMAT(FROM_UNIXTIME(`occurred_at_ms` / 1000), '%Y-%m-%d') AS `utc_day`, " +
                    "`server`, `source`, `event`, $count AS `event_count`, " +
                    "COUNT(DISTINCT `player_id`) AS `unique_players` FROM `$table` WHERE $where " +
                    "GROUP BY `utc_day`, `server`, `source`, `event` " +
                    "ORDER BY `utc_day`, `server`, `source`, `event` LIMIT ?"
            val rows = connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                val index = bindFilters(statement, request)
                statement.setInt(index, request.limit + 1)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                PlayerTelemetrySummaryRow(
                                    utcDay = result.getString("utc_day"),
                                    server = result.getString("server"),
                                    source = result.getString("source"),
                                    event = result.getString("event"),
                                    count = result.getLong("event_count"),
                                    uniquePlayers = result.getLong("unique_players"),
                                ),
                            )
                        }
                    }
                }
            }
            PlayerTelemetrySummary(
                fromInclusive = request.fromInclusive,
                untilExclusive = request.untilExclusive,
                coverageFrom = coverage.coverageFrom,
                coverageGapFrom = coverage.coverageGapFrom,
                retainedFrom = coverage.retainedFrom,
                nodesObserved = coverage.nodesObserved,
                oldestNodeLastSeenAt = coverage.oldestNodeLastSeenAt,
                oldestNodeCoverageThrough = coverage.oldestNodeCoverageThrough,
                historyCovered = coverage.covers(request.fromInclusive, request.untilExclusive),
                rows = rows.take(request.limit),
                hasMore = rows.size > request.limit,
            )
        }

    override fun prune(cutoff: Long): CompletableFuture<Int> = runtime.executor.write { connection ->
        val sql = "DELETE FROM `$COMPACT_EVENTS_TABLE` WHERE `occurred_at_ms` < ? ORDER BY `occurred_at_ms` LIMIT ?"
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, cutoff)
            statement.setInt(2, settings.retentionBatchSize)
            statement.executeUpdate()
        }
    }

    private fun eventsTable(legacy: Boolean): String = if (legacy) EVENTS_TABLE else COMPACT_EVENTS_TABLE

    private fun coverage(connection: Connection, server: String?, legacy: Boolean): PlayerTelemetryCoverage {
        val retainedFrom = clockMillis() - settings.retentionDays * MILLIS_PER_DAY
        val nodes = if (legacy) "`$NODES_TABLE` AS node" else COMPACT_COVERAGE_NODES
        val serverColumn = if (legacy) "node.`server`" else "registered.`server`"
        val predicate = if (server == null) "" else " WHERE $serverColumn = ?"
        val through = if (legacy) "node.`coverage_through_ms`" else COMPACT_COVERAGE_THROUGH
        return connection.prepareStatement(
            "SELECT MAX(node.`coverage_from_ms`) AS `coverage_from`, MIN(node.`coverage_gap_from_ms`) AS `coverage_gap_from`, " +
                "COUNT(*) AS `nodes_observed`, MIN(node.`last_seen_at_ms`) AS `oldest_node_last_seen_at`, " +
                "SUM(($through) IS NOT NULL) AS `nodes_with_coverage_through`, " +
                "MIN($through) AS `oldest_node_coverage_through` FROM $nodes$predicate",
        ).use { statement ->
            statement.queryTimeout = QUERY_TIMEOUT_SECONDS
            server?.let { statement.setString(1, it) }
            statement.executeQuery().use { result ->
                check(result.next()) { "Player telemetry node coverage query returned no aggregate row" }
                val coverageFrom = result.getLong("coverage_from").takeUnless { result.wasNull() }
                val coverageGapFrom = result.getLong("coverage_gap_from").takeUnless { result.wasNull() }
                val nodesObserved = result.getLong("nodes_observed")
                val oldestNodeLastSeenAt = result.getLong("oldest_node_last_seen_at").takeUnless { result.wasNull() }
                val nodesWithCoverageThrough = result.getLong("nodes_with_coverage_through")
                val oldestNodeCoverageThrough = result.getLong("oldest_node_coverage_through").takeUnless { result.wasNull() }
                PlayerTelemetryCoverage(
                    coverageFrom = coverageFrom,
                    coverageGapFrom = coverageGapFrom,
                    retainedFrom = retainedFrom,
                    nodesObserved = nodesObserved,
                    oldestNodeLastSeenAt = oldestNodeLastSeenAt,
                    nodesWithCoverageThrough = nodesWithCoverageThrough,
                    oldestNodeCoverageThrough = oldestNodeCoverageThrough,
                )
            }
        }
    }

    private fun where(request: PlayerTelemetryQuery): String = buildString {
        append("`occurred_at_ms` >= ? AND `occurred_at_ms` < ?")
        if (request.playerId != null) append(" AND `player_id` = ?")
        if (request.playerName != null) append(" AND `player_name` = ?")
        if (request.sessionId != null) append(" AND `session_id` = ?")
        if (request.server != null) append(" AND `server` = ?")
        if (request.source != null) append(" AND `source` = ?")
        if (request.event != null) append(" AND `event` = ?")
        if (request.subject != null) append(" AND `subject` = ?")
        if (!request.includeQa) append(" AND `qa` = FALSE")
    }

    private fun where(request: PlayerTelemetrySummaryQuery): String = buildString {
        append("`occurred_at_ms` >= ? AND `occurred_at_ms` < ?")
        if (request.playerId != null) append(" AND `player_id` = ?")
        if (request.playerName != null) append(" AND `player_name` = ?")
        if (request.sessionId != null) append(" AND `session_id` = ?")
        if (request.server != null) append(" AND `server` = ?")
        if (request.source != null) append(" AND `source` = ?")
        if (request.event != null) append(" AND `event` = ?")
        if (request.subject != null) append(" AND `subject` = ?")
        if (!request.includeQa) append(" AND `qa` = FALSE")
    }

    private fun bindFilters(statement: PreparedStatement, request: PlayerTelemetryQuery): Int {
        var index = 1
        statement.setLong(index++, request.fromInclusive)
        statement.setLong(index++, request.untilExclusive)
        request.playerId?.let { bindUuid(statement, index++, it, !request.legacy) }
        request.playerName?.let { statement.setString(index++, it) }
        request.sessionId?.let { bindUuid(statement, index++, it, !request.legacy) }
        request.server?.let { statement.setString(index++, it) }
        request.source?.let { statement.setString(index++, it) }
        request.event?.let { statement.setString(index++, it) }
        request.subject?.let { statement.setString(index++, it) }
        return index
    }

    private fun bindFilters(statement: PreparedStatement, request: PlayerTelemetrySummaryQuery): Int {
        var index = 1
        statement.setLong(index++, request.fromInclusive)
        statement.setLong(index++, request.untilExclusive)
        request.playerId?.let { bindUuid(statement, index++, it, !request.legacy) }
        request.playerName?.let { statement.setString(index++, it) }
        request.sessionId?.let { bindUuid(statement, index++, it, !request.legacy) }
        request.server?.let { statement.setString(index++, it) }
        request.source?.let { statement.setString(index++, it) }
        request.event?.let { statement.setString(index++, it) }
        request.subject?.let { statement.setString(index++, it) }
        return index
    }

    private fun bind(statement: PreparedStatement, event: PlayerTelemetryEvent, hash: String, ingestedAt: Long, compact: Boolean) {
        bindUuid(statement, 1, event.eventId, compact)
        statement.setLong(2, event.occurredAt)
        statement.setString(3, event.server)
        bindUuid(statement, 4, event.playerId, compact)
        statement.setString(5, event.playerName)
        bindUuid(statement, 6, event.sessionId, compact)
        statement.setString(7, event.source)
        statement.setString(8, event.event)
        statement.setString(9, event.subject)
        statement.setString(10, event.operationId)
        statement.setString(11, event.world)
        setNullableDouble(statement, 12, event.x)
        setNullableDouble(statement, 13, event.y)
        setNullableDouble(statement, 14, event.z)
        statement.setBoolean(15, event.qa)
        statement.setLong(16, ingestedAt)
        if (compact) {
            statement.setBytes(17, PlayerTelemetryCompactAttributes.encode(event.attributes))
            statement.setBytes(18, HEX.parseHex(hash))
            statement.setInt(19, event.occurrences)
        } else {
            statement.setString(17, codec.encodeAttributes(event))
            statement.setString(18, hash)
            statement.setInt(19, event.schemaVersion)
        }
    }

    /** The UUID's network byte order compares the same way as its canonical lowercase string. */
    private fun bindUuid(statement: PreparedStatement, index: Int, value: String?, compact: Boolean) {
        if (compact) {
            val bytes = value?.let(UUID::fromString)?.let {
                ByteBuffer.allocate(16).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array()
            }
            statement.setBytes(index, bytes)
        } else {
            statement.setString(index, value?.lowercase())
        }
    }

    private fun readUuid(result: ResultSet, column: String, compact: Boolean): String? =
        if (compact) result.getBytes(column)?.let {
            val bytes = ByteBuffer.wrap(it)
            UUID(bytes.getLong(), bytes.getLong()).toString()
        } else result.getString(column)

    private fun setNullableDouble(statement: PreparedStatement, index: Int, value: Double?) {
        if (value == null) statement.setNull(index, Types.DOUBLE) else statement.setDouble(index, value)
    }

    private fun readEvent(result: ResultSet, compact: Boolean): PlayerTelemetryEvent =
        PlayerTelemetryEvent(
            eventId = requireNotNull(readUuid(result, "event_id", compact)),
            occurredAt = result.getLong("occurred_at_ms"),
            server = result.getString("server"),
            playerId = readUuid(result, "player_id", compact),
            playerName = result.getString("player_name"),
            sessionId = readUuid(result, "session_id", compact),
            source = result.getString("source"),
            event = result.getString("event"),
            subject = result.getString("subject"),
            operationId = result.getString("operation_id"),
            world = result.getString("world"),
            x = result.getDouble("x").takeUnless { result.wasNull() },
            y = result.getDouble("y").takeUnless { result.wasNull() },
            z = result.getDouble("z").takeUnless { result.wasNull() },
            qa = result.getBoolean("qa"),
            attributes = if (compact) PlayerTelemetryCompactAttributes.decode(result.getBytes("attributes"))
                else gson.fromJson(result.getString("attributes_json"), attributesType) ?: emptyMap(),
            schemaVersion = if (compact) PlayerTelemetryEvent.CURRENT_SCHEMA_VERSION else result.getInt("schema_version"),
        )

    private fun verifyHashes(actual: Map<String, String>, expected: Map<String, String>) {
        check(actual.keys == expected.keys) { "Player telemetry insert readback omitted event ids" }
        check(expected.all { (id, hash) -> actual[id] == hash }) {
            "Player telemetry event id was reused with different content"
        }
    }

    override fun close() = runtime.close()

    companion object {
        private const val MIGRATION_NAMESPACE = "arc_player_telemetry"
        private const val EVENTS_TABLE = "arc_player_events"
        private const val COMPACT_EVENTS_TABLE = "arc_player_events_v2"
        private val EVENT_TABLES = listOf(EVENTS_TABLE, COMPACT_EVENTS_TABLE)
        private const val NODES_TABLE = "arc_player_event_nodes"
        private const val COMPACT_NODES_TABLE = "arc_player_event_nodes_v2"
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val QUERY_TIMEOUT_SECONDS = 3
        private val HEX = HexFormat.of()
        private const val EVENT_COLUMNS =
            "`event_id`, `occurred_at_ms`, `server`, `player_id`, `player_name`, `session_id`, " +
                "`source`, `event`, `subject`, `operation_id`, `world`, `x`, `y`, `z`, `qa`"
        private const val INSERT_VALUES = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?"
        private const val COMPACT_COVERAGE_NODES =
            "(SELECT `server` FROM `$COMPACT_NODES_TABLE` UNION SELECT `server` FROM `$NODES_TABLE`) AS registered " +
                "LEFT JOIN `$COMPACT_NODES_TABLE` AS node ON node.`server` = registered.`server` " +
                "LEFT JOIN `$NODES_TABLE` AS old_node ON old_node.`server` = registered.`server`"
        // A missing compact node or renewed legacy activity cannot prove continuous compact coverage.
        private const val COMPACT_COVERAGE_THROUGH =
            "CASE WHEN old_node.`last_seen_at_ms` >= node.`coverage_from_ms` " +
                "OR old_node.`coverage_through_ms` > node.`coverage_from_ms` " +
                "THEN NULL ELSE node.`coverage_through_ms` END"
        private const val UPSERT_NODE =
            "INSERT INTO `$COMPACT_NODES_TABLE` (`server`, `coverage_from_ms`, `last_seen_at_ms`, `coverage_gap_from_ms`, `known_drops`) " +
                "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE `coverage_from_ms` = LEAST(`coverage_from_ms`, VALUES(`coverage_from_ms`)), " +
                "`last_seen_at_ms` = VALUES(`last_seen_at_ms`), " +
                "`coverage_gap_from_ms` = CASE WHEN VALUES(`coverage_gap_from_ms`) IS NULL THEN `coverage_gap_from_ms` " +
                "WHEN `coverage_gap_from_ms` IS NULL THEN VALUES(`coverage_gap_from_ms`) " +
                "ELSE LEAST(`coverage_gap_from_ms`, VALUES(`coverage_gap_from_ms`)) END, " +
                "`known_drops` = GREATEST(`known_drops`, VALUES(`known_drops`))"
        private const val ADVANCE_NODE_COVERAGE =
            "UPDATE `$COMPACT_NODES_TABLE` SET `coverage_through_ms` = CASE WHEN `coverage_through_ms` IS NULL THEN ? " +
                "ELSE GREATEST(`coverage_through_ms`, ?) END, " +
                "`coverage_gap_from_ms` = CASE WHEN ? IS NULL THEN `coverage_gap_from_ms` " +
                "WHEN `coverage_gap_from_ms` IS NULL THEN ? ELSE LEAST(`coverage_gap_from_ms`, ?) END, " +
                "`known_drops` = GREATEST(`known_drops`, ?) WHERE `server` = ?"
        internal val LEGACY_MIGRATION = SqlMigration(
            version = 1,
            description = "create durable player telemetry events and node coverage",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS `$EVENTS_TABLE` (
                    `event_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    `schema_version` SMALLINT UNSIGNED NOT NULL,
                    `occurred_at_ms` BIGINT UNSIGNED NOT NULL,
                    `ingested_at_ms` BIGINT UNSIGNED NOT NULL,
                    `server` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    `player_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    `player_name` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    `session_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    `source` VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    `event` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    `subject` VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    `operation_id` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    `world` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    `x` DOUBLE NULL,
                    `y` DOUBLE NULL,
                    `z` DOUBLE NULL,
                    `qa` BOOLEAN NOT NULL DEFAULT FALSE,
                    `attributes_json` TEXT CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    `event_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    PRIMARY KEY (`event_id`),
                    KEY `arc_player_events_time_idx` (`occurred_at_ms`, `event_id`),
                    KEY `arc_player_events_player_idx` (`player_id`, `occurred_at_ms`, `event_id`),
                    KEY `arc_player_events_session_idx` (`session_id`, `occurred_at_ms`, `event_id`),
                    KEY `arc_player_events_server_idx` (`server`, `occurred_at_ms`, `event_id`),
                    KEY `arc_player_events_source_event_idx` (`source`, `event`, `occurred_at_ms`, `event_id`),
                    KEY `arc_player_events_subject_idx` (`subject`, `occurred_at_ms`, `event_id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `$NODES_TABLE` (
                    `server` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    `coverage_from_ms` BIGINT UNSIGNED NOT NULL,
                    `last_seen_at_ms` BIGINT UNSIGNED NOT NULL,
                    `coverage_gap_from_ms` BIGINT UNSIGNED NULL,
                    `known_drops` BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    `coverage_through_ms` BIGINT UNSIGNED NULL,
                    PRIMARY KEY (`server`),
                    KEY `arc_player_event_nodes_coverage_idx` (`coverage_from_ms`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
            ),
        )
        private val COMPACT_MIGRATION = SqlMigration(
            version = 3,
            description = "create empty compact player events and node coverage tables",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS `$COMPACT_EVENTS_TABLE` (
                    `event_id` BINARY(16) NOT NULL,
                    `occurred_at_ms` BIGINT UNSIGNED NOT NULL,
                    `ingested_at_ms` BIGINT UNSIGNED NOT NULL,
                    `server` VARCHAR(32) NOT NULL,
                    `player_id` BINARY(16) NULL,
                    `player_name` VARCHAR(16) NULL,
                    `session_id` BINARY(16) NULL,
                    `source` VARCHAR(48) NOT NULL,
                    `event` VARCHAR(64) NOT NULL,
                    `subject` VARCHAR(96) NULL,
                    `operation_id` VARCHAR(128) NULL,
                    `world` VARCHAR(64) NULL,
                    `x` DOUBLE NULL,
                    `y` DOUBLE NULL,
                    `z` DOUBLE NULL,
                    `qa` BOOLEAN NOT NULL DEFAULT FALSE,
                    `attributes` BLOB NULL,
                    `event_hash` BINARY(32) NOT NULL,
                    `event_count` INT UNSIGNED NOT NULL,
                    PRIMARY KEY (`event_id`),
                    KEY `event_time_idx` (`occurred_at_ms`, `event_id`),
                    KEY `event_player_idx` (`player_id`, `occurred_at_ms`, `event_id`),
                    KEY `event_session_idx` (`session_id`, `occurred_at_ms`, `event_id`),
                    KEY `event_server_idx` (`server`, `occurred_at_ms`, `event_id`),
                    KEY `event_source_idx` (`source`, `event`, `occurred_at_ms`, `event_id`),
                    KEY `event_subject_idx` (`subject`, `occurred_at_ms`, `event_id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin
                """.trimIndent(),
                "CREATE TABLE IF NOT EXISTS `$COMPACT_NODES_TABLE` LIKE `$NODES_TABLE`",
            ),
        )

        fun open(
            connectionConfig: SqlConnectionConfig,
            settings: PlayerTelemetrySettings,
            codec: PlayerTelemetryJsonCodec,
            gson: Gson,
            clockMillis: () -> Long,
            runtimeName: String,
        ): PlayerTelemetrySqlRepository =
            PlayerTelemetrySqlRepository(SqlRuntime.create(connectionConfig, runtimeName), settings, codec, gson, clockMillis)
    }
}
