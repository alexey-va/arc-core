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

class PaperVisualPacketProviderTest : FreeSpec({
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
