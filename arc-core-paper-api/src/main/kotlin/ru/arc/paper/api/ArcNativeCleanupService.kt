package ru.arc.paper.api

import java.util.concurrent.CompletionStage
import java.util.function.BooleanSupplier

/** Host-owned, tick-budgeted cleanup that can outlive the disabling consumer. */
interface ArcNativeCleanupService {
    /**
     * Admits a detached cleanup cursor on Paper's primary thread without running it inline.
     * [owner] is a stable diagnostic token of 1..80 letters, digits, dots, dashes, colons
     * or underscores. Each [step] performs one small, bounded native operation and returns
     * true only after its final operation; false yields to the shared next-tick budget.
     *
     * Capture concrete entities/leases and cursor state before the consumer closes. A step
     * must not read its disabled plugin's runtime, configuration or global bindings, perform
     * I/O, wait for a future, or load a cold chunk. Check a third-party provider's availability
     * before calling it. The caller retains responsibility for recovery on failure.
     *
     * The observation completes only after actual cleanup. Cancelling a future obtained
     * from it does not cancel accepted cleanup. Queue rejection, a thrown step, and host
     * shutdown complete exceptionally. Full server shutdown cannot promise future ticks;
     * use non-persistent transient entities and startup recovery where appropriate. This
     * is not a persistence or storage-drain acknowledgement. Include the returned stage
     * in the consumer generation's predecessor gate before creating replacement resources.
     */
    fun submit(owner: String, step: BooleanSupplier): CompletionStage<Void>
}
