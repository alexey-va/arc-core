package ru.arc.network

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class NetworkIdentifiersTest : FreeSpec({
    "network player names" - {
        "accept Java and configured Floodgate names" {
            NetworkPlayerName.of("Steve").value shouldBe "Steve"
            NetworkPlayerName.of(".Bedrock_42").value shouldBe ".Bedrock_42"
        }

        "reject malformed, prefixed and control-bearing values fail closed" {
            listOf("", ".", "-Steve", "Player.Name", "a".repeat(17), "Steve\nAdmin").forEach { value ->
                NetworkPlayerName.parseOrNull(value) shouldBe null
                shouldThrow<IllegalArgumentException> { NetworkPlayerName.of(value) }
            }
        }

        "support an explicit non-default Floodgate prefix without weakening Java names" {
            val policy = NetworkPlayerNamePolicy(bedrockPrefixes = setOf("*"))
            NetworkPlayerName.of("*Bedrock", policy).value shouldBe "*Bedrock"
            NetworkPlayerName.parseOrNull(".Bedrock", policy) shouldBe null
            NetworkPlayerName.parseOrNull("Player.Name", policy) shouldBe null
        }

        "reject unsafe prefix policies before they reach routing code" {
            listOf(" ", "é", "A", "_").forEach { prefix ->
                shouldThrow<IllegalArgumentException> { NetworkPlayerNamePolicy(bedrockPrefixes = setOf(prefix)) }
            }
        }
    }

    "backend server ids" - {
        "default to the bounded lowercase RusCrafting namespace" {
            BackendServerId.of("classic_survival-2").value shouldBe "classic_survival-2"
            listOf("", "Spawn", "spawn.eu", "spawn;send", "a".repeat(33)).forEach { value ->
                BackendServerId.parseOrNull(value) shouldBe null
            }
        }

        "require an explicit wider policy" {
            val policy = BackendServerIdPolicy(maxLength = 48, allowUppercase = true, allowDot = true)
            BackendServerId.of("EU.Spawn-2", policy).value shouldBe "EU.Spawn-2"
            BackendServerId.parseOrNull("EU.Spawn-2") shouldBe null
        }
    }
})
