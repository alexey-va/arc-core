package ru.arc.logging

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.ThreadContext
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.impl.ContextDataFactory
import org.apache.logging.log4j.core.impl.Log4jLogEvent
import org.apache.logging.log4j.message.SimpleMessage
import ru.arc.config.EmptyConfig

class LogContextTest : FreeSpec({
    afterEach { ThreadContext.clearAll() }

    "LogContext.withContext" - {
        "should set and clear MDC keys" {
            LogContext.withContext("board", "Steve", "announce") {
                ThreadContext.get("module") shouldBe "board"
                ThreadContext.get("player") shouldBe "Steve"
                ThreadContext.get("action") shouldBe "announce"
            }
            ThreadContext.get("module").shouldBeNull()
        }
    }
})

class ArcJsonLayoutTest : FreeSpec({
    "LokiLogging.buildLayout" - {
        "should emit JSON with core fields and MDC contextMap" {
            val context = LogManager.getContext(false) as LoggerContext
            val layout = LokiLogging.buildLayout(EmptyConfig, context.configuration)
            val contextData = ContextDataFactory.createContextData()
            contextData.putValue("module", "xaction")
            contextData.putValue("action", "publish")
            val event =
                Log4jLogEvent.newBuilder()
                    .setLoggerName("ru.arc.test.Sample")
                    .setLevel(org.apache.logging.log4j.Level.INFO)
                    .setMessage(SimpleMessage("hello structured"))
                    .setContextData(contextData)
                    .build()
            val line = layout.toSerializable(event)
            line shouldContain "\"level\":\"INFO\""
            line shouldContain "\"logger\":\"ru.arc.test.Sample\""
            line shouldContain "\"message\":\"hello structured\""
            line shouldContain "\"contextMap\":{"
            line shouldContain "\"module\":\"xaction\""
        }
    }
})

class QuietDebugFilterTest : FreeSpec({
    "matchesQuietSource" - {
        val sources = setOf("ru.arc.sync", "ru.arc.repository")

        "should match package prefix" {
            QuietDebugFilter.matchesQuietSource("ru.arc.sync.base.SyncRepo", sources) shouldBe true
            QuietDebugFilter.matchesQuietSource("ru.arc.farm.FarmManager", sources) shouldBe false
        }
    }
})
