package ru.arc.xaction

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.UUID

class XConditionTest : FreeSpec({

    "factory methods" - {
        "ofPermission" {
            XCondition.ofPermission("arc.test").permission shouldBe "arc.test"
        }
        "ofServerName" {
            XCondition.ofServerName("spawn").serverName shouldBe "spawn"
        }
        "ofPlayerName" {
            XCondition.ofPlayerName("Steve").playerName shouldBe "Steve"
        }
        "ofPlayerUuid" {
            val uuid = UUID.randomUUID()
            XCondition.ofPlayerUuid(uuid).playerUuid shouldBe uuid
        }
    }

    "defaults" {
        val empty = XCondition()
        empty.playerName shouldBe null
        empty.playerUuid shouldBe null
        empty.permission shouldBe null
        empty.serverName shouldBe null
        empty.placeholders shouldBe null
    }

    "data class equality" {
        val a = XCondition.ofPermission("arc.a")
        val b = XCondition.ofPermission("arc.a")
        val c = XCondition.ofPermission("arc.b")
        a shouldBe b
        a shouldNotBe c
    }

    "copy preserves unspecified fields" {
        val original = XCondition(
            playerName = "Alice",
            permission = "arc.board",
            placeholders = mapOf("%key%" to "val"),
        )
        val copied = original.copy(playerName = "Bob")
        copied.playerName shouldBe "Bob"
        copied.permission shouldBe "arc.board"
        copied.placeholders shouldBe mapOf("%key%" to "val")
    }
})
