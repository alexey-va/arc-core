package ru.arc.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture

class CoalescingAsyncWriterTest : FunSpec({
    test("bursts retain only the latest pending value without completing callers early") {
        val writes = mutableListOf<Int>()
        val gates = mutableListOf<CompletableFuture<Unit>>()
        val writer = CoalescingAsyncWriter<Int> { value ->
            writes += value
            CompletableFuture<Unit>().also(gates::add)
        }

        val first = writer.submit(1)
        val replaced = writer.submit(2)
        val latest = writer.submit(3)
        writes.shouldContainExactly(1)

        gates[0].complete(Unit)
        writes.shouldContainExactly(1, 3)
        first.isDone shouldBe true
        replaced.isDone shouldBe false
        latest.isDone shouldBe false

        gates[1].complete(Unit)
        replaced.isDone shouldBe true
        latest.isDone shouldBe true
    }

    test("a newer successful value recovers every waiter from an older failed write") {
        val gates = mutableListOf<CompletableFuture<Unit>>()
        val writer = CoalescingAsyncWriter<Int> { CompletableFuture<Unit>().also(gates::add) }
        val first = writer.submit(1)
        val second = writer.submit(2)
        gates[0].completeExceptionally(IllegalStateException("disk unavailable"))
        first.isDone shouldBe false
        gates[1].complete(Unit)
        first.isCompletedExceptionally shouldBe false
        second.isCompletedExceptionally shouldBe false
    }

    test("close waits for queued work and rejects later submissions") {
        val gates = mutableListOf<CompletableFuture<Unit>>()
        val writer = CoalescingAsyncWriter<Int> { CompletableFuture<Unit>().also(gates::add) }
        writer.submit(1)
        writer.submit(2)
        val closing = writer.closeAsync()
        closing.isDone shouldBe false
        writer.submit(3).isCompletedExceptionally shouldBe true
        gates[0].complete(Unit)
        closing.isDone shouldBe false
        gates[1].complete(Unit)
        closing.isDone shouldBe true
    }

    test("terminal write failure reaches submitter and close waiter") {
        val gate = CompletableFuture<Unit>()
        val writer = CoalescingAsyncWriter<Int> { gate }
        val submitted = writer.submit(1)
        val closing = writer.closeAsync()
        gate.completeExceptionally(IllegalStateException("disk unavailable"))
        submitted.isCompletedExceptionally shouldBe true
        closing.isCompletedExceptionally shouldBe true
    }
})
