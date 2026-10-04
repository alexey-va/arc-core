package ru.arc.telemetry

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlMigration
import ru.arc.sql.SqlRuntime
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
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
            MySqlMigrator(runtime.dataSource, MIGRATION_NAMESPACE).migrate(listOf(MIGRATION))
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

    /** INSERT IGNORE is followed by a hash readback, so only an identical event ID is treated as a replay. */
    override fun insertBatch(events: List<PlayerTelemetryEvent>): CompletableFuture<Unit> {
        require(events.isNotEmpty() && events.size <= settings.batchSize) { "SQL telemetry batch size is invalid" }
        val snapshots = events.map(PlayerTelemetryEvent::validatedCopy)
        require(snapshots.map(PlayerTelemetryEvent::eventId).toSet().size == snapshots.size) {
            "SQL telemetry batch contains duplicate event ids"
        }
        val hashes = snapshots.associate { it.eventId to codec.hash(it) }
        return runtime.executor.transaction { connection ->
            connection.prepareStatement(INSERT_EVENT).use { statement ->
                snapshots.forEach { event ->
                    bind(statement, event, hashes.getValue(event.eventId), clockMillis())
                    statement.addBatch()
                }
                statement.executeBatch().forEach { changed ->
                    check(changed != Statement.EXECUTE_FAILED) { "SQL telemetry batch insert failed" }
                }
            }
            val hashesSql = SELECT_HASHES + snapshots.joinToString(",", "(", ")") { "?" }
            verifyHashes(connection.prepareStatement(hashesSql).use { statement ->
                snapshots.map(PlayerTelemetryEvent::eventId).forEachIndexed { index, id -> statement.setString(index + 1, id) }
                statement.executeQuery().use { result ->
                    buildMap { while (result.next()) put(result.getString("event_id"), result.getString("event_hash")) }
                }
            }, hashes)
        }
    }

    override fun query(request: PlayerTelemetryQuery): CompletableFuture<PlayerTelemetryPage> =
        runtime.executor.read { connection ->
            val coverage = coverage(connection, request.server)
            val where = where(request)
            val cursorSql = if (request.cursor == null) "" else
                " AND (`occurred_at_ms` > ? OR (`occurred_at_ms` = ? AND `event_id` > ?))"
            val sql = "SELECT $EVENT_COLUMNS FROM `$EVENTS_TABLE` WHERE $where$cursorSql " +
                "ORDER BY `occurred_at_ms` ASC, `event_id` ASC LIMIT ?"
            val rows = connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                var index = bindFilters(statement, request)
                request.cursor?.let { cursor ->
                    statement.setLong(index++, cursor.occurredAt)
                    statement.setLong(index++, cursor.occurredAt)
                    statement.setString(index++, cursor.eventId.lowercase())
                }
                statement.setInt(index, request.limit + 1)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(readEvent(result)) } }
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
            val coverage = coverage(connection, request.server)
            val where = where(request)
            val sql =
                "SELECT DATE_FORMAT(FROM_UNIXTIME(`occurred_at_ms` / 1000), '%Y-%m-%d') AS `utc_day`, " +
                    "`server`, `source`, `event`, COUNT(*) AS `event_count`, " +
                    "COUNT(DISTINCT `player_id`) AS `unique_players` FROM `$EVENTS_TABLE` WHERE $where " +
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
        connection.prepareStatement("DELETE FROM `$EVENTS_TABLE` WHERE `occurred_at_ms` < ? ORDER BY `occurred_at_ms` LIMIT ?").use { statement ->
            statement.setLong(1, cutoff)
            statement.setInt(2, settings.retentionBatchSize)
            statement.executeUpdate()
        }
    }

    private fun coverage(connection: java.sql.Connection, server: String?): PlayerTelemetryCoverage {
        val predicate = if (server == null) "" else " WHERE `server` = ?"
        val retainedFrom = clockMillis() - settings.retentionDays * MILLIS_PER_DAY
        return connection.prepareStatement(
            "SELECT MAX(`coverage_from_ms`) AS `coverage_from`, MIN(`coverage_gap_from_ms`) AS `coverage_gap_from`, " +
                "COUNT(*) AS `nodes_observed`, MIN(`last_seen_at_ms`) AS `oldest_node_last_seen_at`, " +
                "SUM(`coverage_through_ms` IS NOT NULL) AS `nodes_with_coverage_through`, " +
                "MIN(`coverage_through_ms`) AS `oldest_node_coverage_through` " +
                "FROM `$NODES_TABLE`$predicate",
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
        request.playerId?.let { statement.setString(index++, it.lowercase()) }
        request.playerName?.let { statement.setString(index++, it) }
        request.sessionId?.let { statement.setString(index++, it.lowercase()) }
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
        request.playerId?.let { statement.setString(index++, it.lowercase()) }
        request.playerName?.let { statement.setString(index++, it) }
        request.sessionId?.let { statement.setString(index++, it.lowercase()) }
        request.server?.let { statement.setString(index++, it) }
        request.source?.let { statement.setString(index++, it) }
        request.event?.let { statement.setString(index++, it) }
        request.subject?.let { statement.setString(index++, it) }
        return index
    }

    private fun bind(statement: PreparedStatement, event: PlayerTelemetryEvent, hash: String, ingestedAt: Long) {
        statement.setString(1, event.eventId)
        statement.setInt(2, event.schemaVersion)
        statement.setLong(3, event.occurredAt)
        statement.setLong(4, ingestedAt)
        statement.setString(5, event.server)
        statement.setString(6, event.playerId)
        statement.setString(7, event.playerName)
        statement.setString(8, event.sessionId)
        statement.setString(9, event.source)
        statement.setString(10, event.event)
        statement.setString(11, event.subject)
        statement.setString(12, event.operationId)
        statement.setString(13, event.world)
        setNullableDouble(statement, 14, event.x)
        setNullableDouble(statement, 15, event.y)
        setNullableDouble(statement, 16, event.z)
        statement.setBoolean(17, event.qa)
        statement.setString(18, codec.encodeAttributes(event))
        statement.setString(19, hash)
    }

    private fun setNullableDouble(statement: PreparedStatement, index: Int, value: Double?) {
        if (value == null) statement.setNull(index, Types.DOUBLE) else statement.setDouble(index, value)
    }

    private fun readEvent(result: ResultSet): PlayerTelemetryEvent =
        PlayerTelemetryEvent(
            eventId = result.getString("event_id"),
            occurredAt = result.getLong("occurred_at_ms"),
            server = result.getString("server"),
            playerId = result.getString("player_id"),
            playerName = result.getString("player_name"),
            sessionId = result.getString("session_id"),
            source = result.getString("source"),
            event = result.getString("event"),
            subject = result.getString("subject"),
            operationId = result.getString("operation_id"),
            world = result.getString("world"),
            x = result.getDouble("x").takeUnless { result.wasNull() },
            y = result.getDouble("y").takeUnless { result.wasNull() },
            z = result.getDouble("z").takeUnless { result.wasNull() },
            qa = result.getBoolean("qa"),
            attributes = gson.fromJson(result.getString("attributes_json"), attributesType) ?: emptyMap(),
            schemaVersion = result.getInt("schema_version"),
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
        private const val NODES_TABLE = "arc_player_event_nodes"
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val QUERY_TIMEOUT_SECONDS = 3
        private const val EVENT_COLUMNS =
            "`event_id`, `schema_version`, `occurred_at_ms`, `server`, `player_id`, `player_name`, `session_id`, " +
                "`source`, `event`, `subject`, `operation_id`, `world`, `x`, `y`, `z`, `qa`, `attributes_json`"
        private const val INSERT_EVENT =
            "INSERT IGNORE INTO `$EVENTS_TABLE` (`event_id`, `schema_version`, `occurred_at_ms`, `ingested_at_ms`, " +
                "`server`, `player_id`, `player_name`, `session_id`, `source`, `event`, `subject`, `operation_id`, " +
                "`world`, `x`, `y`, `z`, `qa`, `attributes_json`, `event_hash`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        private const val SELECT_HASHES =
            "SELECT `event_id`, `event_hash` FROM `$EVENTS_TABLE` WHERE `event_id` IN "
        private const val UPSERT_NODE =
            "INSERT INTO `$NODES_TABLE` (`server`, `coverage_from_ms`, `last_seen_at_ms`, `coverage_gap_from_ms`, `known_drops`) " +
                "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE `coverage_from_ms` = LEAST(`coverage_from_ms`, VALUES(`coverage_from_ms`)), " +
                "`last_seen_at_ms` = VALUES(`last_seen_at_ms`), " +
                "`coverage_gap_from_ms` = CASE WHEN VALUES(`coverage_gap_from_ms`) IS NULL THEN `coverage_gap_from_ms` " +
                "WHEN `coverage_gap_from_ms` IS NULL THEN VALUES(`coverage_gap_from_ms`) " +
                "ELSE LEAST(`coverage_gap_from_ms`, VALUES(`coverage_gap_from_ms`)) END, " +
                "`known_drops` = GREATEST(`known_drops`, VALUES(`known_drops`))"
        private const val ADVANCE_NODE_COVERAGE =
            "UPDATE `$NODES_TABLE` SET `coverage_through_ms` = CASE WHEN `coverage_through_ms` IS NULL THEN ? " +
                "ELSE GREATEST(`coverage_through_ms`, ?) END, " +
                "`coverage_gap_from_ms` = CASE WHEN ? IS NULL THEN `coverage_gap_from_ms` " +
                "WHEN `coverage_gap_from_ms` IS NULL THEN ? ELSE LEAST(`coverage_gap_from_ms`, ?) END, " +
                "`known_drops` = GREATEST(`known_drops`, ?) WHERE `server` = ?"
        private val MIGRATION = SqlMigration(
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
