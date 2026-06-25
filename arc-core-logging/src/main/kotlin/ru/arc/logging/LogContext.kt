package ru.arc.logging

import org.apache.logging.log4j.ThreadContext

/** Structured Log4j MDC context for Loki JSON (`contextMap` in [ArcJsonLayout]). */
object LogContext {
    @JvmStatic
    @JvmOverloads
    fun withContext(
        module: String? = null,
        player: String? = null,
        action: String? = null,
        block: Runnable,
    ) {
        val keys =
            listOfNotNull(
                module?.let { "module" to it },
                player?.let { "player" to it },
                action?.let { "action" to it },
            )
        keys.forEach { (key, value) -> ThreadContext.put(key, value) }
        try {
            block.run()
        } finally {
            keys.forEach { (key, _) -> ThreadContext.remove(key) }
        }
    }
}
