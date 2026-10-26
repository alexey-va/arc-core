package ru.arc.logging

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Layout
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.appender.AppenderLoggingException
import org.apache.logging.log4j.core.appender.AsyncAppender
import org.apache.logging.log4j.core.appender.RollingRandomAccessFileAppender
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy
import org.apache.logging.log4j.core.config.AppenderRef
import org.apache.logging.log4j.core.config.DefaultConfiguration
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.config.Reconfigurable
import org.apache.logging.log4j.core.impl.ContextDataFactory
import org.apache.logging.log4j.core.impl.Log4jLogEvent
import org.apache.logging.log4j.core.layout.AbstractStringLayout
import org.apache.logging.log4j.core.layout.PatternLayout
import org.apache.logging.log4j.jul.Log4jBridgeHandler
import org.apache.logging.log4j.message.SimpleMessage
import ru.arc.config.EmptyConfig
import ru.arc.test.DiagnosticMirrorProbe
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val HEALTHY = "ARC_HEALTH component=arc-farms state=up ready=true generated_at_ms=42 recovery_backlog=42990 active_leases=666 schemas=state:1 dependencies=redis:true probes=runtime:up"

class ArcDiagnosticLoggingTest : FreeSpec({
    "formal diagnostics quiet only safe first-party records" {
        listOf(
            HEALTHY,
            "ARC_VISUAL_PACKETS status=interval seconds=30.0 bytes=174 packets=10",
            "ARC_VISUAL_PACKETS status=source source=ArcCivs bytes=12",
            "ARCFARMS_DEBUG event=worksite_scan columns=42",
            "ARC_BUILDER_ACTION action=step blocks=1",
        ).forEach { ArcDiagnosticRecords.quiet(record(it)) shouldBe true }
        listOf(
            "ARC_RUNTIME event=plugin-ready component=arc outcome=ok",
            "ARC_HEALTH component=arc state=down ready=false",
            "ARC_HEALTH component=arc state=starting ready=false",
            "ARC_HEALTH state=up ready=true broken",
            "ARC_HEALTH state=up ready=true ready=false",
            "ARC_VISUAL_PACKETS status=recovered",
            "ARC_VISUAL_PACKETS status=policy limit=10",
            "ARCFARMS_DEBUG event=storage_failed",
            "ARC_CIVS event=unknown",
            "ordinary plugin information",
        ).forEach { ArcDiagnosticRecords.quiet(record(it)) shouldBe false }
        ArcDiagnosticRecords.quiet(record(HEALTHY, Level.WARN)) shouldBe false
        ArcDiagnosticRecords.quiet(record(HEALTHY, failure = IllegalStateException("retained"))) shouldBe false
        ArcDiagnosticRecords.quiet(record(HEALTHY, logger = "third.party.Plugin")) shouldBe false
        ArcDiagnosticRecords.capture(record(HEALTHY, logger = "third.party.Plugin")) shouldBe false
        ArcDiagnosticRecords.quiet(record("\u001b[38;5;7m[ARC] $HEALTHY\u001b[0m", logger = "Minecraft")) shouldBe true
        ArcDiagnosticRecords.quiet(record("[Player] $HEALTHY", logger = "Minecraft")) shouldBe false
    }

    "native Paper async and random-access main file are quiet while Loki retains every event" {
        DiagnosticFixture().use { fixture ->
            val handle = ArcDiagnosticLogging.install(fixture.context, fixture.logs)
            ArcDiagnosticLogging.install(fixture.context, fixture.logs) shouldBe handle
            val logger = fixture.context.getLogger("ArcFarms")
            logger.info(HEALTHY)
            logger.warn("ARC_HEALTH component=arc-farms state=down ready=false")
            logger.error("storage broke", IllegalStateException("original-cause"))
            logger.info("ARC_RUNTIME event=plugin-ready component=arc-farms outcome=ok")
            fixture.context.getLogger("third.party.Plugin").info("unrelated-third-party")
            handle.close()
            handle.close()
            fixture.awaitMain("unrelated-third-party")
            val main = Files.readString(fixture.main)
            main shouldNotContain "generated_at_ms=42"
            main shouldContain "state=down"
            main shouldContain "original-cause"
            main shouldContain "plugin-ready"
            val diagnostic = Files.readString(fixture.logs.resolve("arc-debug.log"))
            diagnostic shouldContain "generated_at_ms=42"
            diagnostic shouldContain "original-cause"
            diagnostic shouldNotContain "unrelated-third-party"
            fixture.loki.events.count { it.message.formattedMessage == HEALTHY } shouldBe 1
            logger.info(HEALTHY.replace("42", "43"))
            fixture.awaitMain("generated_at_ms=43")
        }
    }

    "non-additive first-party routes are captured once and reconfiguration replaces only owned bindings" {
        DiagnosticFixture().use { fixture ->
            val isolated = LoggerConfig("ru.arc.isolated", Level.ALL, false)
            isolated.addAppender(fixture.loki, null, null)
            fixture.context.configuration.addLogger("ru.arc.isolated", isolated)
            fixture.context.updateLoggers()
            val handle = ArcDiagnosticLogging.install(fixture.context, fixture.logs)
            fixture.context.getLogger("ru.arc.isolated.Check").info("isolated-before")
            fixture.context.configuration = fixture.configuration("replacement.log")
            fixture.context.getLogger("ArcCore").info(HEALTHY.replace("42", "44"))
            fixture.context.getLogger("ArcCore").warn("reconfigured-warning")
            handle.close()
            fixture.awaitMain("reconfigured-warning", fixture.logs.resolve("replacement.log"))
            val diagnostic = Files.readString(fixture.logs.resolve("arc-debug.log"))
            diagnostic.split("isolated-before").size shouldBe 2
            diagnostic shouldContain "generated_at_ms=44"
            Files.readString(fixture.logs.resolve("replacement.log")) shouldNotContain "generated_at_ms=44"
            fixture.context.configuration.appenders.containsKey(ArcDiagnosticLogging.ASYNC_APPENDER) shouldBe false
            fixture.context.configuration = fixture.configuration("after-close.log")
            fixture.context.configuration.appenders.containsKey(ArcDiagnosticLogging.ASYNC_APPENDER) shouldBe false
        }
    }

    "JUL plugin logger reaches the same diagnostic routing through the real Log4j bridge" {
        DiagnosticFixture(global = true).use { fixture ->
            val handle = ArcDiagnosticLogging.install(fixture.context, fixture.logs)
            val logger = java.util.logging.Logger.getLogger("ArcFarms")
            val previousParentHandlers = logger.useParentHandlers
            val previousLevel = logger.level
            val handler = Log4jBridgeHandler(false, null, false)
            logger.useParentHandlers = false
            logger.level = java.util.logging.Level.ALL
            logger.addHandler(handler)
            try {
                logger.info("ARCFARMS_DEBUG event=jul-bridge columns=42")
                logger.warning("jul-warning-preserved")
                handle.close()
                fixture.awaitMain("jul-warning-preserved")
                Files.readString(fixture.main) shouldNotContain "event=jul-bridge"
                Files.readString(fixture.logs.resolve("arc-debug.log")) shouldContain "event=jul-bridge"
            } finally {
                logger.removeHandler(handler)
                handler.close()
                logger.useParentHandlers = previousParentHandlers
                logger.level = previousLevel
                handle.close()
            }
        }
    }

    "console mirror is excluded only when the structured record was actually enabled" {
        DiagnosticFixture(global = true).use { fixture ->
            val handle = ArcDiagnosticLogging.install(fixture.context, fixture.logs)
            val previousDisabled = ArcLogging.disableStructuredEmit
            ArcLogging.install(
                platform = object : LoggingPlatform {
                    override val brandTag = "ARC"
                    override fun writeConsole(level: LogLevel, styledLine: String, plainLine: String, throwable: Throwable?) {
                        fixture.context.getLogger("ConsoleBridge").log(level.toLog4j(), "[ARC] $plainLine", throwable)
                    }
                },
                configSource = LoggingConfigSource { EmptyConfig },
            )
            try {
                ArcLogging.disableStructuredEmit = false
                DiagnosticMirrorProbe.emit("ARCFARMS_DEBUG event=paired-record")
                ArcLogging.disableStructuredEmit = true
                DiagnosticMirrorProbe.emit("ARCFARMS_DEBUG event=mirror-only-record")
                ArcLogging.disableStructuredEmit = false
                fixture.context.getLogger(DiagnosticMirrorProbe::class.java.name).level = Level.ERROR
                DiagnosticMirrorProbe.emit("ARCFARMS_DEBUG event=filtered-structured-record")
                handle.close()
                val text = Files.readString(fixture.logs.resolve("arc-debug.log"))
                listOf("event=paired-record", "event=mirror-only-record", "event=filtered-structured-record").forEach {
                    text.split(it).size shouldBe 2
                }
            } finally {
                handle.close()
                ArcLogging.disableStructuredEmit = previousDisabled
                ArcLogging.install(Slf4jLoggingPlatform("arc"), LoggingConfigSource { EmptyConfig })
            }
        }
    }

    "native onChange and direct reconfiguration do not invert the context monitor lock" {
        val fixture = DiagnosticFixture()
        val handle = ArcDiagnosticLogging.install(fixture.context, fixture.logs)
        val monitorHeld = CountDownLatch(1)
        val setterStarted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val direct = fixture.configuration("direct-change.log") { setterStarted.countDown() }
        val nativeChange = Thread.ofPlatform().daemon().unstarted {
            runCatching {
                synchronized(fixture.context) {
                    monitorHeld.countDown()
                    check(setterStarted.await(5, TimeUnit.SECONDS))
                    fixture.context.onChange(Reconfigurable { fixture.configuration("native-change.log") })
                }
            }.onFailure(failure::set)
        }
        val directChange = Thread.ofPlatform().daemon().unstarted {
            runCatching {
                check(monitorHeld.await(5, TimeUnit.SECONDS))
                fixture.context.configuration = direct
            }.onFailure(failure::set)
        }
        try {
            nativeChange.start()
            directChange.start()
            nativeChange.join(5_000)
            directChange.join(5_000)
            nativeChange.isAlive shouldBe false
            directChange.isAlive shouldBe false
            failure.get() shouldBe null
            fixture.context.getLogger("ArcCore").info(HEALTHY.replace("42", "45"))
            handle.close()
            Files.readString(fixture.logs.resolve("arc-debug.log")) shouldContain "generated_at_ms=45"
        } finally {
            if (!nativeChange.isAlive && !directChange.isAlive) {
                handle.close()
                fixture.close()
            }
        }
    }

    "queue overflow restores main output without re-emitting at Loki or blocking on the diagnostic writer" {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val layout = object : AbstractStringLayout(StandardCharsets.UTF_8) {
            override fun toSerializable(event: LogEvent): String {
                if (first.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "test writer was not released" }
                }
                return event.message.formattedMessage + "\n"
            }
        }
        DiagnosticFixture().use { fixture ->
            val handle = ArcDiagnosticLogging.install(fixture.context, fixture.logs, layout)
            val logger = fixture.context.getLogger("ArcFarms")
            logger.info("ARCFARMS_DEBUG event=queue id=first")
            check(entered.await(5, TimeUnit.SECONDS))
            val producer = Thread.ofPlatform().daemon().start {
                repeat(4_110) { logger.info("ARCFARMS_DEBUG event=queue id={}", it) }
            }
            try {
                producer.join(5_000)
                producer.isAlive shouldBe false
                logger.info("ARCFARMS_DEBUG event=after-overflow id=last")
                fixture.awaitMain("event=after-overflow")
                fixture.loki.events.count { it.message.formattedMessage == "ARCFARMS_DEBUG event=queue id=4096" } shouldBe 1
            } finally {
                release.countDown()
                producer.join(5_000)
                handle.close()
            }
        }
    }

    "writer failure restores original diagnostic records in main" {
        val layout = object : AbstractStringLayout(StandardCharsets.UTF_8) {
            override fun toSerializable(event: LogEvent): String = throw AppenderLoggingException("forced write failure")
        }
        DiagnosticFixture().use { fixture ->
            val handle = ArcDiagnosticLogging.install(fixture.context, fixture.logs, layout)
            fixture.context.getLogger("ArcFarms").info("ARCFARMS_DEBUG event=file-failure id=original")
            fixture.awaitMain("event=file-failure")
            handle.close()
            fixture.loki.events.count { it.message.formattedMessage == "ARCFARMS_DEBUG event=file-failure id=original" } shouldBe 1
        }
    }

    "native rollover keeps at most seven compressed archives and the current file" {
        DiagnosticFixture().use { fixture ->
            val handle = ArcDiagnosticLogging.install(fixture.context, fixture.logs)
            val logger = fixture.context.getLogger("ArcFarms")
            val payload = "x".repeat(2_100_000)
            repeat(48) { logger.info("ARCFARMS_DEBUG event=rollover id={} payload={}", it, payload) }
            handle.close()
            val archives = Files.list(fixture.logs).use { paths ->
                paths.filter { it.fileName.toString().matches(Regex("arc-debug-[1-7]\\.log\\.gz")) }.toList()
            }
            archives.size shouldBe 7
            Files.exists(fixture.logs.resolve("arc-debug.log")) shouldBe true
            (Files.size(fixture.logs.resolve("arc-debug.log")) < 12_600_000L) shouldBe true
        }
    }
})

