package ru.arc.logging

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.Filter.Result
import org.apache.logging.log4j.core.Layout
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.appender.AsyncAppender
import org.apache.logging.log4j.core.appender.ConsoleAppender
import org.apache.logging.log4j.core.appender.FileAppender
import org.apache.logging.log4j.core.appender.RandomAccessFileAppender
import org.apache.logging.log4j.core.appender.RollingFileAppender
import org.apache.logging.log4j.core.appender.RollingRandomAccessFileAppender
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy
import org.apache.logging.log4j.core.config.AppenderControl
import org.apache.logging.log4j.core.config.AppenderRef
import org.apache.logging.log4j.core.config.Configuration
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.filter.AbstractFilter
import org.apache.logging.log4j.core.filter.Filterable
import org.apache.logging.log4j.core.layout.PatternLayout
import java.beans.PropertyChangeListener
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One explicit host owner per Log4j context; shaded sibling plugins must not install this themselves.
 * Mirrors first-party records to `logDirectory/arc-debug.log` through a native nonblocking queue
 * (4096 events), rotating at 10 MB with seven gzip archives. Does not enable DEBUG loggers.
 * Only recognized native main appenders quiet explicit diagnostics; Loki is untouched.
 * A write/queue failure restores main output before forwarding quiet records, without root re-emission.
 * Close detaches only this owner's state and drains accepted file writes; repeated install returns
 * the same owner until it closes. Reconfiguration is observed after Log4j replaces its configuration.
 */
object ArcDiagnosticLogging {
    private const val OWNER_KEY = "ru.arc.logging.diagnostics.owner.v1"
    internal const val CONSOLE_MIRROR_KEY = "ru.arc.logging.structured-console-mirror"
    internal const val FILE_APPENDER = "ArcDiagnosticFile"
    internal const val ASYNC_APPENDER = "ArcDiagnosticAsync"
    internal const val FALLBACK_APPENDER = "ArcDiagnosticFallback"
    private val noOp = AutoCloseable { }
    private val mainConsoleNames = setOf("console", "terminalconsole", "serverguiconsole")
    private val mainConsoleTypes = setOf(
        "net.minecrell.terminalconsole.TerminalConsoleAppender",
        "com.mojang.logging.plugins.QueueLogAppender",
        "com.mojang.util.QueueLogAppender",
    )
    private val mainFileNames = setOf("file", "serverlog")

    @JvmStatic
    fun install(logDirectory: Path): AutoCloseable {
        val context = LogManager.getContext(false) as? LoggerContext
        if (context == null) {
            LogManager.getLogger(ArcDiagnosticLogging::class.java).warn("ARC diagnostic file requires Log4j Core; main logging remains active")
            return noOp
        }
        return install(context, logDirectory)
    }

    internal fun install(
        context: LoggerContext,
        logDirectory: Path,
        fileLayout: Layout<out Serializable>? = null,
    ): AutoCloseable {
        // java.lang.AutoCloseable and the string key also work across independently shaded copies.
        val owner = Owner(context, logDirectory, fileLayout)
        val installed = context.putObjectIfAbsent(OWNER_KEY, owner)
        if (installed != null) return installed as? AutoCloseable ?: noOp
        return try {
            owner.start()
            owner
        } catch (failure: Exception) {
            owner.close()
            context.getLogger(ArcDiagnosticLogging::class.java.name).warn("Cannot install ARC diagnostic file; main logging remains active", failure)
            noOp
        }
    }

