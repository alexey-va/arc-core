package ru.arc.redis.activity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import java.util.UUID

class PlayerActivityStoreTest :
    StringSpec({
        "records and decodes network activity with a persistent coverage boundary" {
            val redis = InMemoryRedis()
            val playerId = UUID.fromString("5b8e06f1-11ef-4d83-a290-2238632a16d1")
            val store = PlayerActivityStore(redis) { 1_000L }

            store.ensureCoverageStartedAt().get() shouldBe 1_000L
            store.markSeen(playerId, 2_000L).get()

            store.load().get() shouldBe
                PlayerActivitySnapshot(
                    coverageStartedAt = 1_000L,
                    lastSeen = mapOf(playerId to 2_000L),
                    invalidEntries = 0,
                )
        }

        "does not move the coverage boundary on a later startup" {
            val redis = InMemoryRedis()
            val first = PlayerActivityStore(redis) { 1_000L }
            val second = PlayerActivityStore(redis) { 9_000L }

            first.ensureCoverageStartedAt().get()

            second.ensureCoverageStartedAt().get() shouldBe 1_000L
        }

        "keeps same-player writes ordered" {
            val redis = InMemoryRedis().apply { saveDelay = 20L }
            val playerId = UUID.fromString("5b8e06f1-11ef-4d83-a290-2238632a16d1")
            val store = PlayerActivityStore(redis)

            val earlier = store.markSeen(playerId, 1_000L)
            val later = store.markSeen(playerId, 2_000L)
            earlier.get()
            later.get()

            store.load().get().lastSeen[playerId] shouldBe 2_000L
        }

        "never lets an older observation overwrite a newer one" {
            val redis = InMemoryRedis().apply { saveDelay = 20L }
            val playerId = UUID.fromString("5b8e06f1-11ef-4d83-a290-2238632a16d1")
            val store = PlayerActivityStore(redis)

            val newer = store.markSeen(playerId, 2_000L)
            val older = store.markSeen(playerId, 1_000L)
            newer.get()
            older.get()

            store.load().get().lastSeen[playerId] shouldBe 2_000L
        }

        "counts malformed metadata and player entries without exposing them" {
            val redis = InMemoryRedis()
            redis.setHash(
                PlayerActivityStore.REDIS_KEY,
                mapOf(
                    "_coverage_started_at" to "broken",
                    "not-a-uuid" to "1000",
                    "5b8e06f1-11ef-4d83-a290-2238632a16d1" to "negative",
                ),
            )

            val snapshot = PlayerActivityStore(redis).load().get()

            snapshot.coverageStartedAt shouldBe null
            snapshot.lastSeen shouldBe emptyMap()
            snapshot.invalidEntries shouldBe 3
        }

        "rejects non-positive timestamps before Redis writes" {
            val store = PlayerActivityStore(InMemoryRedis())
            val playerId = UUID.fromString("5b8e06f1-11ef-4d83-a290-2238632a16d1")

            shouldThrow<IllegalArgumentException> { store.markSeen(playerId, 0L) }
            shouldThrow<IllegalArgumentException> { store.ensureCoverageStartedAt(-1L) }
        }
    })
