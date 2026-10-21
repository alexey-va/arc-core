package ru.arc.paper.packet

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.api.ArcVisualPacketBudget
import ru.arc.paper.api.VisualPacketAdmission
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.logging.Handler
import java.util.logging.LogRecord

class PaperVisualPacketProviderTest : FreeSpec({
    "effective policy is logged on host activation and reload without requiring traffic or overload" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcCore")
            val messages = mutableListOf<String>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { messages += record.message }
                override fun flush() = Unit
                override fun close() = Unit
            }
            host.logger.addHandler(handler)
            try {
                PaperVisualPacketRuntime.installPending(host).use { provider ->
                    messages.none { "status=policy" in it } shouldBe true
                    val policy = PaperVisualPacketRuntime.Settings(sourceViewerMbps = mapOf("game:video" to 24.0))
                    provider.updateSettings(policy)
                    messages.any { "status=policy" in it && "server_limit_mbps=32.0" in it && "viewer_limit_mbps=2.0" in it } shouldBe true
                    messages.any { "status=source_policy" in it && "source=game:video" in it && "viewer_limit_mbps=24.0" in it } shouldBe true
                    messages.clear()
                    provider.updateSettings(policy.copy(sourceViewerMbps = emptyMap()))
                    messages.count { "status=policy" in it && "source_overrides=0" in it } shouldBe 1
                    messages.none { "status=source_policy" in it } shouldBe true
                    provider.snapshot().totals.attempts shouldBe 0L
                }
            } finally { host.logger.removeHandler(handler) }
        }
    }

    "ArcCore provides one budget without ARC or a global scheduler and removes it on close" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcCore")
            val consumer = paper.createSimplePlugin("ArcBuilder")
            val config = Config(Files.createTempDirectory("visual-provider"), "visual-packets.yml")
            Tasks.reset()
            shouldThrow<IllegalArgumentException> { PaperVisualPackets(consumer, "preview") }
            try {
                PaperVisualPacketRuntime.install(host, config).use { provider ->
                    shouldThrow<IllegalStateException> { Tasks.scheduler }
                    (host.server.servicesManager.load(ArcVisualPacketBudget::class.java) === provider) shouldBe true
                    (consumer.server.servicesManager.load(ArcVisualPacketBudget::class.java) === provider) shouldBe true
                    PaperVisualPackets(host, "workshop")
                    PaperVisualPackets(consumer, "preview")
                    shouldThrow<IllegalStateException> { PaperVisualPacketRuntime.install(consumer, config) }
                }
                host.server.servicesManager.load(ArcVisualPacketBudget::class.java) shouldBe null
            } finally {
                Tasks.reset()
            }
        }
    }

    "consumer shutdown and global scheduler reset leave the host budget and reporter alive" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcCore")
            val consumer = paper.createSimplePlugin("ARC")
            val config = Config(Files.createTempDirectory("visual-provider-lifecycle"), "visual-packets.yml")
            Tasks.reset()
            PaperVisualPacketRuntime.install(host, config).use { provider ->
                try {
                    PaperArcRuntime.installScheduling(consumer)
                    paper.server.scheduler.pendingTasks.count { it.owner === host } shouldBe 1
                    paper.server.pluginManager.disablePlugin(consumer)
                    Tasks.reset()

                    paper.server.servicesManager.load(ArcVisualPacketBudget::class.java) shouldBe provider
                    provider.snapshot().closed shouldBe false
                    provider.acquire("arccivs:preview", Any(), 32, 1, false, true) shouldBe VisualPacketAdmission.ALLOWED
                    provider.updateSettings(provider.snapshot().settings.copy(logIntervalSeconds = 60L))
                    paper.server.scheduler.pendingTasks.count { it.owner === host } shouldBe 1
                } finally {
                    Tasks.reset()
                }
            }
            paper.server.scheduler.pendingTasks.count { it.owner === host } shouldBe 0
        }
    }
})
