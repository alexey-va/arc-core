package ru.arc.runtime

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException

enum class PluginHotloadState {
    ACTIVE,
    PREPARING,
    READY,
    FAILED,
}

data class PluginHotloadPhaseStatus(
    val state: PluginHotloadState,
    val failure: Throwable? = null,
)

data class PluginHotloadStatus(
    val state: PluginHotloadState,
    val version: String,
    val phases: Map<String, PluginHotloadPhaseStatus>,
    val failure: Throwable? = null,
)

/**
 * Observes one plugin's existing stop operation without owning its resources.
 *
 * [beginGeneration] and the first [prepare] belong on the platform lifecycle
 * thread. [stop] must stop admission, close the existing owner, and return every
 * real terminal drain, including any synchronous close failures as failed
 * stages. A synchronous throw is terminal failure; it cannot report drains the
 * callback did not return. Names are stable lowercase diagnostic tokens, at most
 * 48 characters, with at most 32 phases.
 *
 * The stop callback runs once per generation. All returned phases must finish
 * before the generation becomes READY or FAILED. Cancellation is failure, never
 * readiness. Returned stages are observers: cancelling or completing their
 * derived futures cannot change the owned result or cancel accepted cleanup.
 * Status snapshots are thread-safe and never wait for a drain.
 */
class PluginHotload(
    private val version: String,
    private val stop: () -> Map<String, CompletionStage<*>>,
) {
    private val monitor = Any()
    private var generationStarted = false
    private var completion: CompletableFuture<Unit>? = null
    private var state = PluginHotloadState.ACTIVE
    private var failure: Throwable? = null
    private var phases = linkedMapOf<String, PluginHotloadPhaseStatus>()

    init {
        require(version.isNotBlank()) { "Plugin hotload version must not be blank" }
    }

    /**
     * Starts the initial generation, or resets a successfully drained one.
     * Pending or failed cleanup and a duplicate active enable fail immediately;
     * this method never joins a future on the platform thread.
     */
    fun beginGeneration() = synchronized(monitor) {
        if (generationStarted) {
            check(state == PluginHotloadState.READY) {
                "Previous plugin generation is ${state.name.lowercase()}; successful cleanup is required before enable"
            }
            completion = null
            state = PluginHotloadState.ACTIVE
            failure = null
            phases.clear()
        }
        generationStarted = true
    }

    /** Begins the existing stop callback exactly once and observes every drain. */
    fun prepare(): CompletionStage<Unit> {
        val target = synchronized(monitor) {
            completion?.let { return it.minimalCompletionStage() }
            generationStarted = true
            state = PluginHotloadState.PREPARING
            CompletableFuture<Unit>().also { completion = it }
        }
        val drains = try {
            stop().toMap().also { values ->
                require(values.size <= 32) { "Plugin hotload supports at most 32 named phases" }
                require(values.keys.all { it.matches(PHASE_NAME) }) { "Invalid plugin hotload phase name" }
            }
        } catch (cause: Throwable) {
            finish(target, cause)
            return target.minimalCompletionStage()
        }
        synchronized(monitor) {
            drains.keys.forEach { phases[it] = PluginHotloadPhaseStatus(PluginHotloadState.PREPARING) }
        }
        val observed = drains.map { (name, stage) ->
            val phase = CompletableFuture<Unit>()
            fun complete(cause: Throwable?) {
                val actual = cause?.let(::unwrap)
                synchronized(monitor) {
                    phases[name] = PluginHotloadPhaseStatus(
                        if (actual == null) PluginHotloadState.READY else PluginHotloadState.FAILED,
                        actual,
                    )
                }
                if (actual == null) phase.complete(Unit) else phase.completeExceptionally(actual)
            }
            try {
                stage.whenComplete { _, cause -> complete(cause) }
            } catch (cause: Throwable) {
                complete(cause)
            }
            phase
        }
        CompletableFuture.allOf(*observed.toTypedArray()).whenComplete { _, cause -> finish(target, cause) }
        return target.minimalCompletionStage()
    }

    fun status(): PluginHotloadStatus = synchronized(monitor) {
        PluginHotloadStatus(state, version, phases.toMap(), failure)
    }

    private fun finish(target: CompletableFuture<Unit>, cause: Throwable?) {
        val actual = cause?.let(::unwrap)
        synchronized(monitor) {
            failure = actual
            state = if (actual == null) PluginHotloadState.READY else PluginHotloadState.FAILED
        }
        if (actual == null) target.complete(Unit) else target.completeExceptionally(actual)
    }

    private companion object {
        val PHASE_NAME = Regex("[a-z][a-z0-9_-]{0,47}")

        fun unwrap(failure: Throwable): Throwable {
            var actual = failure
            while ((actual is CompletionException || actual is ExecutionException) && actual.cause != null) {
                actual = actual.cause!!
            }
            return actual
        }
    }
}