    private class Owner(
        private val context: LoggerContext,
        private val directory: Path,
        private val fileLayout: Layout<out Serializable>?,
    ) : AutoCloseable {
        private var binding: Binding? = null
        private var closed = false
        private val monitor = Any()
        private val warned = AtomicBoolean()
        private val listener = PropertyChangeListener { event ->
            if (event.propertyName == LoggerContext.PROPERTY_CONFIG) {
                synchronized(monitor) {
                    if (!closed && binding?.configuration !== context.configuration) {
                        try {
                            attach()
                        } catch (failure: Exception) {
                            warn(failure)
                        }
                    }
                }
            }
        }

        fun start() = synchronized(monitor) {
            if (closed) return@synchronized
            Files.createDirectories(directory)
            context.addPropertyChangeListener(listener)
            attach()
        }

        private fun attach() {
            binding?.close()
            binding = null
            val next = Binding(context.configuration, directory, fileLayout, context, ::warn)
            try {
                next.start()
                binding = next
                context.updateLoggers()
            } catch (failure: Exception) {
                next.close()
                throw failure
            }
        }

        private fun warn(failure: Throwable?) {
            if (warned.compareAndSet(false, true)) {
                context.getLogger(ArcDiagnosticLogging::class.java.name).warn(
                    "ARC diagnostic file unavailable; verbose messages remain in main logging (queue full or file write failed)",
                    failure,
                )
            }
        }

        override fun close() = synchronized(monitor) {
            if (closed) return@synchronized
            closed = true
            context.removePropertyChangeListener(listener)
            context.removeObject(OWNER_KEY, this)
            binding?.close()
            binding = null
            context.updateLoggers()
        }
    }

