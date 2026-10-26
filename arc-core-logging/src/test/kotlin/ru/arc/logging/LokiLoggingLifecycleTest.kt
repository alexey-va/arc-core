package ru.arc.logging

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.ConfigurationListener
import org.apache.logging.log4j.core.config.DefaultConfiguration
import pl.tkowalcz.tjahzi.log4j2.LokiAppender
import pl.tkowalcz.tjahzi.log4j2.LokiAppenderBuilder
import ru.arc.config.Config
import java.nio.file.Files

class LokiLoggingLifecycleTest : FreeSpec({
    "close removes exact listener and appender identities across generations without removing a replacement" {
        val directory = Files.createTempDirectory("arc-logging-lifecycle")
        Files.writeString(directory.resolve("logging.yml"), "enabled: true\nlabels:\n  service_name: test\n")
        val configuration = spyk(DefaultConfiguration())
        val listeners = mutableListOf<ConfigurationListener>()
        every { configuration.addListener(capture(listeners)) } answers { callOriginal() }
        val context = mockk<LoggerContext>(relaxed = true) { every { this@mockk.configuration } returns configuration }
        val root = mockk<Logger>(relaxed = true) { every { this@mockk.context } returns context }
        val first = mockk<LokiAppender>(relaxed = true) {
            every { name } returns "LifecycleLoki"
            every { isStarted } returns true
        }
        val second = mockk<LokiAppender>(relaxed = true) {
            every { name } returns "LifecycleLoki"
            every { isStarted } returns true
        }
        val replacement = mockk<LokiAppender>(relaxed = true) { every { name } returns "LifecycleLoki" }
        LokiLogging.close()
        mockkStatic(LogManager::class)
        mockkConstructor(LokiAppenderBuilder::class)
        every { LogManager.getRootLogger() } returns root
        every { anyConstructed<LokiAppenderBuilder<*>>().build() } returnsMany listOf(first, second)
        try {
            val config = Config(directory, "logging.yml")
            LokiLogging.install(config, LokiAttachTarget.ROOT, "ru.arc", "LifecycleLoki") shouldBe true
            configuration.rootLogger.appenders["LifecycleLoki"] shouldBeSameInstanceAs first
            val oldListener = listeners.single()
            LokiLogging.close()
            LokiLogging.close()
            configuration.rootLogger.appenders.containsKey("LifecycleLoki") shouldBe false
            configuration.appenders.containsKey("LifecycleLoki") shouldBe false
            verify(exactly = 1) { configuration.removeListener(oldListener); first.stop() }
            oldListener.onChange(mockk())
            listeners.size shouldBe 1
            LokiLogging.reinstallFromState() shouldBe false

            LokiLogging.install(config, LokiAttachTarget.ROOT, "ru.arc", "LifecycleLoki") shouldBe true
            oldListener.onChange(mockk())
            verify(exactly = 2) { context.updateLoggers() }
            configuration.appenders["LifecycleLoki"] = replacement
            configuration.rootLogger.removeAppender("LifecycleLoki")
            configuration.rootLogger.addAppender(replacement, org.apache.logging.log4j.Level.INFO, null)
            configuration.appenders["LifecycleLoki"] shouldBeSameInstanceAs replacement
            LokiLogging.close()
            configuration.appenders["LifecycleLoki"] shouldBeSameInstanceAs replacement
            configuration.rootLogger.appenders["LifecycleLoki"] shouldBeSameInstanceAs replacement
            verify(exactly = 1) { configuration.removeListener(listeners.last()); second.stop() }
            verify(exactly = 0) { replacement.stop() }
        } finally {
            LokiLogging.close()
            unmockkConstructor(LokiAppenderBuilder::class)
            unmockkStatic(LogManager::class)
            directory.toFile().deleteRecursively()
        }
    }
})
