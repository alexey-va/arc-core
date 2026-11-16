package ru.arc.telemetry

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlConnectionConfig
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
        MySqlMigrator(repository.runtime.dataSource, "arc_player_telemetry")
            .migrate(listOf(PlayerTelemetrySqlRepository.LEGACY_MIGRATION))
        legacyUi = telemetryEvent(serverId("menu"), System.currentTimeMillis() - 2_000, source = "ui", event = "ui.open")
        repository.insertBatch(listOf(legacyUi)).join()
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
            .existingVersions shouldBe listOf(1, 2)
    }

    "menu writes route separately from legacy UI and mixed retries remain atomic" {
        fun idsIn(table: String) = repository.runtime.executor.read { connection ->
            connection.prepareStatement("SELECT `event_id` FROM `$table` WHERE `server` = ?").use { statement ->
                statement.setString(1, legacyUi.server)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getString(1)) } }
            }
        }.join()

        idsIn("arc_menu_events") shouldBe emptyList()
        idsIn("arc_player_events") shouldBe listOf(legacyUi.eventId)
        val view = telemetryEvent(
            legacyUi.server,
            legacyUi.occurredAt + 1,
            source = "menu",
            event = "menu.view",
            attributes = mapOf("buttons" to (0..127).joinToString(",") { "_pages/actions:button_$it=$it" }, "disabledSlots" to "0,127"),
        )
        val pair = listOf(legacyUi, view)
        repository.insertBatch(pair).join()
        repository.insertBatch(pair).join()
        idsIn("arc_player_events") shouldBe listOf(legacyUi.eventId)
        idsIn("arc_menu_events") shouldBe listOf(view.eventId)

        shouldThrow<CompletionException> {
            repository.insertBatch(listOf(legacyUi.copy(eventId = UUID.randomUUID().toString()), view.copy(subject = "different"))).join()
        }
        idsIn("arc_player_events") shouldBe listOf(legacyUi.eventId)
        val query = PlayerTelemetryQuery(
            fromInclusive = legacyUi.occurredAt,
            untilExclusive = view.occurredAt + 1,
            server = legacyUi.server,
        )
        repository.query(query).join().events shouldBe listOf(legacyUi)
        repository.query(query.copy(source = "ui")).join().events shouldBe listOf(legacyUi)
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
        )

        repository.insertBatch(listOf(original)).join()
        repository.insertBatch(listOf(original.copy(attributes = original.attributes.toMap()))).join()

        val wouldBeRolledBack = telemetryEvent(server = server, occurredAt = timestamp + 1)
        val conflictingRetry = original.copy(subject = "quest:beta")
        shouldThrow<CompletionException> {
            repository.insertBatch(listOf(wouldBeRolledBack, conflictingRetry)).join()
        }

        val page = repository.query(
            PlayerTelemetryQuery(
                fromInclusive = timestamp,
                untilExclusive = timestamp + 10,
                server = server,
                limit = 10,
            ),
        ).join()
        page.events shouldHaveSize 1
        page.events.map(PlayerTelemetryEvent::eventId) shouldBe listOf(original.eventId)
        page.events.single().attributes shouldBe mapOf("result" to "complete")
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
        val ids = (1..6).map { UUID.fromString("00000000-0000-0000-0000-${it.toString().padStart(12, '0')}").toString() }

        repository.insertBatch(
            listOf(
                telemetryEvent(server, from, playerId = playerId, sessionId = sessionId, eventId = ids[0]),
                telemetryEvent(server, from, playerId = playerId, sessionId = sessionId, eventId = ids[1]),
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

    "summary groups by UTC day and reports counts and unique players" {
        val server = serverId("summary")
        val timestamp = System.currentTimeMillis() - 5_000
        repository.insertBatch(
            listOf(
                telemetryEvent(server, timestamp, playerId = UUID.randomUUID(), source = "quest", event = "complete"),
                telemetryEvent(server, timestamp + 1, playerId = UUID.randomUUID(), source = "quest", event = "complete"),
                telemetryEvent(server, timestamp + 2, playerId = UUID.randomUUID(), source = "quest", event = "start"),
            ),
        ).join()

        val result = repository.summary(
            PlayerTelemetrySummaryQuery(
                fromInclusive = timestamp,
                untilExclusive = timestamp + 10,
                server = server,
                limit = 10,
            ),
        ).join()

        result.rows shouldHaveSize 2
        val complete = result.rows.single { it.event == "complete" }
        complete.utcDay shouldBe Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate().toString()
        complete.count shouldBe 2L
        complete.uniquePlayers shouldBe 2L
        result.rows.single { it.event == "start" }.count shouldBe 1L
        result.hasMore shouldBe false
    }

    "coverage metadata requires every registered node watermark and honors gaps" {
        val serverA = serverId("coverage-a")
        val serverB = serverId("coverage-b")
        val from = System.currentTimeMillis() - 30_000
        val until = from + 10_000
        repository.registerServer(serverA, from, null, 0).join()
        repository.registerServer(serverB, from, null, 0).join()

        fun coveragePage() = repository.query(
            PlayerTelemetryQuery(fromInclusive = from, untilExclusive = until, limit = 10),
        ).join()

        coveragePage().also {
            it.nodesObserved shouldBe 2L
            it.historyCovered shouldBe false
        }

        repository.advanceCoverage(serverA, until, null, 0).join()
        coveragePage().historyCovered shouldBe false

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

        val gap = from + 5_000
        repository.advanceCoverage(serverA, until, gap, 1).join()
        coveragePage().also {
            it.coverageGapFrom shouldBe gap
            it.historyCovered shouldBe false
        }
    }

    "retention pruning is bounded and keeps the cutoff timestamp" {
        val server = serverId("prune")
        val base = System.currentTimeMillis() - 60_000
        val cutoff = base + 200
        val events = listOf(
            telemetryEvent(server, base),
            telemetryEvent(server, base + 100),
            telemetryEvent(server, cutoff),
        )
        val menuEvents = listOf(
            telemetryEvent(server, base, source = "menu", event = "menu.view"),
            telemetryEvent(server, cutoff, source = "menu", event = "menu.end"),
        )
        repository.insertBatch(events + menuEvents).join()

        repository.prune(cutoff).join() shouldBe 2
        repository.prune(cutoff).join() shouldBe 1
        repository.prune(cutoff).join() shouldBe 0

        val query = PlayerTelemetryQuery(fromInclusive = base, untilExclusive = cutoff + 1, server = server, limit = 10)
        repository.query(query).join().events shouldBe listOf(events.last())
        repository.query(query.copy(source = "menu")).join().events shouldBe listOf(menuEvents.last())
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
)

private fun serverId(label: String): String = "it-${label}-${UUID.randomUUID().toString().take(8)}"
