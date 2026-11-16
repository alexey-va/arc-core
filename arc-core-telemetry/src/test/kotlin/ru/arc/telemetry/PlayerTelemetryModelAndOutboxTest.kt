package ru.arc.telemetry

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID

class PlayerTelemetryModelAndOutboxTest : FunSpec({
    fun event(id: String = UUID.randomUUID().toString(), at: Long = 1_800_000_000_000L) =
        PlayerTelemetryEvent(
            eventId = id,
            occurredAt = at,
            server = "spawn",
            playerId = "37e2a8ab-85c2-4d9a-88f1-5b52cad487f0",
            playerName = "Player_7",
            sessionId = "6c1372e2-ea9c-44d6-a2b5-35b311acda66",
            source = "arc.menu",
            event = "click",
            subject = "mounts",
            operationId = "visit:abc-123",
            world = "spawn_world",
            x = 12.0,
            y = 64.0,
            z = -4.0,
            attributes = mapOf("button" to "equip", "rawSlot" to "14"),
        )

    fun outbox(root: java.nio.file.Path, settings: PlayerTelemetrySettings = PlayerTelemetrySettings()) =
        FilePlayerTelemetryOutbox(root, settings, PlayerTelemetryJsonCodec(Gson()), Gson())

    test("events snapshot ordered safe attributes and keep operation IDs separate from event IDs") {
        val id = UUID.randomUUID().toString()
        val first = event(id).copy(attributes = linkedMapOf("zKey" to "two", "aKey" to "one"))
        val second = event(id).copy(attributes = linkedMapOf("aKey" to "one", "zKey" to "two"))
        val separateObservation = event().copy(operationId = first.operationId)
        val codec = PlayerTelemetryJsonCodec(Gson())

        first.validatedCopy().eventId shouldBe id
        separateObservation.operationId shouldBe first.operationId
        (separateObservation.eventId == first.eventId) shouldBe false
        codec.hash(first) shouldBe codec.hash(second)
        first.eventId shouldBe id
    }

    test("rejects raw free text and incomplete location coordinates") {
        shouldThrow<IllegalArgumentException> {
            event().copy(attributes = mapOf("chatText" to "hello! player"))
        }
        shouldThrow<IllegalArgumentException> {
            PlayerTelemetryEvent(
                occurredAt = 1_800_000_000_000L,
                server = "spawn",
                source = "arc",
                event = "click",
                world = "spawn_world",
                x = 1.0,
            )
        }
    }

    test("compact attributes round-trip small action fields and a compressed menu render") {
        val action = mapOf("cancelled" to "false", "count" to "7")
        val buttons = (0 until 128).joinToString(",") { "inventory_region_button_$it=$it" }
        val render = mapOf("buttons" to buttons, "revision" to "0123456789ab")

        PlayerTelemetryCompactAttributes.encode(emptyMap()) shouldBe null
        PlayerTelemetryCompactAttributes.decode(null) shouldBe emptyMap()
        PlayerTelemetryCompactAttributes.decode(PlayerTelemetryCompactAttributes.encode(action)) shouldBe action
        val packed = requireNotNull(PlayerTelemetryCompactAttributes.encode(render))
        PlayerTelemetryCompactAttributes.decode(packed) shouldBe render
        (packed.size < buttons.length / 2) shouldBe true

        val counted = event().copy(source = "player", event = "interact", attributes = action)
        counted.occurrences shouldBe 7
        counted.copy(schemaVersion = PlayerTelemetryEvent.LEGACY_SCHEMA_VERSION).occurrences shouldBe 1
        counted.copy(source = "proxy").occurrences shouldBe 1
    }

    test("v1 journal records remain readable without reusing old coverage") {
        val root = Files.createTempDirectory("arc-player-telemetry-cutover-")
        val folder = root.resolve("player-telemetry")
        val previousCoverage = """{"schemaVersion":1,"coverageFrom":1,"currentRunStartedAt":1,"coverageGapFrom":1,"knownDropCount":5,"cleanShutdown":false}"""
        Files.createDirectories(folder)
        Files.writeString(folder.resolve("coverage.json"), previousCoverage)
        val now = 1_800_000_001_000L
        val legacy = event(at = now - 10).copy(schemaVersion = PlayerTelemetryEvent.LEGACY_SCHEMA_VERSION)
        val current = event(at = now + 10)
        outbox(root).also {
            val initial = it.recover(now)
            initial.coverageFrom shouldBe now
            initial.coverageGapFrom shouldBe null
            it.commit("mixed", listOf(legacy, current))
            it.markCleanShutdown(now + 20)
        }

        outbox(root).also {
            it.recover(now + 30).coverageFrom shouldBe now
            it.next(now + 31)?.events shouldBe listOf(legacy, current)
        }
        Files.readString(folder.resolve("coverage.json")) shouldBe previousCoverage
    }

    test("outbox recovery preserves known drops, stable event IDs and exact acknowledgement") {
        val root = Files.createTempDirectory("arc-player-telemetry-outbox-")
        val first = outbox(root)
        first.recover(1_800_000_000_000L).coverageFrom shouldBe 1_800_000_000_000L
        val original = event(at = 1_800_000_000_010L)
        first.commit("batch-a", listOf(original))
        first.noteGap(1_800_000_000_020L, 3)
        first.markCleanShutdown(1_800_000_000_030L)

        val recovered = outbox(root)
        val snapshot = recovered.recover(1_800_000_000_040L)
        snapshot.knownDropCount shouldBe 3L
        snapshot.coverageGapFrom shouldBe 1_800_000_000_020L
        val replay = recovered.next(1_800_000_000_041L)
        replay?.events shouldBe listOf(original)
        recovered.acknowledge(requireNotNull(replay)) shouldBe
            ru.arc.persistence.DurableAcknowledgementOutcome.ACKNOWLEDGED
        recovered.acknowledge(requireNotNull(replay)) shouldBe
            ru.arc.persistence.DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED
        recovered.stats().eventCount shouldBe 0L
    }

    test("corrupt records remain on disk while valid records can continue") {
        val root = Files.createTempDirectory("arc-player-telemetry-corrupt-")
        val first = outbox(root)
        first.recover(1_800_000_000_000L)
        first.commit("a-corrupt", listOf(event(at = 1_800_000_000_010L)))
        val valid = event(at = 1_800_000_000_020L)
        first.commit("b-valid", listOf(valid))
        first.markCleanShutdown(1_800_000_000_030L)
        val corruptPath = root.resolve("player-telemetry/outbox/a-corrupt.json")
        Files.writeString(corruptPath, "not-json")

        val recovered = outbox(root)
        val snapshot = recovered.recover(1_800_000_000_040L)
        snapshot.corruptRecordCount shouldBe 1
        snapshot.eventCount shouldBe 1L
        snapshot.coverageGapFrom shouldBe snapshot.coverageFrom
        recovered.next(1_800_000_000_041L)?.events shouldBe listOf(valid)
        Files.exists(corruptPath) shouldBe true
    }
})
