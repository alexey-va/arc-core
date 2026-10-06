package ru.arc.paper.packet

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.api.ArcVisualPacketBudget
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class PaperVisualPacketProviderTest : FreeSpec({
    "one host registers the shared provider before consumers and removes it on close" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ARC")
            val consumer = paper.createSimplePlugin("ArcBuilder")
            val config = Config(Files.createTempDirectory("visual-provider"), "visual-packets.yml")
            shouldThrow<IllegalArgumentException> { PaperVisualPackets(consumer, "preview") }
            PaperArcRuntime.installScheduling(host)
            try {
                PaperVisualPacketRuntime.install(host, config).use { provider ->
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
})
