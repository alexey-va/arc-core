package ru.arc.util

import org.slf4j.LoggerFactory

/** Minimal slf4j facade for platform-agnostic modules. */
object Logging {
    private val log = LoggerFactory.getLogger("ru.arc")

    fun debug(message: String, vararg args: Any?) {
        log.debug(message, *args)
    }

    fun info(message: String, vararg args: Any?) {
        log.info(message, *args)
    }

    fun warn(message: String, vararg args: Any?) {
        log.warn(message, *args)
    }

    fun error(message: String, vararg args: Any?) {
        log.error(message, *args)
    }

    fun error(message: String, throwable: Throwable) {
        log.error(message, throwable)
    }
}