    private class Binding(
        val configuration: Configuration,
        private val directory: Path,
        private val fileLayout: Layout<out Serializable>?,
        private val context: LoggerContext,
        private val warn: (Throwable?) -> Unit,
    ) : AutoCloseable {
        private val degraded = AtomicBoolean()
        private val attachedLoggers = mutableListOf<LoggerConfig>()
        private val quietTargets = linkedSetOf<Filterable>()
        private val ownedAppenders = mutableListOf<Appender>()
        private val quietFilter = object : AbstractFilter() {
            override fun filter(event: LogEvent): Result =
                if (!degraded.get() && ArcDiagnosticRecords.quiet(event)) Result.DENY else Result.NEUTRAL
        }
        private val fileFilter = object : AbstractFilter() {
            override fun filter(event: LogEvent): Result =
                if (!degraded.get() && ArcDiagnosticRecords.capture(event) &&
                    event.contextData.getValue<String>(CONSOLE_MIRROR_KEY) != "true"
                ) Result.NEUTRAL else Result.DENY
        }

        fun start() {
            check(listOf(FILE_APPENDER, ASYNC_APPENDER, FALLBACK_APPENDER).none(configuration.appenders::containsKey)) {
                "Diagnostic appender name already in use"
            }
            val file = RollingFileAppender.newBuilder()
                .setName(FILE_APPENDER)
                .setConfiguration(configuration)
                .setLayout(fileLayout ?: PatternLayout.newBuilder().withPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level [%logger] %msg%n%throwable").build())
                .setIgnoreExceptions(false)
                .withFileName(directory.resolve("arc-debug.log").toString())
                .withFilePattern(directory.resolve("arc-debug-%i.log.gz").toString())
                .withPolicy(SizeBasedTriggeringPolicy.createPolicy("10 MB"))
                .withStrategy(DefaultRolloverStrategy.newBuilder().withMax("7").withConfig(configuration).build())
                .build()
            checkNotNull(file) { "Cannot create diagnostic rolling file" }
            add(file)
            val fallback = object : AbstractAppender(FALLBACK_APPENDER, null, null, true, Property.EMPTY_ARRAY) {
                override fun append(event: LogEvent) {
                    degraded.set(true)
                    warn(null)
                    if (quietTargets.isNotEmpty() && ArcDiagnosticRecords.quiet(event)) {
                        // Never re-emit at root: this would duplicate Loki and could recurse.
                        mainControls(context.configuration, event.loggerName).forEach { control ->
                            runCatching { control.callAppender(event) }
                        }
                    }
                }
            }
            add(fallback)
            val async = AsyncAppender.newBuilder()
                .setName(ASYNC_APPENDER)
                .setConfiguration(configuration)
                .setAppenderRefs(arrayOf(AppenderRef.createAppenderRef(FILE_APPENDER, null, null)))
                .setErrorRef(FALLBACK_APPENDER)
                .setBufferSize(4096)
                .setBlocking(false)
                .setFilter(fileFilter)
                .build()
            add(async)
            val loggers = configuration.loggers.values + configuration.rootLogger
            loggers.forEach { logger ->
                logger.appenders.values.flatMap(::mainLeaves).filterIsInstance<Filterable>().forEach(quietTargets::add)
                if (logger === configuration.rootLogger || !logger.isAdditive) {
                    logger.addAppender(async, null, null)
                    attachedLoggers += logger
                }
            }
            quietTargets.forEach { it.addFilter(quietFilter) }
        }

        private fun add(appender: Appender) {
            ownedAppenders += appender
            configuration.addAppender(appender)
            appender.start()
            check(appender.isStarted) { "Diagnostic appender did not start: ${appender.name}" }
        }

        override fun close() {
            degraded.set(true)
            quietTargets.forEach { it.removeFilter(quietFilter) }
            attachedLoggers.forEach { logger ->
                if (logger.appenders[ASYNC_APPENDER] === ownedAppenders.lastOrNull()) logger.removeAppender(ASYNC_APPENDER)
            }
            // Async stops first and drains the queue before its file/fallback targets stop.
            ownedAppenders.asReversed().forEach { appender ->
                if (configuration.appenders[appender.name] === appender) configuration.appenders.remove(appender.name, appender)
                appender.stop()
            }
            quietTargets.clear()
            attachedLoggers.clear()
            ownedAppenders.clear()
        }
    }

    private fun mainControls(configuration: Configuration, loggerName: String): List<AppenderControl> = buildList {
        var logger: LoggerConfig? = configuration.getLoggerConfig(loggerName)
        val observed = mutableSetOf<Appender>()
        while (logger != null) {
            val current = logger
            current.appenders.values.filter { mainLeaves(it).isNotEmpty() && observed.add(it) }.forEach { appender ->
                val ref = current.appenderRefs.firstOrNull { it.ref == appender.name }
                add(AppenderControl(appender, ref?.level, ref?.filter))
            }
            logger = if (current.isAdditive) current.parent else null
        }
    }

    private fun mainLeaves(appender: Appender): List<Appender> = when {
        appender is AsyncAppender && appender.name != ASYNC_APPENDER -> {
            val children = appender.appenders.map(::mainLeaves)
            if (children.isNotEmpty() && children.all { it.isNotEmpty() }) children.flatten() else emptyList()
        }
        appender.name.lowercase() in mainConsoleNames &&
            (appender is ConsoleAppender || appender.javaClass.name in mainConsoleTypes) -> listOf(appender)
        appender.name.lowercase() in mainFileNames &&
            (appender is RollingFileAppender || appender is FileAppender ||
                appender is RollingRandomAccessFileAppender || appender is RandomAccessFileAppender) -> listOf(appender)
        else -> emptyList()
    }
}

/** Recognizes the documented structured-line grammar; arbitrary prose never becomes a quiet record. */
internal object ArcDiagnosticRecords {
    private val pluginNames = setOf(
        "arc", "arccore", "arcbuilder", "arccivs", "arcfarms", "arcduels", "arcranks", "arcorigin",
        "arcdungeons", "arcmounts", "arccontracts", "arcgiveaways", "arcvotes", "arcecojobs", "arcevents",
        "arccasino", "arcexcellentcrates", "arcjustteams", "trails", "proxyarc",
    )
    private val ansi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val minecraft = Regex("§[0-9a-fk-orx]", RegexOption.IGNORE_CASE)
    private val brand = Regex("^\\[([^]\\r\\n]+)]\\s+")
    private val prefix = Regex("^(ARC_[A-Z0-9_]+|ARCFARMS_DEBUG|ARCCIVS_[A-Z0-9_]+)(?: |$)")
    private val field = Regex("([a-z][a-z0-9_.-]{0,127})=(\"(?:[^\"\\\\\\r\\n]|\\\\[\"\\\\])*\"|[A-Za-z0-9_.:/@+,\\-]+)")
    private val civsTraces = setOf(
        "sql_start", "world_scan", "terraform_native_batch", "container_index", "citizen_route_progress",
        "citizen_route_preparing", "citizen_wander", "citizen_sleep", "citizen_campfire", "citizen_meeting",
        "ui_open", "ui_screen", "ui_click", "ui_dismiss",
    )
    private val outcomeFields = setOf("event", "outcome", "status", "state", "reason", "action")
    private val operatorOutcomes = setOf("failed", "failure", "blocked", "error", "recovered", "policy", "transition")
    private val operatorEvents = setOf("state_changed", "startup_ready")

