package ru.arc.paper.chunk

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class PaperChunkTicketRegistryTest : FreeSpec({
    val first = PaperChunkKey(UUID.fromString("00000000-0000-0000-0000-000000000001"), 4, -2)
    val second = PaperChunkKey(UUID.fromString("00000000-0000-0000-0000-000000000002"), 0, 0)

    "one native ticket is reference-counted across idempotent leases" {
        val backend = FakeChunkTicketBackend()
        val registry = PaperChunkTicketRegistry(backend)

        val one = registry.acquire(first).shouldBeInstanceOf<PaperChunkTicketAcquireResult.Acquired>().lease
        val two = registry.acquire(first).shouldBeInstanceOf<PaperChunkTicketAcquireResult.Acquired>().lease

        backend.added shouldBe listOf(first)
        registry.activeChunkCount shouldBe 1
        registry.activeLeaseCount shouldBe 2

        one.close()
        one.close()
        backend.removed shouldBe emptyList()
        registry.activeLeaseCount shouldBe 1

        two.close()
        backend.removed shouldBe listOf(first)
        registry.activeChunkCount shouldBe 0
    }

    "a pre-existing plugin ticket is borrowed and never removed" {
        val backend = FakeChunkTicketBackend(addResult = PaperChunkTicketAddResult.ALREADY_PRESENT)
        val registry = PaperChunkTicketRegistry(backend)

        registry.acquire(first).shouldBeInstanceOf<PaperChunkTicketAcquireResult.Acquired>().lease.close()

        backend.added shouldBe listOf(first)
        backend.removed shouldBe emptyList()
    }

    "unavailable worlds and backend failures are typed outcomes" {
        val unavailable = PaperChunkTicketRegistry(
            FakeChunkTicketBackend(addResult = PaperChunkTicketAddResult.WORLD_UNAVAILABLE),
        )
        unavailable.acquire(first) shouldBe PaperChunkTicketAcquireResult.WorldUnavailable

        val failure = IllegalStateException("backend unavailable")
        val failed = PaperChunkTicketRegistry(FakeChunkTicketBackend(addFailure = failure)).acquire(first)
            .shouldBeInstanceOf<PaperChunkTicketAcquireResult.Failed>()
        failed.failure shouldBe failure
    }

    "registry close releases every owned ticket and rejects new leases" {
        val backend = FakeChunkTicketBackend()
        val registry = PaperChunkTicketRegistry(backend)
        registry.acquire(first).shouldBeInstanceOf<PaperChunkTicketAcquireResult.Acquired>()
        registry.acquire(second).shouldBeInstanceOf<PaperChunkTicketAcquireResult.Acquired>()

        registry.close()
        registry.close()

        backend.removed shouldBe listOf(first, second)
        registry.activeChunkCount shouldBe 0
        registry.acquire(first) shouldBe PaperChunkTicketAcquireResult.RegistryClosed
    }

    "an unknown removal result blocks reacquisition and close retries cleanup" {
        val backend = FakeChunkTicketBackend(removeFailure = IllegalStateException("unknown removal"))
        val registry = PaperChunkTicketRegistry(backend)
        val lease = registry.acquire(first).shouldBeInstanceOf<PaperChunkTicketAcquireResult.Acquired>().lease

        shouldThrow<IllegalStateException> { lease.close() }.message shouldBe "unknown removal"
        registry.activeLeaseCount shouldBe 0
        registry.acquire(first).shouldBeInstanceOf<PaperChunkTicketAcquireResult.Failed>()

        backend.removeFailure = null
        registry.close()
        backend.removed shouldBe listOf(first, first)
    }

    "a failed registry close keeps owned tickets for an explicit retry" {
        val backend = FakeChunkTicketBackend(removeFailure = IllegalStateException("cleanup unavailable"))
        val registry = PaperChunkTicketRegistry(backend)
        registry.acquire(first).shouldBeInstanceOf<PaperChunkTicketAcquireResult.Acquired>()

        shouldThrow<IllegalStateException> { registry.close() }
        registry.activeChunkCount shouldBe 1
        registry.acquire(second) shouldBe PaperChunkTicketAcquireResult.RegistryClosed

        backend.removeFailure = null
        registry.close()
        registry.activeChunkCount shouldBe 0
        backend.removed shouldBe listOf(first, first)
    }

    "a false removal result is uncertain and registry close retries it" {
        val backend = FakeChunkTicketBackend(removeResult = false)
        val registry = PaperChunkTicketRegistry(backend)
        val lease = registry.acquire(first).shouldBeInstanceOf<PaperChunkTicketAcquireResult.Acquired>().lease

        shouldThrow<IllegalStateException> { lease.close() }
        registry.activeChunkCount shouldBe 1
        registry.activeLeaseCount shouldBe 0
        registry.acquire(first).shouldBeInstanceOf<PaperChunkTicketAcquireResult.Failed>()

        backend.removeResult = true
        registry.close()
        registry.activeChunkCount shouldBe 0
        backend.removed shouldBe listOf(first, first)
    }

    "a false removal during registry close preserves state for retry" {
        val backend = FakeChunkTicketBackend(removeResult = false)
        val registry = PaperChunkTicketRegistry(backend)
        registry.acquire(first).shouldBeInstanceOf<PaperChunkTicketAcquireResult.Acquired>()

        shouldThrow<IllegalStateException> { registry.close() }
        registry.activeChunkCount shouldBe 1

        backend.removeResult = true
        registry.close()
        registry.activeChunkCount shouldBe 0
        backend.removed shouldBe listOf(first, first)
    }
})

private class FakeChunkTicketBackend(
    private val addResult: PaperChunkTicketAddResult = PaperChunkTicketAddResult.ADDED,
    private val addFailure: Throwable? = null,
    var removeFailure: Throwable? = null,
    var removeResult: Boolean = true,
) : PaperChunkTicketBackend {
    val added = mutableListOf<PaperChunkKey>()
    val removed = mutableListOf<PaperChunkKey>()

    override fun add(key: PaperChunkKey): PaperChunkTicketAddResult {
        added += key
        addFailure?.let { throw it }
        return addResult
    }

    override fun remove(key: PaperChunkKey): Boolean {
        removed += key
        removeFailure?.let { throw it }
        return removeResult
    }
}