private fun record(message: String, level: Level = Level.INFO, logger: String = "ArcFarms", failure: Throwable? = null): LogEvent =
    Log4jLogEvent.newBuilder().setLoggerName(logger).setLevel(level).setMessage(SimpleMessage(message)).setThrown(failure).build()

private class DiagnosticFixture(global: Boolean = false) : AutoCloseable {
    val logs: Path = Files.createTempDirectory("arc-diagnostic-routing")
    val main: Path = logs.resolve("latest.log")
    val context = if (global) LogManager.getContext(false) as LoggerContext else LoggerContext("arc-test-${UUID.randomUUID()}")
    var loki = DiagnosticCollector()
        private set
    private val original = if (global) context.configuration else null

    init { context.configuration = configuration("latest.log") }

    fun configuration(name: String, onStart: (() -> Unit)? = null): DefaultConfiguration {
        val configuration = object : DefaultConfiguration() {
            override fun start() {
                super.start()
                onStart?.invoke()
            }
        }
        configuration.rootLogger.appenders.keys.toList().forEach(configuration.rootLogger::removeAppender)
        configuration.appenders.clear()
        configuration.rootLogger.level = Level.ALL
        val file = RollingRandomAccessFileAppender.newBuilder()
            .setName("File")
            .setConfiguration(configuration)
            .setLayout(PatternLayout.newBuilder().withPattern("%level %msg%n%throwable").build())
            .withFileName(logs.resolve(name).toString())
            .withFilePattern(logs.resolve("main-%i.log.gz").toString())
            .withPolicy(SizeBasedTriggeringPolicy.createPolicy("100 MB"))
            .withStrategy(DefaultRolloverStrategy.newBuilder().withMax("1").withConfig(configuration).build())
            .build()
        checkNotNull(file)
        configuration.addAppender(file)
        val async = AsyncAppender.newBuilder()
            .setName("Async")
            .setConfiguration(configuration)
            .setAppenderRefs(arrayOf(AppenderRef.createAppenderRef("File", null, null)))
            .setBufferSize(128)
            .build()
        configuration.addAppender(async)
        loki = DiagnosticCollector(loki.events)
        configuration.addAppender(loki)
        configuration.rootLogger.addAppender(async, null, null)
        configuration.rootLogger.addAppender(loki, null, null)
        return configuration
    }

    fun awaitMain(text: String, file: Path = main) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!Files.exists(file) || !Files.readString(file).contains(text)) {
            check(System.nanoTime() < deadline) { "Missing main record: $text" }
            Thread.sleep(5)
        }
    }

    override fun close() {
        if (original == null) context.stop() else context.configuration = original
        logs.toFile().deleteRecursively()
    }
}

private class DiagnosticCollector(
    val events: CopyOnWriteArrayList<LogEvent> = CopyOnWriteArrayList(),
) : AbstractAppender("LokiCollector", null, null, true, Property.EMPTY_ARRAY) {
    override fun append(event: LogEvent) { events += event.toImmutable() }
}
