package ru.arc.logging

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.config.Configuration
import org.apache.logging.log4j.core.config.ConfigurationListener
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
 *
 * Velocity (and any host that reconfigures Log4j2 at runtime) re-attaches the appender on
 * [ConfigurationListener.onChange] so pushes do not stop after a few minutes.
 */
object LokiLogging {
    private val log = LogManager.getLogger(LokiLogging::class.java)

    /** When true, [install] is a no-op (tests). */
    @JvmField
    var disabled: Boolean = false

    const val DEFAULT_LOGGER_PREFIX = "ru.arc"
    const val DEFAULT_CONFIG_FILE = LoggingModuleConfig.RESOURCE

    private data class InstallState(
        val config: Config,
        val target: LokiAttachTarget,
        val loggerPrefix: String,
        val appenderName: String,
    )

    @Volatile
    private var installState: InstallState? = null

    private val listenerConfigurations =
        java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<Configuration, Boolean>(),
        )

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
            installState = null
            return false
        }

        installState = InstallState(config, target, loggerPrefix, appenderName)
        return attachAppender(module, target, loggerPrefix, appenderName)
    }

    /** Re-apply last install (e.g. ProxyARC `/proxyarc reload` after logging.yml change). */
    @JvmStatic
    fun reinstallFromState(): Boolean {
        val state = installState ?: return false
        return install(state.config, state.target, state.loggerPrefix, state.appenderName)
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

    private fun registerReconfigurationListener(configuration: Configuration) {
        if (!listenerConfigurations.add(configuration)) return
        configuration.addListener(
            ConfigurationListener { reconfigurable ->
                val state = installState ?: return@ConfigurationListener
                val module = LoggingModuleConfig(state.config)
                if (!module.enabled) return@ConfigurationListener
                attachAppender(module, state.target, state.loggerPrefix, state.appenderName)
                registerReconfigurationListener(
                    (LogManager.getRootLogger() as Logger).context.configuration,
                )
            },
        )
    }

    private fun attachAppender(
        module: LoggingModuleConfig,
        target: LokiAttachTarget,
        loggerPrefix: String,
        appenderName: String,
    ): Boolean {
        return try {
            val labels = parseLabels(module.labels)
            val rootLogger = LogManager.getRootLogger() as Logger
            val configuration = rootLogger.context.configuration
            registerReconfigurationListener(configuration)
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
                configuration.getAppender(appenderName) as? LokiAppender
                    ?: LokiAppender
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
                        .also {
                            it.start()
                            configuration.addAppender(it)
                        }

            if (!appender.isStarted) {
                appender.start()
            }

            when (target) {
                LokiAttachTarget.LOGGER_PREFIX -> {
                    val loggerConfig = configuration.getLoggerConfig(loggerPrefix)
                    if (!loggerConfig.appenderRefs.any { it.ref == appenderName }) {
                        loggerConfig.addAppender(appender, lokiLevel, null)
                    }
                    loggerConfig.level = lokiLevel
                    loggerConfig.isAdditive = false
                }
                LokiAttachTarget.ROOT -> {
                    val loggerConfig = configuration.getLoggerConfig(LogManager.ROOT_LOGGER_NAME)
                    if (!loggerConfig.appenderRefs.any { it.ref == appenderName }) {
                        loggerConfig.addAppender(appender, lokiLevel, null)
                    }
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
