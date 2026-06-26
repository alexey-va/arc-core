package ru.arc.paper.player

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID

class BukkitPlayerLookupTest : FreeSpec({

    lateinit var server: ServerMock
    lateinit var plugin: TestPaperPlugin
    val lookup = BukkitPlayerLookup()

    beforeSpec {
        MockBukkit.mock()
        server = MockBukkit.getMock()!!
        plugin = MockBukkit.load(TestPaperPlugin::class.java)
    }

    afterSpec {
        MockBukkit.unmock()
    }

    "findUuid" - {
        "returns uuid for online player" {
            val player = server.addPlayer("Steve")
            lookup.findUuid("Steve") shouldBe player.uniqueId
        }
        "returns null for unknown player" {
            lookup.findUuid("NobodyHere").shouldBeNull()
        }
    }

    "findName" - {
        "returns name for online player" {
            val player = server.addPlayer("Alice")
            lookup.findName(player.uniqueId) shouldBe "Alice"
        }
    }

    "isOnline" - {
        "true when player is online" {
            val player = server.addPlayer("Online")
            lookup.isOnline(player.uniqueId) shouldBe true
        }
        "false for random uuid" {
            lookup.isOnline(UUID.randomUUID()) shouldBe false
        }
    }

    "onlineNames" {
        server.addPlayer("One")
        server.addPlayer("Two")
        lookup.onlineNames() shouldContain "One"
        lookup.onlineNames() shouldContain "Two"
    }

    "hasPermission" - {
        "true when online player has permission" {
            val player = server.addPlayer("Perm") as PlayerMock
            player.addAttachment(plugin, "arc.test.perm", true)
            lookup.hasPermission(player.uniqueId, "arc.test.perm") shouldBe true
        }
        "false when player is offline" {
            lookup.hasPermission(UUID.randomUUID(), "any.perm") shouldBe false
        }
    }
})
