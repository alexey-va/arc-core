package ru.arc.logging

import org.apache.logging.log4j.LogManager
import ru.arc.config.Config

/**
 * Unified ARC logging: `{}` formatting, Loki structured emit, quiet DEBUG filters, platform hooks.
 *
 * Install once at plugin startup via [install]. Paper and Velocity supply a [LoggingPlatform].
 */
object ArcLogging {
    private const val DEFAULT_LOGGER_PREFIX = "ru.arc"

    @JvmField
    var quietMode: Boolean = false

    /** Skip Loki appender install (tests — avoids direct-buffer OOM). */
    @JvmField
    var disableLoki: Boolean = false

    /** Skip Log4j structured emit alongside console output. */
    @JvmField
    var disableStructuredEmit: Boolean = false

    private var platform: LoggingPlatform = Slf4jLoggingPlatform("arc")
    private var configSource: LoggingConfigSource = LoggingConfigSource { ru.arc.config.EmptyConfig }
    private var opsSink: LoggingOpsSink = LoggingOpsSink { _, _ -> }

    private var configVersion = -1
    private var initializing = false
    private var cachedLogLevel = LogLevel.INFO
    private var quietSourcesConfigVersion = -1
    private var cachedQuietSources: Set<String> = emptySet()

    private var lokiSpec: LokiInstallSpec? = null

    @JvmStatic
    @JvmOverloads
    fun install(
        platform: LoggingPlatform,
        configSource: LoggingConfigSource,
        loki: LokiInstallSpec? = null,
        opsSink: LoggingOpsSink = LoggingOpsSink { _, _ -> },
    ) {
        this.platform = platform
        this.configSource = configSource
        this.lokiSpec = loki
        this.opsSink = opsSink
        if (loki != null && !disableLoki) {
            installLoki(loki)
        }
    }

    @JvmStatic
    fun installLoki(spec: LokiInstallSpec) {
        if (disableLoki) return
        lokiSpec = spec
        val cfg = configSource.config()
        LokiLogging.install(cfg, spec.target, spec.loggerPrefix, spec.appenderName)
    }

    @JvmStatic
    fun installLokiFromConfig() {
        val spec = lokiSpec ?: return
        if (LokiLogging.reinstallFromState()) return
        installLoki(spec)
    }

    @JvmStatic
    @JvmOverloads
    fun withContext(
        module: String? = null,
        player: String? = null,
        action: String? = null,
        block: Runnable,
    ) = LogContext.withContext(module, player, action, block)

    @JvmStatic
    fun escapeMM(s: String): String = LogFormat.escapeMiniMessage(s)

    @JvmStatic
    fun info(
        message: String,
        vararg args: Any?,
    ) = log(LogLevel.INFO, message, *args)

    @JvmStatic
    fun debug(
        message: String,
        vararg args: Any?,
    ) = log(LogLevel.DEBUG, message, *args)

    @JvmStatic
    fun warn(
        message: String,
        vararg args: Any?,
    ) = log(LogLevel.WARN, message, *args)

    @JvmStatic
    fun error(
        message: String,
        vararg args: Any?,
    ) = log(LogLevel.ERROR, message, *args)

    @JvmStatic
    fun getLogLevel(): LogLevel = getLogLevelCached()

    @JvmStatic
    fun setLogLevel(level: LogLevel) {
        cachedLogLevel = level
    }

    @JvmStatic
    fun format(
        template: String,
        vararg args: Any?,
    ): String = LogFormat.format(template, *args)

    @JvmStatic
    fun matchesQuietSource(
        className: String,
        sources: Collection<String>,
    ): Boolean = QuietDebugFilter.matchesQuietSource(className, sources)

    @JvmStatic
    fun resolveCallerLogger(): org.apache.logging.log4j.Logger {
        val caller =
            Thread
                .currentThread()
                .stackTrace
                .asSequence()
                .map { it.className }
                .firstOrNull { !QuietDebugFilter.shouldSkipStructuredCallerFrame(it) }
                ?: DEFAULT_LOGGER_PREFIX
        return LogManager.getLogger(caller)
    }