    fun capture(event: LogEvent): Boolean = firstPartyLogger(event.loggerName) || knownBrand(event.message.formattedMessage)

    fun quiet(event: LogEvent): Boolean {
        if (event.level.isMoreSpecificThan(Level.WARN) || event.thrown != null || event.thrownProxy != null) return false
        if (!firstPartyLogger(event.loggerName) && !knownBrand(event.message.formattedMessage)) return false
        val text = recordText(event)
        if (text?.startsWith("ARC_RUNTIME ") == true || text == "ARC_RUNTIME") return false
        val parsed = text?.let(::parse)
        if (parsed != null && parsed.second.any { (key, value) ->
                key in outcomeFields && value.split('_', '-').any { it in operatorOutcomes } ||
                    key == "event" && value in operatorEvents
            }) return false
        if (event.level.isLessSpecificThan(Level.DEBUG)) return capture(event)
        val (name, fields) = parsed ?: return false
        return when (name) {
            "ARC_HEALTH" -> fields["state"] == "up" && fields["ready"] == "true"
            "ARC_VISUAL_PACKETS" -> fields["status"] in setOf("interval", "source")
            "ARCFARMS_DEBUG", "ARC_BUILDER_ACTION", "ARC_BUILDER_TOOLS" -> true
            "ARC_CIVS" -> fields["event"] in civsTraces
            else -> false
        }
    }

    private fun firstPartyLogger(name: String): Boolean =
        name == "ru.arc" || name.startsWith("ru.arc.") || name == "ru.ruscrafting" ||
            name.startsWith("ru.ruscrafting.") || name.lowercase() in pluginNames

    private fun knownBrand(message: String): Boolean {
        val plain = minecraft.replace(ansi.replace(LogFormat.plainForBuffer(message), ""), "").trim()
        return brand.find(plain)?.groupValues?.get(1)?.lowercase() in pluginNames
    }

    private fun recordText(event: LogEvent): String? {
        var text = minecraft.replace(ansi.replace(LogFormat.plainForBuffer(event.message.formattedMessage), ""), "").trim()
        val wrapper = brand.find(text)
        if (wrapper != null) {
            if (wrapper.groupValues[1].lowercase() !in pluginNames) return null
            text = text.substring(wrapper.range.last + 1)
        }
        return text.removePrefix("[DEBUG] ")
    }

    private fun parse(text: String): Pair<String, Map<String, String>>? {
        val name = prefix.find(text)?.groupValues?.get(1) ?: return null
        val fields = linkedMapOf<String, String>()
        var cursor = name.length
        while (cursor < text.length) {
            if (text[cursor++] != ' ') return null
            val match = field.matchAt(text, cursor) ?: return null
            val key = match.groupValues[1]
            if (fields.put(key, match.groupValues[2]) != null) return null
            cursor = match.range.last + 1
        }
        return if (fields.isEmpty()) null else name to fields
    }
}
