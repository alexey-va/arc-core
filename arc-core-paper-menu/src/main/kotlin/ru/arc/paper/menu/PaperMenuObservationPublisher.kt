package ru.arc.paper.menu

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.Collections
import java.util.UUID

internal object PaperMenuObservationPublisher {
    private val dispatching = ThreadLocal<PaperMenuAttempt>()

    fun publish(plugin: Plugin, snapshot: () -> Map<String, Any>?) {
        runCatching {
            if (!Bukkit.isPrimaryThread()) return
            val sink = plugin.server.servicesManager.load(ArcTelemetryProvider::class.java) ?: return
            val payload = snapshot() ?: return
            val trigger = currentAttempt()?.takeIf {
                it.started && payload["phase"] == "open" && payload["playerId"] == it.playerId
            }
            sink.observeUi(Collections.unmodifiableMap(
                if (trigger == null) payload else payload + ("triggerAttemptId" to trigger.id),
            ))
        }
    }

    fun attempt(plugin: Plugin, snapshot: () -> Map<String, Any>): PaperMenuAttempt? = runCatching {
        if (Bukkit.isPrimaryThread()) PaperMenuAttempt(plugin, snapshot()) else null
    }.getOrNull()

    fun currentAttempt(): PaperMenuAttempt? = dispatching.get()

    /** Correlates actual same-player opens within this shaded runtime and this call only. */
    fun <T> dispatch(attempt: PaperMenuAttempt?, action: () -> T): T {
        val previous = dispatching.get()
        dispatching.set(attempt)
        try {
            return action()
        } catch (failure: Throwable) {
            attempt?.start("handler_exception") // A failed transfer remains cancelled.
            attempt?.reportOutcome("failed", "handler_exception", null)
            throw failure
        } finally {
            if (previous == null) dispatching.remove() else dispatching.set(previous)
        }
    }
}

/** Captures only detached telemetry values so a reported outcome can outlive its screen. */
internal class PaperMenuAttempt(private val plugin: Plugin, snapshot: Map<String, Any>) {
    val id: String = UUID.randomUUID().toString()
    val playerId = snapshot.getValue("playerId")
    private val payload = snapshot + ("attemptId" to id)
    var started = false
        private set
    private var pending: MutableList<Map<String, Any>>? = null

    fun start(blockedReason: String? = null) {
        if (started) return
        started = true
        PaperMenuObservationPublisher.publish(plugin) {
            if (blockedReason == null) payload + ("phase" to "click")
            else payload + mapOf("phase" to "blocked", "reason" to blockedReason)
        }
        pending?.forEach { publishResult(it) }
        pending = null
    }

    fun reject(reason: String) {
        start(reason)
        reportOutcome("rejected", reason, null)
    }

    fun reportOutcome(outcome: String, reason: String?, target: String?) {
        if (outcome !in OUTCOMES) return
        runCatching {
            if (!Bukkit.isPrimaryThread()) return
            val details = buildMap<String, Any> {
                put("phase", "result")
                put("outcome", outcome)
                reason?.takeIf(REASON::matches)?.let { put("reason", it) }
                target?.takeIf(TARGET::matches)?.let { put("target", it) }
            }
            if (started) publishResult(details)
            else {
                // Transfer admission is observed only after its handler returns.
                // ponytail: keep a handful of synchronous reports until the legacy admission point.
                val results = pending ?: mutableListOf<Map<String, Any>>().also { pending = it }
                if (results.size < 4) results += details
            }
        }
    }

    private fun publishResult(details: Map<String, Any>) =
        PaperMenuObservationPublisher.publish(plugin) { payload + details }

    private companion object {
        val OUTCOMES = setOf("requested", "succeeded", "rejected", "failed")
        val REASON = Regex("[a-z][a-z0-9_]{0,47}")
        val TARGET = Regex("[a-z0-9][a-z0-9_.:/-]{0,95}")
    }
}
