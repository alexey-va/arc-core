package ru.arc.paper.player

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import java.util.UUID

class BukkitPlayerLookupTest : FreeSpec({

    lateinit var paper: MockBukkitTestRuntime
    lateinit var plugin: TestPaperPlugin
    val lookup = BukkitPlayerLookup()

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        plugin = paper.loadPlugin<TestPaperPlugin>()
    }

    afterEach {
        paper.close()
    }

    "findUuid" - {
        "returns uuid for online player" {
            val player = paper.addPlayer("Steve")
            lookup.findUuid("Steve") shouldBe player.uniqueId
        }
        "returns null for unknown player" {
            lookup.findUuid("NobodyHere").shouldBeNull()
        }
    }

    "findName" - {
        "returns name for online player" {
            val player = paper.addPlayer("Alice")
            lookup.findName(player.uniqueId) shouldBe "Alice"
        }
    }

    "isOnline" - {
        "true when player is online" {
            val player = paper.addPlayer("Online")
            lookup.isOnline(player.uniqueId) shouldBe true
        }
        "false for random uuid" {
            lookup.isOnline(UUID.randomUUID()) shouldBe false
        }
    }

    "onlineNames" {
        paper.addPlayer("One")
        paper.addPlayer("Two")
        lookup.onlineNames() shouldContain "One"
        lookup.onlineNames() shouldContain "Two"
    }

    "hasPermission" - {
        "true when online player has permission" {
            val player = paper.addPlayer("Perm")
            player.addAttachment(plugin, "arc.test.perm", true)
            lookup.hasPermission(player.uniqueId, "arc.test.perm") shouldBe true
        }
        "false when player is offline" {
            lookup.hasPermission(UUID.randomUUID(), "any.perm") shouldBe false
        }
    }
})
