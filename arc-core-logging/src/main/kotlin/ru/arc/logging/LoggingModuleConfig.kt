package ru.arc.logging

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import java.nio.file.Path

/**
 * Typed accessors for [LoggingModuleConfig.RESOURCE] — shared by Paper (ARC) and Velocity (ProxyARC).
 *
 * Bundled defaults live in arc-core-logging; on each server override at least
 * `labels.service_name`, and set `enabled` + `host` for Loki.
 */
open class LoggingModuleConfig(
    private val config: Config,
) {
    open val enabled: Boolean
        get() = config.bool("enabled", false)

    open val host: String
        get() = config.string("host", "localhost")

    open val port: Int
        get() = config.integer("port", 3100)

    open val rate: Int
        get() = config.integer("rate", 20)

    open val maxBurst: Int
        get() = config.integer("maxBurst", 100)

    /** Console / [ArcLogging] threshold (DEBUG|INFO|WARN|ERROR). Hot-reload via config version. */
    open val level: LogLevel
        get() =
            runCatching {
                LogLevel.valueOf(config.string("level", "INFO").uppercase())
            }.getOrDefault(LogLevel.INFO)

    /** Minimum level shipped to Loki appender. */
    open val lokiLevel: LogLevel
        get() =
            runCatching {
                LogLevel.valueOf(config.string("loki-level", "INFO").uppercase())
            }.getOrDefault(LogLevel.INFO)

    /** `json` ([ArcJsonLayout]) or `pattern` (Log4j PatternLayout JSON-like). */
    open val lokiFormat: String
        get() = config.string("loki-format", "json").lowercase()

    open val labels: Map<String, String>
        get() = config.map("labels", emptyMap())

    open val serviceName: String
        get() = labels["service_name"]?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVICE_NAME

    open val debugQuietSources: List<String>
        get() =
            config
                .stringList("debug-quiet-sources", emptyList())
                .map { it.trim() }
                .filter { it.isNotEmpty() }

    companion object {
        const val RESOURCE = "logging.yml"
        const val DEFAULT_SERVICE_NAME = "arc"

        @JvmStatic
        fun load(dataPath: Path): LoggingModuleConfig =
            LoggingModuleConfig(ConfigManager.ofModule(dataPath, RESOURCE))

        @JvmStatic
        fun bundledResourcePath(): String = ConfigManager.bundledModuleResource(RESOURCE)
    }
}

/** Explicit test values — no YAML file. */
class TestLoggingModuleConfig(
    override val enabled: Boolean = false,
    override val host: String = "localhost",
    override val port: Int = 3100,
    override val rate: Int = 20,
    override val maxBurst: Int = 100,
    override val level: LogLevel = LogLevel.INFO,
    override val lokiLevel: LogLevel = LogLevel.INFO,
    override val lokiFormat: String = "json",
    override val labels: Map<String, String> = mapOf("service_name" to DEFAULT_SERVICE_NAME),
    override val debugQuietSources: List<String> = emptyList(),
) : LoggingModuleConfig(EmptyConfig)
