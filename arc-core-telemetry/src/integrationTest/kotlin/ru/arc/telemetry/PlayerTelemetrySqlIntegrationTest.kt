package ru.arc.telemetry

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlMigration
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletionException

class PlayerTelemetrySqlIntegrationTest : FreeSpec({
    lateinit var mysql: MySqlTestService
    lateinit var repository: PlayerTelemetrySqlRepository
    lateinit var legacyUi: PlayerTelemetryEvent
    lateinit var legacyMenu: PlayerTelemetryEvent
    var mysqlStarted = false
    var repositoryCreated = false

    beforeSpec {
        mysql = MySqlTestService.start(MySqlTestSettings(database = "arc_player_telemetry_test"))
        mysqlStarted = true
        val endpoint = mysql.endpoint
        val connectionConfig = SqlConnectionConfig(
            host = endpoint.host,
            port = endpoint.port,
            database = endpoint.database,
            username = endpoint.username,
            password = endpoint.password,
            sslMode = SqlSslMode.DISABLED,
            minimumIdle = 0,
            maximumPoolSize = 3,
        )
        val gson = Gson()
        repository = PlayerTelemetrySqlRepository.open(
            connectionConfig = connectionConfig,
            settings = PlayerTelemetrySettings(retentionBatchSize = 1),
            codec = PlayerTelemetryJsonCodec(gson),
            gson = gson,
            clockMillis = System::currentTimeMillis,
            runtimeName = "telemetry-integration-test",
        )
        repositoryCreated = true
        val menuOnlyMigration = SqlMigration(
            version = 2,
            description = "create empty menu telemetry events table",
            statements = listOf("CREATE TABLE IF NOT EXISTS `arc_menu_events` LIKE `arc_player_events`"),
        )
        MySqlMigrator(repository.runtime.dataSource, "arc_player_telemetry")
            .migrate(listOf(PlayerTelemetrySqlRepository.LEGACY_MIGRATION, menuOnlyMigration))
        legacyUi = telemetryEvent(
            serverId("menu"), System.currentTimeMillis() - 2_000, source = "ui", event = "ui.open",
            schemaVersion = PlayerTelemetryEvent.LEGACY_SCHEMA_VERSION,
        )
        legacyMenu = legacyUi.copy(
            eventId = UUID.randomUUID().toString(), occurredAt = legacyUi.occurredAt + 1,
            source = "menu", event = "menu.view", attributes = mapOf("buttons" to "legacy custom"),
        )
        repository.insertBatch(listOf(legacyUi, legacyMenu)).join()
        repository.migrate().join()
    }

    afterSpec {
        try {
            if (repositoryCreated) repository.close()
        } finally {
            if (mysqlStarted) mysql.close()
        }
    }

    "migration is repeatable" {
        repository.migrate().join() shouldBe Unit
        MySqlMigrator(repository.runtime.dataSource, "arc_player_telemetry")
            .migrate(listOf(PlayerTelemetrySqlRepository.LEGACY_MIGRATION))
            .existingVersions shouldBe listOf(1, 2, 3)
    }

    "current menu and proxy events share compact storage and mixed retries preserve the archive" {
        fun rowsIn(table: String) = repository.runtime.executor.read { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM `$table` WHERE `server` = ?").use { statement ->
                statement.setString(1, legacyUi.server)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            }
        }.join()

        rowsIn("arc_player_events_v2") shouldBe 0
        rowsIn("arc_player_events") shouldBe 2
        val view = telemetryEvent(
            legacyUi.server,
            legacyMenu.occurredAt + 1,
            source = "menu",
            event = "menu.view",
            attributes = mapOf("buttons" to (0..127).joinToString(",") { "_pages/actions:button_$it=$it" }, "disabledSlots" to "0,127"),
        )
        val proxy = telemetryEvent(legacyUi.server, view.occurredAt + 1, source = "proxy", event = "connection.login")
        val batch = listOf(legacyUi, legacyMenu, view, proxy)
        repository.insertBatch(batch).join()
        repository.insertBatch(batch).join()
        rowsIn("arc_player_events") shouldBe 2
        rowsIn("arc_player_events_v2") shouldBe 2
        rowsIn("arc_menu_events") shouldBe 0

        shouldThrow<CompletionException> {
            repository.insertBatch(listOf(legacyUi.copy(eventId = UUID.randomUUID().toString()), view.copy(subject = "different"))).join()
        }
        rowsIn("arc_player_events") shouldBe 2
        val query = PlayerTelemetryQuery(
            fromInclusive = legacyUi.occurredAt,
            untilExclusive = proxy.occurredAt + 1,
            server = legacyUi.server,
        )
        repository.query(query).join().events shouldBe listOf(view, proxy)
        repository.query(query.copy(legacy = true)).join().events shouldBe listOf(legacyUi, legacyMenu)
        repository.query(query.copy(legacy = true, source = "menu", event = "menu.view")).join().events shouldBe listOf(legacyMenu)
        repository.query(query.copy(event = "menu.view")).join().events shouldBe listOf(view)
        repository.query(query.copy(source = "menu")).join().also {
            it.events shouldBe listOf(view)
            it.historyCovered shouldBe false
            it.coverageFrom shouldBe null
        }

        repository.summary(PlayerTelemetrySummaryQuery(query.fromInclusive, query.untilExclusive, server = query.server, source = "menu"))
            .join().also {
                it.rows.single().event shouldBe "menu.view"
                it.rows.single().count shouldBe 1L
                it.historyCovered shouldBe false
            }
    }

    "identical event retries are idempotent and conflicting content rolls back the batch" {
        val server = serverId("hash")
        val timestamp = System.currentTimeMillis() - 20_000
        val original = telemetryEvent(
            server = server,
            occurredAt = timestamp,
            playerId = UUID.randomUUID(),
            playerName = "TelemetryOne",
            sessionId = UUID.randomUUID(),
            subject = "quest:alpha",
            attributes = mapOf("result" to "complete"),
        ).copy(world = "spawn_world", x = 12.25, y = 64.5, z = -42.125, operationId = "quest:alpha:07")

        repository.insertBatch(listOf(original)).join()
        repository.insertBatch(listOf(original.copy(attributes = original.attributes.toMap()))).join()

        val wouldBeRolledBack = original.copy(eventId = UUID.randomUUID().toString(), occurredAt = timestamp + 1)
        val conflictingRetry = original.copy(subject = "quest:beta")
        shouldThrow<CompletionException> {
            repository.insertBatch(listOf(wouldBeRolledBack, conflictingRetry)).join()
        }

        val page = repository.query(
            PlayerTelemetryQuery(
                fromInclusive = timestamp,
                untilExclusive = timestamp + 10,
                server = server,
                playerName = "TelemetryOne",
                subject = "quest:alpha",
                limit = 10,
            ),
        ).join()
        page.events shouldHaveSize 1
        page.events.single() shouldBe original
        page.events.map(PlayerTelemetryEvent::eventId) shouldNotContain wouldBeRolledBack.eventId
    }

    "UUID, player, session, QA, half-open time filters and keyset pages compose" {
        val server = serverId("page")
        val from = System.currentTimeMillis() - 10_000
        val until = from + 1_000
        val playerId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val otherPlayerId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val sessionId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val otherSessionId = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val ids = (7..12).map { "${it.toString(16)}0000000-0000-0000-0000-000000000000" }

        repository.insertBatch(
            listOf(
                telemetryEvent(server, from, playerId = playerId, sessionId = sessionId, eventId = ids[1]),
                telemetryEvent(server, from, playerId = playerId, sessionId = sessionId, eventId = ids[0]),
                telemetryEvent(server, from + 500, playerId = playerId, sessionId = sessionId, qa = true, eventId = ids[2]),
                telemetryEvent(server, from + 500, playerId = playerId, sessionId = otherSessionId, eventId = ids[3]),
                telemetryEvent(server, until, playerId = playerId, sessionId = sessionId, eventId = ids[4]),
                telemetryEvent(server, from + 500, playerId = otherPlayerId, sessionId = sessionId, eventId = ids[5]),
            ),
        ).join()

        val first = repository.query(
            PlayerTelemetryQuery(
                fromInclusive = from,
                untilExclusive = until,
                playerId = playerId.toString(),
                sessionId = sessionId.toString(),
                server = server,
                limit = 1,
            ),
        ).join()
        first.events.map(PlayerTelemetryEvent::eventId) shouldBe listOf(ids[0])
        first.hasMore shouldBe true
        first.nextCursor shouldBe PlayerTelemetryCursor(from, ids[0])

        val second = repository.query(
            PlayerTelemetryQuery(
                fromInclusive = from,
                untilExclusive = until,
                playerId = playerId.toString(),
                sessionId = sessionId.toString(),
                server = server,
                limit = 1,
                cursor = first.nextCursor,
            ),
        ).join()
        second.events.map(PlayerTelemetryEvent::eventId) shouldBe listOf(ids[1])
        second.hasMore shouldBe false

        val includeQa = repository.query(
            PlayerTelemetryQuery(
                fromInclusive = from,
                untilExclusive = until,
                playerId = playerId.toString(),
                sessionId = sessionId.toString(),
                server = server,
                includeQa = true,
                limit = 10,
            ),
        ).join()
        includeQa.events.map(PlayerTelemetryEvent::eventId) shouldBe listOf(ids[0], ids[1], ids[2])
        includeQa.events.map(PlayerTelemetryEvent::eventId) shouldNotContain ids[3]
        includeQa.events.map(PlayerTelemetryEvent::eventId) shouldNotContain ids[4]
        includeQa.events.map(PlayerTelemetryEvent::eventId) shouldNotContain ids[5]
    }

    "summary counts action occurrences and distinct players in the compact table" {
        val server = serverId("summary")
        val timestamp = System.currentTimeMillis() - 5_000
        val player = UUID.randomUUID()
        val action = telemetryEvent(server, timestamp, playerId = player, source = "player", event = "interact",
            attributes = mapOf("count" to "3"))
        repository.insertBatch(
            listOf(
                action,
                action.copy(eventId = UUID.randomUUID().toString(), attributes = mapOf("count" to "2")),
                action.copy(eventId = UUID.randomUUID().toString(), playerId = UUID.randomUUID().toString(), attributes = emptyMap()),
                action.copy(eventId = UUID.randomUUID().toString(), source = "quest", event = "start"),
                action.copy(eventId = UUID.randomUUID().toString(), schemaVersion = PlayerTelemetryEvent.LEGACY_SCHEMA_VERSION),
            ),
        ).join()

        val query = PlayerTelemetrySummaryQuery(fromInclusive = timestamp, untilExclusive = timestamp + 10, server = server)
        val result = repository.summary(query).join()

        result.rows shouldHaveSize 2
        val interactions = result.rows.single { it.event == "interact" }
        interactions.utcDay shouldBe Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate().toString()
        interactions.count shouldBe 6L
        interactions.uniquePlayers shouldBe 2L
        result.rows.single { it.event == "start" }.count shouldBe 1L
        result.hasMore shouldBe false
        repository.summary(query.copy(legacy = true)).join().rows.single().count shouldBe 1L
    }

    "compact coverage requires known legacy nodes to switch and honors gaps" {
        val serverA = serverId("coverage-a")
        val serverB = serverId("coverage-b")
        val from = System.currentTimeMillis() - 30_000
        val until = from + 10_000
        repository.registerServer(serverA, from, null, 0).join()

        fun legacyHeartbeat(at: Long) = repository.runtime.executor.write { connection ->
            connection.prepareStatement(
                "INSERT INTO `arc_player_event_nodes` (`server`, `coverage_from_ms`, `last_seen_at_ms`, `coverage_through_ms`) " +
                    "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE `last_seen_at_ms` = ?",
            ).use { statement ->
                statement.setString(1, serverB)
                statement.setLong(2, from - 2)
                statement.setLong(3, at)
                statement.setLong(4, at)
                statement.setLong(5, at)
                statement.executeUpdate()
            }
        }.join()
        legacyHeartbeat(from - 1)

        fun coveragePage() = repository.query(
            PlayerTelemetryQuery(fromInclusive = from, untilExclusive = until, limit = 10),
        ).join()

        coveragePage().also {
            it.nodesObserved shouldBe 2L
            it.historyCovered shouldBe false
        }

        repository.advanceCoverage(serverA, until, null, 0).join()
        coveragePage().historyCovered shouldBe false

        repository.registerServer(serverB, from, null, 0).join()
        repository.advanceCoverage(serverB, until - 1, null, 0).join()
        coveragePage().also {
            it.oldestNodeCoverageThrough shouldBe until - 1
            it.historyCovered shouldBe false
        }

        repository.advanceCoverage(serverB, until, null, 0).join()
        coveragePage().also {
            it.nodesObserved shouldBe 2L
            it.oldestNodeCoverageThrough shouldBe until
            it.historyCovered shouldBe true
        }

        val archived = PlayerTelemetryQuery(from - 2, from - 1, server = serverB, legacy = true)
        repository.query(archived).join().historyCovered shouldBe true

        legacyHeartbeat(until + 1)
        coveragePage().historyCovered shouldBe false

        val gap = from + 5_000
        repository.advanceCoverage(serverA, until, gap, 1).join()
        coveragePage().also {
            it.coverageGapFrom shouldBe gap
            it.historyCovered shouldBe false
        }
    }

    "retention pruning is bounded, keeps the cutoff and leaves the archive" {
        val server = serverId("prune")
        val base = System.currentTimeMillis() - 60_000
        val cutoff = base + 200
        val events = listOf(
            telemetryEvent(server, base),
            telemetryEvent(server, base + 100),
            telemetryEvent(server, cutoff),
        )
        val archived = telemetryEvent(server, base - 1, schemaVersion = PlayerTelemetryEvent.LEGACY_SCHEMA_VERSION)
        repository.insertBatch(events + archived).join()

        repository.prune(cutoff).join() shouldBe 1
        repository.prune(cutoff).join() shouldBe 1
        repository.prune(cutoff).join() shouldBe 0

        val query = PlayerTelemetryQuery(fromInclusive = base - 1, untilExclusive = cutoff + 1, server = server, limit = 10)
        repository.query(query).join().events shouldBe listOf(events.last())
        repository.query(query.copy(legacy = true)).join().events shouldBe listOf(archived)
    }
})

private fun telemetryEvent(
    server: String,
    occurredAt: Long,
    eventId: String = UUID.randomUUID().toString(),
    playerId: UUID? = null,
    playerName: String? = null,
    sessionId: UUID? = null,
    source: String = "integration",
    event: String = "observed",
    subject: String? = null,
    qa: Boolean = false,
    attributes: Map<String, String> = emptyMap(),
    schemaVersion: Int = PlayerTelemetryEvent.CURRENT_SCHEMA_VERSION,
) = PlayerTelemetryEvent(
    eventId = eventId,
    occurredAt = occurredAt,
    server = server,
    playerId = playerId?.toString(),
    playerName = playerName,
    sessionId = sessionId?.toString(),
    source = source,
    event = event,
    subject = subject,
    qa = qa,
    attributes = attributes,
    schemaVersion = schemaVersion,
)

private fun serverId(label: String): String = "it-${label}-${UUID.randomUUID().toString().take(8)}"
