package ru.arc.logging

import org.slf4j.LoggerFactory

/** Default platform for Velocity / headless tests — plain SLF4J, no MiniMessage rendering. */
class Slf4jLoggingPlatform(
    override val brandTag: String,
    private val loggerName: String = brandTag.lowercase(),
) : LoggingPlatform {
    private val log = LoggerFactory.getLogger(loggerName)

    override fun writeConsole(
        level: LogLevel,
        styledLine: String,
        plainLine: String,
        throwable: Throwable?,
    ) {
        val message = LogFormat.plainForBuffer(styledLine).ifBlank { plainLine }
        when (level) {
            LogLevel.DEBUG -> if (throwable != null) log.debug(message, throwable) else log.debug(message)
            LogLevel.INFO -> if (throwable != null) log.info(message, throwable) else log.info(message)
            LogLevel.WARN -> if (throwable != null) log.warn(message, throwable) else log.warn(message)
            LogLevel.ERROR -> if (throwable != null) log.error(message, throwable) else log.error(message)
        }
    }
}
