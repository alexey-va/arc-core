package ru.arc.paper.menu

import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/** Bounded, classloader-safe observation emitted by the shared menu runtime. */
class PaperMenuObservationEvent(
    val kind: Kind,
    payload: Map<String, Any>,
) : Event(false) {
    private val snapshot: Map<String, Any> = payload.mapValues { (_, value) -> copyValue(value) }

    fun getPayload(): Map<String, Any> = snapshot

    enum class Kind {
        OPEN, RENDER, ACTION, CLOSE;

        companion object {
            fun fromPhase(phase: String): Kind = when (phase) {
                "open" -> OPEN
                "render" -> RENDER
                "click", "blocked" -> ACTION
                "close", "censored" -> CLOSE
                else -> error("Unknown observation phase: $phase")
            }
        }
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS

        private fun copyValue(value: Any): Any = when (value) {
            is String, is Int, is Long, is Boolean -> value
            is List<*> -> value.map { requireNotNull(it) { "Observation lists cannot contain null" }.let(::copyValue) }
            is Map<*, *> -> value.entries.associate { (key, child) ->
                require(key is String) { "Observation map keys must be strings" }
                key to requireNotNull(child) { "Observation maps cannot contain null" }.let(::copyValue)
            }
            else -> error("Unsupported observation payload value: ${value::class.java.name}")
        }
    }
}
