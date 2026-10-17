package ru.arc.core

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Best-effort JVM lifetime protection for an asynchronous lifecycle drain.
 * Register while the owner starts, remove after actual completion. Hooks run
 * concurrently: [startDrain] must be thread-safe, non-native bookkeeping only,
 * and must preserve work retained by other lifecycle owners. It must never seal
 * a consumer executor before that consumer can enqueue its terminal flush.
 * A forced process kill still requires durable recovery journals.
 */
class JvmShutdownDrain(
    private val name: String,
    completion: CompletableFuture<*>,
    startDrain: () -> Unit = {},
) : AutoCloseable {
    private val hook = Thread({
        try {
            startDrain()
            completion.get(120, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            warn("shutdown drain timed out; durable recovery remains required")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: ExecutionException) {
            warn("terminal flush failed; durable recovery remains required")
        } catch (failure: Throwable) {
            System.getLogger(JvmShutdownDrain::class.java.name).log(System.Logger.Level.WARNING,
                "$name shutdown drain could not start", failure)
        }
    }, "$name-shutdown-drain")

    init {
        require(name.isNotBlank())
        Runtime.getRuntime().addShutdownHook(hook)
        completion.whenComplete { _, _ -> close() }
    }

    override fun close() {
        try { Runtime.getRuntime().removeShutdownHook(hook) }
        catch (_: IllegalStateException) { /* JVM shutdown is already observing this owner. */ }
    }

    private fun warn(message: String) = System.getLogger(JvmShutdownDrain::class.java.name)
        .log(System.Logger.Level.WARNING, "$name $message")
}
