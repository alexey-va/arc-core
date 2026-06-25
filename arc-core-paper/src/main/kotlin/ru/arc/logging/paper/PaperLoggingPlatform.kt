package ru.arc.logging.paper

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import ru.arc.logging.LogFormat
import ru.arc.logging.LogLevel
import ru.arc.logging.LoggingPlatform

/**
 * Paper console: MiniMessage via [Bukkit.getConsoleSender], JUL fallback for tests.
 */
class PaperLoggingPlatform(
    override val brandTag: String = "ARC",
    private val julLoggerName: String = "ARC",
    private val miniMessage: MiniMessage = MiniMessage.miniMessage(),
) : LoggingPlatform {
    override fun writeConsole(
        level: LogLevel,
        styledLine: String,
        plainLine: String,
        throwable: Throwable?,
    ) {
        writeMiniMessage(styledLine)
        if (level == LogLevel.ERROR) {
            val jul = java.util.logging.Logger.getLogger(julLoggerName)
            if (throwable != null) {
                jul.log(java.util.logging.Level.SEVERE, plainLine, throwable)
            } else {
                jul.log(java.util.logging.Level.SEVERE, plainLine)
            }
        }
    }

    /** Direct MiniMessage line (no level prefix) — e.g. startup banners. */
    fun writeRaw(miniMessage: String) = writeMiniMessage(miniMessage)

    private fun writeMiniMessage(line: String) {
        try {
            val component = miniMessage.deserialize(line)
            Bukkit.getConsoleSender().sendMessage(component)
        } catch (_: Exception) {
            java.util.logging.Logger
                .getLogger(julLoggerName)
                .info(LogFormat.plainForBuffer(line))
        }
    }
}
