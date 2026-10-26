package ru.arc.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage

class PluginHotloadTest : FreeSpec({
    "prepare stops once and waits for every real drain without giving observers cancellation authority" {
        val storage = CompletableFuture<Unit>()
        val native = CompletableFuture<Unit>()
        val history = CompletableFuture<Unit>()
        var calls = 0
        val hotload = PluginHotload("2.7.69") {
            calls++
            linkedMapOf("storage" to storage, "native" to native, "history" to history)
        }
        hotload.beginGeneration()
        hotload.status().state shouldBe PluginHotloadState.ACTIVE
        hotload.status().version shouldBe "2.7.69"

        val cancelledObserver = hotload.prepare().toCompletableFuture()
        val completion = hotload.prepare().toCompletableFuture()
        cancelledObserver.cancel(true) shouldBe true
        calls shouldBe 1
        storage.isCancelled shouldBe false
        native.isCancelled shouldBe false
        val earlier = hotload.status()
        earlier.phases.values.map { it.state } shouldBe List(3) { PluginHotloadState.PREPARING }

        storage.complete(Unit)
        history.complete(Unit)
        completion.isDone shouldBe false
        hotload.status().state shouldBe PluginHotloadState.PREPARING
        hotload.status().phases.getValue("storage").state shouldBe PluginHotloadState.READY
        earlier.phases.getValue("storage").state shouldBe PluginHotloadState.PREPARING

        native.complete(Unit)
        completion.join() shouldBe Unit
        hotload.status().state shouldBe PluginHotloadState.READY
        hotload.status().phases.values.map { it.state } shouldBe List(3) { PluginHotloadState.READY }
        hotload.prepare().toCompletableFuture().join() shouldBe Unit
        calls shouldBe 1
    }

    "failure and cancellation stay separate and the combined result still waits for the last drain" {
        val storage = CompletableFuture<Unit>()
        val native = CompletableFuture<Unit>()
        val history = CompletableFuture<Unit>()
        val failure = IllegalStateException("storage failed")
        val hotload = PluginHotload("1") { linkedMapOf("storage" to storage, "native" to native, "history" to history) }
        val completion = hotload.prepare().toCompletableFuture()

        storage.completeExceptionally(CompletionException(failure))
        native.cancel(false)
        completion.isDone shouldBe false
        hotload.status().state shouldBe PluginHotloadState.PREPARING
        hotload.status().phases.getValue("storage").failure shouldBeSameInstanceAs failure
        (hotload.status().phases.getValue("native").failure is CancellationException) shouldBe true

        history.complete(Unit)
        shouldThrow<CompletionException> { completion.join() }.cause shouldBeSameInstanceAs failure
        hotload.status().state shouldBe PluginHotloadState.FAILED
        hotload.status().failure shouldBeSameInstanceAs failure
        hotload.status().phases.getValue("history").state shouldBe PluginHotloadState.READY
        shouldThrow<IllegalStateException> { hotload.beginGeneration() }
    }

    "a cancelled drain fails readiness even when it is the only phase" {
        val drain = CompletableFuture<Unit>()
        val hotload = PluginHotload("1") { mapOf("storage" to drain) }
        val result = hotload.prepare().toCompletableFuture()
        drain.cancel(false)

        shouldThrow<CompletionException> { result.join() }.cause.let { it is CancellationException } shouldBe true
        hotload.status().state shouldBe PluginHotloadState.FAILED
        (hotload.status().failure is CancellationException) shouldBe true
    }

    "a synchronous stop throw is captured once and blocks another generation" {
        var calls = 0
        val failure = IllegalArgumentException("stop failed")
        val hotload = PluginHotload("1") { calls++; throw failure }
        hotload.beginGeneration()

        repeat(2) {
            val completion = hotload.prepare().toCompletableFuture()
            completion.isDone shouldBe true
            shouldThrow<CompletionException> { completion.join() }.cause shouldBeSameInstanceAs failure
        }
        calls shouldBe 1
        hotload.status().state shouldBe PluginHotloadState.FAILED
        hotload.status().failure shouldBeSameInstanceAs failure
        shouldThrow<IllegalStateException> { hotload.beginGeneration() }
        hotload.prepare()
        calls shouldBe 1
    }

    "enable refuses an active or pending predecessor and resets only successful cleanup" {
        var drain = CompletableFuture<Unit>()
        var calls = 0
        val hotload = PluginHotload("1") { calls++; mapOf("storage" to drain) }
        hotload.beginGeneration()
        shouldThrow<IllegalStateException> { hotload.beginGeneration() }
        hotload.prepare()
        shouldThrow<IllegalStateException> { hotload.beginGeneration() }
        drain.isDone shouldBe false
        calls shouldBe 1

        drain.complete(Unit)
        hotload.beginGeneration()
        hotload.status().state shouldBe PluginHotloadState.ACTIVE
        hotload.status().phases shouldBe emptyMap()
        hotload.status().failure shouldBe null
        drain = CompletableFuture()
        val second = hotload.prepare().toCompletableFuture()
        calls shouldBe 2
        second.isDone shouldBe false
        drain.complete(Unit)
        second.join() shouldBe Unit
        hotload.status().state shouldBe PluginHotloadState.READY
    }

    "empty cleanup is ready and a reentrant prepare never repeats stop" {
        lateinit var hotload: PluginHotload
        var calls = 0
        var nested: CompletionStage<Unit>? = null
        hotload = PluginHotload("1") {
            calls++
            nested = hotload.prepare()
            emptyMap()
        }

        hotload.prepare().toCompletableFuture().join() shouldBe Unit
        nested!!.toCompletableFuture().join() shouldBe Unit
        calls shouldBe 1
        hotload.status().state shouldBe PluginHotloadState.READY
    }

    "drains are observed through CompletionStage without requiring toCompletableFuture" {
        val drain = object : CompletableFuture<Unit>() {
            override fun toCompletableFuture(): CompletableFuture<Unit> = error("not supported")
        }
        val hotload = PluginHotload("1") { mapOf("native" to drain) }
        val result = hotload.prepare().toCompletableFuture()
        drain.complete(Unit)
        result.join() shouldBe Unit
        hotload.status().state shouldBe PluginHotloadState.READY
    }
})
