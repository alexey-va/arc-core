package ru.arc.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class DurableRecoveryWorkflowTest : FreeSpec({
    data class Record(val id: String, val checksum: String)
    data class RestoreReceipt(val checksum: String)

    fun workflow(
        commit: (Record) -> CompletableFuture<Record> = { CompletableFuture.completedFuture(it) },
        acknowledge: (Record, RestoreReceipt) -> CompletableFuture<DurableAcknowledgementOutcome> = { _, _ ->
            CompletableFuture.completedFuture(DurableAcknowledgementOutcome.ACKNOWLEDGED)
        },
    ) = DurableRecoveryWorkflow(
        commit = commit,
        sameContent = Record::equals,
        acknowledge = acknowledge,
    )

    "mutation runs only after matching durable readback" {
        val candidate = Record("player", "sha")
        var mutated: Record? = null

        val receipt = workflow().commitThenMutate(candidate) { committed ->
            mutated = committed
            CompletableFuture.completedFuture("mutated")
        }.join()

        mutated shouldBe candidate
        receipt shouldBe DurableMutationReceipt(candidate, "mutated")
    }

    "mismatched readback rejects before mutation" {
        val candidate = Record("player", "sha")
        var mutations = 0
        val failure = shouldThrow<CompletionException> {
            workflow(commit = { CompletableFuture.completedFuture(it.copy(checksum = "other")) })
                .commitThenMutate(candidate) {
                    mutations++
                    CompletableFuture.completedFuture("unsafe")
                }.join()
        }.cause as DurableRecoveryException

        failure.phase shouldBe DurableRecoveryPhase.COMMIT
        mutations shouldBe 0
        failure.message shouldBe "Durable recovery commit failed"
    }

    "commit and mutation failures expose their exact phase" {
        val candidate = Record("player", "sha")
        val commitFailure = shouldThrow<CompletionException> {
            workflow(commit = { CompletableFuture.failedFuture(IllegalStateException("storage")) })
                .commitThenMutate(candidate) { CompletableFuture.completedFuture("unused") }.join()
        }.cause as DurableRecoveryException
        commitFailure.phase shouldBe DurableRecoveryPhase.COMMIT

        val mutationFailure = shouldThrow<CompletionException> {
            workflow().commitThenMutate(candidate) {
                CompletableFuture.failedFuture(IllegalStateException("paper"))
            }.join()
        }.cause as DurableRecoveryException
        mutationFailure.phase shouldBe DurableRecoveryPhase.MUTATION
    }

    "acknowledgement is attempted only after successful restore verification" {
        val record = Record("player", "sha")
        var acknowledgements = 0
        val recovery = workflow(
            acknowledge = { _, _ ->
                acknowledgements++
                CompletableFuture.completedFuture(DurableAcknowledgementOutcome.ACKNOWLEDGED)
            },
        )

        val restored = recovery.restoreThenAcknowledge(record) {
            CompletableFuture.completedFuture(RestoreReceipt(it.checksum))
        }.join()
        restored shouldBe DurableRecoveryCompletion.Acknowledged(record, RestoreReceipt("sha"))
        acknowledgements shouldBe 1

        val failure = shouldThrow<CompletionException> {
            recovery.restoreThenAcknowledge(record) {
                CompletableFuture.failedFuture(IllegalStateException("verification"))
            }.join()
        }.cause as DurableRecoveryException
        failure.phase shouldBe DurableRecoveryPhase.RESTORE
        acknowledgements shouldBe 1
    }

    "every acknowledgement outcome stays explicit" {
        val record = Record("player", "sha")
        val receipt = RestoreReceipt("sha")

        fun result(outcome: DurableAcknowledgementOutcome): DurableRecoveryCompletion<Record, RestoreReceipt> =
            workflow(acknowledge = { _, _ -> CompletableFuture.completedFuture(outcome) })
                .restoreThenAcknowledge(record) { CompletableFuture.completedFuture(receipt) }.join()

        result(DurableAcknowledgementOutcome.ACKNOWLEDGED) shouldBe
            DurableRecoveryCompletion.Acknowledged(record, receipt)
        result(DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED) shouldBe
            DurableRecoveryCompletion.AlreadyAcknowledged(record, receipt)
        result(DurableAcknowledgementOutcome.CONTENT_MISMATCH) shouldBe
            DurableRecoveryCompletion.ContentMismatch(record, receipt)
    }

    "synchronous callback failures are phase classified" {
        val record = Record("player", "sha")
        val failure = shouldThrow<CompletionException> {
            workflow(acknowledge = { _, _ -> throw IllegalStateException("sync") })
                .restoreThenAcknowledge(record) { CompletableFuture.completedFuture(RestoreReceipt("sha")) }
                .join()
        }.cause as DurableRecoveryException

        failure.phase shouldBe DurableRecoveryPhase.ACKNOWLEDGEMENT
    }
})
