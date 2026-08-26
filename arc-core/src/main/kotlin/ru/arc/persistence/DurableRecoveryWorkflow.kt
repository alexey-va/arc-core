package ru.arc.persistence

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException

/** Stable protocol phase used in bounded recovery diagnostics. */
enum class DurableRecoveryPhase {
    COMMIT,
    MUTATION,
    RESTORE,
    ACKNOWLEDGEMENT,
}

/**
 * Failure with an explicit protocol phase and no serialized recovery payload.
 *
 * The original failure remains available as [cause]. Callers may retry only
 * according to [phase]: a commit failure proves that mutation was not invoked,
 * while later failures intentionally leave the committed recovery record for
 * reconciliation.
 */
class DurableRecoveryException(
    val phase: DurableRecoveryPhase,
    cause: Throwable,
) : RuntimeException("Durable recovery ${phase.name.lowercase()} failed", cause)

/** Exact result of attempting to retire one committed recovery record. */
enum class DurableAcknowledgementOutcome {
    ACKNOWLEDGED,
    ALREADY_ACKNOWLEDGED,
    CONTENT_MISMATCH,
}

/** Proof that [mutation] ran only after [committed] was durably read back. */
data class DurableMutationReceipt<C : Any, M : Any>(
    val committed: C,
    val mutation: M,
)

/**
 * Result after restore/verification and exact acknowledgement.
 *
 * [ContentMismatch] deliberately retains the record. It is a normal typed
 * reconciliation outcome rather than permission to delete a newer record.
 */
sealed interface DurableRecoveryCompletion<out C : Any, out R : Any> {
    val committed: C
    val restoreReceipt: R

    data class Acknowledged<C : Any, R : Any>(
        override val committed: C,
        override val restoreReceipt: R,
    ) : DurableRecoveryCompletion<C, R>

    data class AlreadyAcknowledged<C : Any, R : Any>(
        override val committed: C,
        override val restoreReceipt: R,
    ) : DurableRecoveryCompletion<C, R>

    data class ContentMismatch<C : Any, R : Any>(
        override val committed: C,
        override val restoreReceipt: R,
    ) : DurableRecoveryCompletion<C, R>
}

/**
 * Enforces the durable recovery call order without owning domain storage.
 *
 * [commit] must return the decoded durable readback. [sameContent] compares the
 * candidate with that readback and must include the domain identity/checksum.
 * [acknowledge] must conditionally retire that exact committed record.
 *
 * This class never selects an executor. Every supplied [CompletionStage]
 * controls its own thread, so Paper consumers must marshal Bukkit work to the
 * primary thread before completing mutation or restore stages. A failure after
 * commit never triggers acknowledgement and leaves recovery retryable.
 */
class DurableRecoveryWorkflow<C : Any, R : Any>(
    private val commit: (C) -> CompletionStage<C>,
    private val sameContent: (candidate: C, committed: C) -> Boolean,
    private val acknowledge: (committed: C, restoreReceipt: R) -> CompletionStage<DurableAcknowledgementOutcome>,
) {
    /** Commits and verifies [candidate] before invoking [mutation]. */
    fun <M : Any> commitThenMutate(
        candidate: C,
        mutation: (committed: C) -> CompletionStage<M>,
    ): CompletableFuture<DurableMutationReceipt<C, M>> =
        phase(DurableRecoveryPhase.COMMIT) { commit(candidate) }
            .thenCompose { committed ->
                val matches = try {
                    sameContent(candidate, committed)
                } catch (failure: Throwable) {
                    return@thenCompose failed(DurableRecoveryPhase.COMMIT, failure)
                }
                if (!matches) {
                    return@thenCompose failed(
                        DurableRecoveryPhase.COMMIT,
                        IllegalStateException("Durable recovery readback does not match the candidate"),
                    )
                }
                phase(DurableRecoveryPhase.MUTATION) { mutation(committed) }
                    .thenApply { receipt -> DurableMutationReceipt(committed, receipt) }
            }

    /** Restores/verifies [committed], then conditionally acknowledges it. */
    fun restoreThenAcknowledge(
        committed: C,
        restoreAndVerify: (committed: C) -> CompletionStage<R>,
    ): CompletableFuture<DurableRecoveryCompletion<C, R>> =
        phase(DurableRecoveryPhase.RESTORE) { restoreAndVerify(committed) }
            .thenCompose { receipt ->
                phase(DurableRecoveryPhase.ACKNOWLEDGEMENT) { acknowledge(committed, receipt) }
                    .thenApply { outcome ->
                        when (outcome) {
                            DurableAcknowledgementOutcome.ACKNOWLEDGED ->
                                DurableRecoveryCompletion.Acknowledged(committed, receipt)
                            DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED ->
                                DurableRecoveryCompletion.AlreadyAcknowledged(committed, receipt)
                            DurableAcknowledgementOutcome.CONTENT_MISMATCH ->
                                DurableRecoveryCompletion.ContentMismatch(committed, receipt)
                        }
                    }
            }

    private fun <T : Any> phase(
        phase: DurableRecoveryPhase,
        operation: () -> CompletionStage<T>,
    ): CompletableFuture<T> {
        val stage = try {
            operation()
        } catch (failure: Throwable) {
            return failed(phase, failure)
        }
        val result = CompletableFuture<T>()
        stage.whenComplete { value, failure ->
            if (failure == null) {
                if (value == null) {
                    result.completeExceptionally(
                        DurableRecoveryException(phase, IllegalStateException("Durable recovery stage returned null")),
                    )
                } else {
                    result.complete(value)
                }
            } else {
                result.completeExceptionally(DurableRecoveryException(phase, failure.unwrapCompletion()))
            }
        }
        return result
    }

    private fun <T : Any> failed(phase: DurableRecoveryPhase, failure: Throwable): CompletableFuture<T> =
        CompletableFuture.failedFuture(DurableRecoveryException(phase, failure.unwrapCompletion()))

    private fun Throwable.unwrapCompletion(): Throwable {
        var current = this
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = requireNotNull(current.cause)
        }
        return current
    }
}
