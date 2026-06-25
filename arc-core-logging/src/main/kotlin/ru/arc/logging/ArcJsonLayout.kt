package ru.arc.logging

import com.google.gson.Gson
import org.apache.logging.log4j.core.Layout
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.config.Configuration
import org.apache.logging.log4j.core.layout.AbstractStringLayout
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Compact JSON lines for Loki/Grafana. Avoids Log4j JsonLayout + Jackson conflicts in shaded plugin JARs.
 */
class ArcJsonLayout(
    config: Configuration,
) : AbstractStringLayout(StandardCharsets.UTF_8, null, null) {

    init {
        @Suppress("unused")
        val unused = config
    }

    override fun toSerializable(event: LogEvent): String {
        val payload = linkedMapOf<String, Any?>()
        payload["timestamp"] = Instant.ofEpochMilli(event.timeMillis).toString()
        payload["level"] = event.level.name()
        payload["logger"] = event.loggerName
        payload["message"] = event.message?.formattedMessage ?: ""
        payload["thread"] = event.threadName

        val context = event.contextData?.toMap().orEmpty()
        if (context.isNotEmpty()) {
            payload["contextMap"] = context
        }

        event.thrown?.let { payload["exception"] = it.stackTraceToString() }

        return GSON.toJson(payload) + System.lineSeparator()
    }

    companion object {
        private val GSON = Gson()

        fun create(configuration: Configuration): Layout<String> = ArcJsonLayout(configuration)
    }
}
