package ru.arc.redis.safety

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture

class RecentMessageDeduplicatorTest : FreeSpec({
    "accepts one concurrent claimant and rejects every replay" {
        val guard = RecentMessageDeduplicator(ttlMillis = 1_000)
        val results = (1..64).map {
            CompletableFuture.supplyAsync { guard.claim("event:one", 100) }
        }.map(CompletableFuture<MessageClaimResult>::join)
        results.filter { it == MessageClaimResult.ACCEPTED }.shouldHaveSize(1)
        results.filter { it == MessageClaimResult.DUPLICATE }.shouldHaveSize(63)
    }

    "expires exactly at the TTL boundary and fails closed on clock rollback" {
        val guard = RecentMessageDeduplicator(ttlMillis = 100)
        guard.claim("event:one", 1_000) shouldBe MessageClaimResult.ACCEPTED
        guard.claim("event:one", 999) shouldBe MessageClaimResult.DUPLICATE
        guard.claim("event:one", 1_099) shouldBe MessageClaimResult.DUPLICATE
        guard.claim("event:one", 1_100) shouldBe MessageClaimResult.ACCEPTED
    }

    "bounds memory and accepts a new id after expiry cleanup" {
        val guard = RecentMessageDeduplicator(ttlMillis = 10, maxEntries = 2)
        guard.claim("one", 0) shouldBe MessageClaimResult.ACCEPTED
        guard.claim("two", 0) shouldBe MessageClaimResult.ACCEPTED
        guard.claim("three", 0) shouldBe MessageClaimResult.CAPACITY_EXCEEDED
        guard.claim("three", 10) shouldBe MessageClaimResult.ACCEPTED
        guard.size() shouldBe 1
    }

    "rejects unsafe ids before touching the cache" {
        val guard = RecentMessageDeduplicator(ttlMillis = 10)
        shouldThrow<IllegalArgumentException> { guard.claim("event\nadmin", 0) }
        guard.size() shouldBe 0
    }
})
