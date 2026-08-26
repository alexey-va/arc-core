package ru.arc.network

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class LeasedNetworkDirectoryTest : FreeSpec({
    "entries expire exactly at the local lease boundary" {
        var now = 1_000L
        val directory = LeasedNetworkDirectory<String, String>(leaseMillis = 100, maxEntries = 4) { now }

        directory.observe("spawn", "spawn", "ready") shouldBe NetworkLeaseObservation.Accepted(
            NetworkLeaseEntry("spawn", "spawn", "ready", null, 1_000, 1_100),
            replaced = false,
        )
        now = 1_099
        directory.get("spawn")?.value shouldBe "ready"
        now = 1_100
        directory.get("spawn") shouldBe null
    }

    "replacement renews a lease and stale sequences fail closed" {
        var now = 10L
        val directory = LeasedNetworkDirectory<String, String>(leaseMillis = 50, maxEntries = 2) { now }
        directory.observe("arena", "parkour", "one", sequence = 7)
        now = 20

        directory.observe("arena", "parkour", "old", sequence = 6) shouldBe
            NetworkLeaseObservation.Rejected(NetworkLeaseRejectionReason.STALE_SEQUENCE)
        directory.get("arena")?.value shouldBe "one"

        directory.observe("arena", "parkour", "two", sequence = 7) shouldBe NetworkLeaseObservation.Accepted(
            NetworkLeaseEntry("arena", "parkour", "two", 7, 20, 70),
            replaced = true,
        )
    }

    "capacity rejects a new active key without evicting a healthy peer" {
        val directory = LeasedNetworkDirectory<String, String>(leaseMillis = 100, maxEntries = 1) { 5L }
        directory.observe("spawn", "spawn", "ready")

        directory.observe("survival", "survival", "ready") shouldBe
            NetworkLeaseObservation.Rejected(NetworkLeaseRejectionReason.CAPACITY)
        directory.snapshot().map { it.key } shouldContainExactly listOf("spawn")
    }

    "expired entries free capacity before a new observation" {
        var now = 0L
        val directory = LeasedNetworkDirectory<String, String>(leaseMillis = 10, maxEntries = 1) { now }
        directory.observe("spawn", "spawn", "ready")
        now = 10

        directory.observe("survival", "survival", "ready") shouldBe NetworkLeaseObservation.Accepted(
            NetworkLeaseEntry("survival", "survival", "ready", null, 10, 20),
            replaced = false,
        )
    }

    "source observation time controls expiry without allowing an older refresh" {
        var now = 1_000L
        val directory = LeasedNetworkDirectory<String, String>(leaseMillis = 100, maxEntries = 2) { now }

        directory.observe("spawn", "spawn", "ready", observedAtMillis = 950) shouldBe
            NetworkLeaseObservation.Accepted(
                NetworkLeaseEntry("spawn", "spawn", "ready", null, 950, 1_050),
                replaced = false,
            )
        directory.observe("spawn", "spawn", "old", observedAtMillis = 949) shouldBe
            NetworkLeaseObservation.Rejected(NetworkLeaseRejectionReason.STALE_OBSERVATION)

        now = 1_050
        directory.get("spawn") shouldBe null
        directory.observe("old", "spawn", "stale", observedAtMillis = 949) shouldBe
            NetworkLeaseObservation.Rejected(NetworkLeaseRejectionReason.EXPIRED)
    }

    "wall-clock rollback clears routing and rejects the triggering observation" {
        var now = 100L
        val directory = LeasedNetworkDirectory<String, String>(leaseMillis = 10, maxEntries = 2) { now }
        directory.observe("spawn", "spawn", "ready")
        now = 99

        directory.observe("survival", "survival", "ready") shouldBe
            NetworkLeaseObservation.Rejected(NetworkLeaseRejectionReason.CLOCK_ROLLBACK)
        directory.size() shouldBe 0

        directory.observe("survival", "survival", "ready") shouldBe NetworkLeaseObservation.Accepted(
            NetworkLeaseEntry("survival", "survival", "ready", null, 99, 109),
            replaced = false,
        )
    }

    "expire returns stable keys only once" {
        var now = 1L
        val directory = LeasedNetworkDirectory<String, String>(leaseMillis = 5, maxEntries = 3) { now }
        directory.observe("a", "spawn", "one")
        directory.observe("b", "survival", "two")
        now = 6

        directory.expire() shouldContainExactly listOf("a", "b")
        directory.expire() shouldBe emptyList()
    }
})
