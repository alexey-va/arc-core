package ru.arc.logging

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.config.Configuration
import org.apache.logging.log4j.core.filter.BurstFilter
import org.apache.logging.log4j.core.layout.PatternLayout
import pl.tkowalcz.tjahzi.log4j2.LokiAppender
import pl.tkowalcz.tjahzi.log4j2.labels.Label
import pl.tkowalcz.tjahzi.log4j2.labels.StructuredMetadata
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Installs Tjahzi Loki appender from `logging.yml` (shared by ARC Paper and ProxyARC).
 *
 * Config keys: `enabled`, `host`, `port`, `labels`, `rate`, `maxBurst`, `loki-level`, `loki-format`.
 */
object LokiLogging {
    private val log = LogManager.getLogger(LokiLogging::class.java)

    /** When true, [install] is a no-op (tests). */
    @JvmField
    var disabled: Boolean = false

    const val DEFAULT_LOGGER_PREFIX = "ru.arc"
    const val DEFAULT_CONFIG_FILE = LoggingModuleConfig.RESOURCE

    @JvmStatic
    fun loadConfig(folder: Path, configFile: String = DEFAULT_CONFIG_FILE): Config =
        ConfigManager.ofModule(folder, configFile)

    @JvmStatic
    @JvmOverloads
    fun install(
        folder: Path,
        configFile: String = DEFAULT_CONFIG_FILE,
        target: LokiAttachTarget = LokiAttachTarget.LOGGER_PREFIX,
        loggerPrefix: String = DEFAULT_LOGGER_PREFIX,
        appenderName: String = "ArcLokiAppender",
    ): Boolean = install(loadConfig(folder, configFile), target, loggerPrefix, appenderName)

    /**
     * @return true if appender was installed, false if skipped (disabled / already off).
     */
    @JvmStatic
    @JvmOverloads
    fun install(
        config: Config,
        target: LokiAttachTarget = LokiAttachTarget.LOGGER_PREFIX,
        loggerPrefix: String = DEFAULT_LOGGER_PREFIX,
        appenderName: String = "ArcLokiAppender",
    ): Boolean {
        if (disabled) return false
        val module = LoggingModuleConfig(config)
        if (!module.enabled) {
            log.debug("Loki appender disabled in config (enabled=false)")
            return false
        }

        return try {
            val labels = parseLabels(module.labels)
            val rootLogger = LogManager.getRootLogger() as Logger
            val configuration = rootLogger.context.configuration
            val layout = buildLayout(module, configuration)
            val lokiLevel = module.lokiLevel.toLog4j()

            val filter =
                BurstFilter
                    .newBuilder()
                    .setLevel(lokiLevel)
                    .setRate(module.rate.toFloat())
                    .setMaxBurst(module.maxBurst.toLong())
                    .build()

            val appender =
                LokiAppender
                    .newBuilder()
                    .apply {
                        host = module.host
                        port = module.port
                        setLabels(labels)
                        setHeaders(emptyArray())
                        setMetadata(emptyArray<StructuredMetadata>())
                        name = appenderName
                        setLayout(layout)
                        setFilter(filter)
                    }.build()

            appender.start()
            configuration.addAppender(appender)

            when (target) {
                LokiAttachTarget.LOGGER_PREFIX -> {
                    val loggerConfig = configuration.getLoggerConfig(loggerPrefix)
                    loggerConfig.addAppender(appender, lokiLevel, null)
                    loggerConfig.level = lokiLevel
                    loggerConfig.isAdditive = false
                }
                LokiAttachTarget.ROOT -> {
                    val loggerConfig = configuration.getLoggerConfig(LogManager.ROOT_LOGGER_NAME)
                    loggerConfig.addAppender(appender, lokiLevel, null)
                }
            }

            rootLogger.context.updateLoggers()
            log.info(
                "Loki appender '{}' → {}:{} (target={}, level={})",
                appenderName,
                module.host,
                module.port,
                target,
                lokiLevel,
            )
            true
        } catch (e: Throwable) {
            log.warn("Failed to install Loki appender", e)
            false
        }
    }

    @JvmStatic
    fun buildLayout(
        config: Config,
        configuration: Configuration,
    ): org.apache.logging.log4j.core.Layout<String> = buildLayout(LoggingModuleConfig(config), configuration)

    @JvmStatic
    fun buildLayout(
        module: LoggingModuleConfig,
        configuration: Configuration,
    ): org.apache.logging.log4j.core.Layout<String> {
        if (module.lokiFormat == "pattern") {
            return PatternLayout
                .newBuilder()
                .withPattern(
                    "{\"instant\":{\"epochSecond\":%d{UNIX},\"nanoOfSecond\":%nano},"
                        + "\"thread\":\"%t\","
                        + "\"level\":\"%p\","
                        + "\"loggerName\":\"%c\","
                        + "\"message\":\"%enc{%m}{JSON}\","
                        + "\"endOfBatch\":false,"
                        + "\"loggerFqcn\":\"%fqcn\","
                        + "\"threadId\":%tid,"
                        + "\"threadPriority\":%threadPriority}%n",
                )
                .withCharset(StandardCharsets.UTF_8)
                .build()
        }

        return ArcJsonLayout.create(configuration)
    }

    @JvmStatic
    fun resolveLokiLevel(config: Config): Level = LoggingModuleConfig(config).lokiLevel.toLog4j()

    private fun parseLabels(labels: Map<String, String>): Array<Label> =
        labels
            .entries
            .filter { (key, value) ->
                when {
                    !Label.hasValidName(key) -> {
                        log.warn("Invalid Loki label name: {}", key)
                        false
                    }
                    value.isBlank() -> {
                        log.warn("Blank value for Loki label: {}", key)
                        false
                    }
                    else -> true
                }
            }.map { (key, value) -> Label.createLabel(key, value, null) }
            .toTypedArray()
}
