package ru.arc.ai.npc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe
import ru.arc.ai.config.TestLlmModuleConfig
import ru.arc.redis.InMemoryRedis
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class NpcChatRpcTest : FreeSpec({
    "request and response round trip" {
        val redis = InMemoryRedis()
        val config = TestLlmModuleConfig(npcChatTimeoutMs = 1_000)
        val server = NpcChatRpcServer(redis, config) { request ->
            CompletableFuture.completedFuture("Привет, ${request.playerName}!")
        }
        val client = NpcChatRpcClient(redis, config)
        server.start()
        client.start()

        client.complete(UUID.randomUUID(), "Player", "arrival_host", "Где я?", emptyList()).join() shouldBe
            "Привет, Player!"
        client.pendingCount() shouldBeExactly 0

        client.close()
        server.close()
        client.hasActiveTimeoutScheduler().shouldBeFalse()
    }

    "server rejects unbounded history before handler" {
        val redis = InMemoryRedis()
        val config = TestLlmModuleConfig(npcChatTimeoutMs = 1_000)
        var handled = false
        val server = NpcChatRpcServer(redis, config) {
            handled = true
            CompletableFuture.completedFuture("wrong")
        }
        val client = NpcChatRpcClient(redis, config)
        server.start()
        client.start()

        val oversized = List(7) { NpcChatTurn("user", "строка $it") }
        // The Paper client itself keeps only the six newest turns.
        client.complete(UUID.randomUUID(), "Player", "arrival_host", "Вопрос", oversized).join() shouldBe "wrong"
        handled.shouldBeTrue()

        val request =
            NpcChatRequest(
                UUID.randomUUID(),
                playerUuid = UUID.randomUUID(),
                playerName = "Player",
                personaId = "BAD-PERSONA",
                message = "Вопрос",
                history = emptyList(),
            )
        val gson = com.google.gson.Gson()
        val responseBefore = redis.getPublishedMessages().size
        redis.publish(config.npcChatRequestChannel, gson.toJson(request))
        val published = redis.getPublishedMessages().drop(responseBefore)
        published.any { it.channel == config.npcChatResponseChannel && it.message.contains("Invalid NPC persona") }
            .shouldBeTrue()

        client.close()
        server.close()
    }

    "client timeout is exceptional and close is final" {
        val redis = InMemoryRedis()
        val config = TestLlmModuleConfig(npcChatTimeoutMs = 10)
        val client = NpcChatRpcClient(redis, config)
        client.start()

        shouldThrow<CompletionException> {
            client.complete(UUID.randomUUID(), "Player", "arrival_host", "Есть кто?", emptyList()).join()
        }
        client.pendingCount() shouldBeExactly 0
        client.close()
        shouldThrow<IllegalStateException> { client.start() }
    }

    "server shortens long dialogue at a clean sentence or word boundary" {
        val redis = InMemoryRedis()
        val config = TestLlmModuleConfig(npcChatTimeoutMs = 1_000)
        val server = NpcChatRpcServer(redis, config) {
            CompletableFuture.completedFuture(
                "Осмотри площадь. Потом загляни на рынок и в мастерские, там проще понять город.",
            )
        }
        val client = NpcChatRpcClient(redis, config)
        server.start()
        client.start()

        client.complete(
            UUID.randomUUID(),
            "Player",
            "arrival_host",
            "Куда идти?",
            emptyList(),
            maxOutputChars = 42,
        ).join() shouldBe "Осмотри площадь."

        server.close()
        val wordServer = NpcChatRpcServer(redis, config) {
            CompletableFuture.completedFuture("площадь рынок мастерские порт конюшни кузница")
        }
        wordServer.start()
        client.complete(
            UUID.randomUUID(),
            "Player",
            "arrival_host",
            "А ещё?",
            emptyList(),
            maxOutputChars = 24,
        ).join() shouldBe "площадь рынок…"
        client.close()
        wordServer.close()
    }
})
