package ru.arc.dungeonparty

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class DungeonPartyWireTest : FreeSpec({
    "request round-trips a bounded candidate roster" {
        val leader = UUID.randomUUID()
        val request = DungeonPartyGatherRequest(UUID.randomUUID(), leader, listOf(leader, UUID.randomUUID()))
        DungeonPartyGatherRequest.decode(request.encode()) shouldBe request
    }

    "arrival round-trips the selected party in leader-first order" {
        val leader = UUID.randomUUID()
        val arrival = DungeonPartyGatherArrival(UUID.randomUUID(), leader, listOf(leader, UUID.randomUUID()))
        DungeonPartyGatherArrival.decode(arrival.encode()) shouldBe arrival
    }

    "rejects duplicate members and trailing bytes" {
        val leader = UUID.randomUUID()
        shouldThrow<IllegalArgumentException> {
            DungeonPartyGatherRequest(UUID.randomUUID(), leader, listOf(leader, leader))
        }
        val arrival = DungeonPartyGatherArrival(UUID.randomUUID(), leader, listOf(leader))
        shouldThrow<IllegalArgumentException> {
            DungeonPartyGatherArrival.decode(arrival.encode() + 1)
        }
    }

    "rejects a roster where the leader is not first" {
        val leader = UUID.randomUUID()
        shouldThrow<IllegalArgumentException> {
            DungeonPartyGatherArrival(UUID.randomUUID(), leader, listOf(UUID.randomUUID(), leader))
        }
    }
})