    @JvmStatic
    fun plainForBuffer(text: String): String = LogFormat.plainForBuffer(text)

    @JvmStatic
    fun buildLokiLayout(
        cfg: Config,
        configuration: org.apache.logging.log4j.core.config.Configuration,
    ): org.apache.logging.log4j.core.Layout<String> = LokiLogging.buildLayout(cfg, configuration)

    @JvmStatic
    fun resolveLokiLevel(cfg: Config): org.apache.logging.log4j.Level = LokiLogging.resolveLokiLevel(cfg)

    private fun log(
        level: LogLevel,
        message: String,
        vararg args: Any?,
    ) {
        if (quietMode) return
        if (getLogLevelCached().ordinal > level.ordinal) return
        if (level == LogLevel.DEBUG && isQuietDebugCaller()) return

        val text = format(message, *args)
        val plain = plainForBuffer(text)
        val throwable = LogFormat.extractThrowable(*args)

        emitStructured(level, plain, throwable)

        val tag = platform.brandTag
        val styled =
            when (level) {
                LogLevel.DEBUG -> "<dark_gray>[$tag] [DEBUG]</dark_gray> <gray>$text</gray>"
                LogLevel.INFO -> "<dark_gray>[$tag]</dark_gray> $text"
                LogLevel.WARN -> "<dark_gray>[$tag]</dark_gray> <yellow><bold>[WARN]</bold></yellow> $text"
                LogLevel.ERROR -> "<dark_gray>[$tag]</dark_gray> <red><bold>[ERROR]</bold></red> $text"
            }

        platform.writeConsole(level, styled, plain, throwable)

        when (level) {
            LogLevel.WARN -> {
                platform.onWarn(plain)
                opsSink.append("WARN", plain)
            }
            LogLevel.ERROR -> {
                platform.onError(plain, throwable)
                opsSink.append("ERROR", plain)
            }
            else -> Unit
        }
    }

    private fun isQuietDebugCaller(): Boolean {
        val sources = quietDebugSources()
        if (sources.isEmpty()) return false
        return Thread
            .currentThread()
            .stackTrace
            .asSequence()
            .map { it.className }
            .filterNot { QuietDebugFilter.shouldSkipStackFrame(it) }
            .any { matchesQuietSource(it, sources) }
    }

    private fun quietDebugSources(): Set<String> {
        val version = configSource.configVersion()
        if (version != quietSourcesConfigVersion) {
            cachedQuietSources = LoggingModuleConfig(configSource.config()).debugQuietSources.toSet()
            quietSourcesConfigVersion = version
        }
        return cachedQuietSources
    }

    private fun emitStructured(
        level: LogLevel,
        plainMessage: String,
        throwable: Throwable?,
    ) {
        if (disableStructuredEmit) return
        val logger = resolveCallerLogger()
        val log4jLevel = level.toLog4j()
        if (throwable != null) {
            logger.log(log4jLevel, plainMessage, throwable)
        } else {
            logger.log(log4jLevel, plainMessage)
        }
    }

    private fun getLogLevelCached(): LogLevel {
        val version = configSource.configVersion()
        val current = cachedLogLevel
        if (!initializing && version != configVersion) {
            initializing = true
            try {
                val lvl = safeResolveLevel()
                cachedLogLevel = lvl
                configVersion = version
                return lvl
            } catch (_: Throwable) {
                cachedLogLevel = LogLevel.INFO
                configVersion = version
                return LogLevel.INFO
            } finally {
                initializing = false
            }
        }
        return current
    }

    private fun safeResolveLevel(): LogLevel =
        runCatching {
            LoggingModuleConfig(configSource.config()).level
        }.getOrDefault(LogLevel.INFO)
}
