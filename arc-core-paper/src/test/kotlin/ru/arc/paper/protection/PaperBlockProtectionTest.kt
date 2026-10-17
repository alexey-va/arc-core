package ru.arc.paper.protection

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.Called
import io.mockk.verify
import org.bukkit.block.Block
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcBlockProtectionService
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PaperBlockProtectionTest : FunSpec({
    test("all priorities participate and changed registrations are visible immediately") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("protected")
            val block = world.getBlockAt(-17, 70, 31)
            PaperBlockProtection.isProtected(block) shouldBe false
            PaperBlockProtection.permitsSchematicMutation("builder", world.uid, -17, 70, 31) shouldBe true
            val plugin = paper.createSimplePlugin("Protection")
            val services = paper.server.servicesManager
            services.register(ArcBlockProtectionService::class.java, ArcBlockProtectionService { _, _, _, _ -> false }, plugin, ServicePriority.Highest)
            var protected = true
            val restrictive = ArcBlockProtectionService { id, x, y, z ->
                protected && id == world.uid && x == -17 && y == 70 && z == 31
            }
            services.register(ArcBlockProtectionService::class.java, restrictive, plugin, ServicePriority.Lowest)
            PaperBlockProtection.isProtected(block) shouldBe true
            PaperBlockProtection.permitsSchematicMutation("builder", world.uid, -17, 70, 31) shouldBe false
            PaperBlockProtection.isProtected(world.uid, -17, 69, 31) shouldBe false
            PaperBlockProtection.isProtected(UUID.randomUUID(), -17, 70, 31) shouldBe false
            protected = false
            PaperBlockProtection.isProtected(block) shouldBe false
            protected = true
            services.unregister(ArcBlockProtectionService::class.java, restrictive)
            PaperBlockProtection.isProtected(block) shouldBe false
        }
    }

    test("a feature can permit its owner without overriding another feature") {
        protectionScenario { paper ->
            val plugin = paper.createSimplePlugin("Protection")
            val world = UUID.randomUUID()
            val owner = object : ArcBlockProtectionService {
                override fun isProtected(worldId: UUID, x: Int, y: Int, z: Int) = true
                override fun permitsSchematicMutation(owner: String, worldId: UUID, x: Int, y: Int, z: Int) = owner == "city"
            }
            paper.server.servicesManager.register(ArcBlockProtectionService::class.java, owner, plugin, ServicePriority.Highest)
            PaperBlockProtection.isProtected(world, 1, 2, 3) shouldBe true
            PaperBlockProtection.permitsSchematicMutation("city", world, 1, 2, 3) shouldBe true
            PaperBlockProtection.permitsSchematicMutation("builder", world, 1, 2, 3) shouldBe false
            paper.server.servicesManager.register(ArcBlockProtectionService::class.java,
                ArcBlockProtectionService { _, _, _, _ -> true }, plugin, ServicePriority.Lowest)
            PaperBlockProtection.permitsSchematicMutation("city", world, 1, 2, 3) shouldBe false
        }
    }

    test("provider exceptions and disabled registrations fail closed") {
        protectionScenario { paper ->
            val plugin = paper.createSimplePlugin("Protection")
            val world = UUID.randomUUID()
            val broken = ArcBlockProtectionService { _, _, _, _ -> error("State unavailable") }
            paper.server.servicesManager.register(ArcBlockProtectionService::class.java, broken, plugin, ServicePriority.Normal)
            PaperBlockProtection.isProtected(world, 0, 0, 0) shouldBe true
            PaperBlockProtection.permitsSchematicMutation("builder", world, 0, 0, 0) shouldBe false
            paper.server.servicesManager.unregister(ArcBlockProtectionService::class.java, broken)
            val disabled = mockk<Plugin>()
            io.mockk.every { disabled.isEnabled } returns false
            val provider = mockk<ArcBlockProtectionService>()
            paper.server.servicesManager.register(ArcBlockProtectionService::class.java, provider, disabled, ServicePriority.Normal)
            PaperBlockProtection.isProtected(world, 0, 0, 0) shouldBe true
            PaperBlockProtection.permitsSchematicMutation("builder", world, 0, 0, 0) shouldBe false
            verify { provider wasNot Called }
        }
    }

    test("off-thread queries deny without accessing block or provider state") {
        protectionScenario { paper ->
            val plugin = paper.createSimplePlugin("Protection")
            val block = mockk<Block>()
            val provider = mockk<ArcBlockProtectionService>()
            paper.server.servicesManager.register(ArcBlockProtectionService::class.java, provider, plugin, ServicePriority.Normal)
            CompletableFuture.supplyAsync {
                listOf(PaperBlockProtection.isProtected(block),
                    PaperBlockProtection.isProtected(UUID.randomUUID(), 1, 2, 3),
                    PaperBlockProtection.permitsSchematicMutation("builder", UUID.randomUUID(), 1, 2, 3))
            }.get(5, TimeUnit.SECONDS) shouldBe listOf(true, true, false)
            verify { block wasNot Called; provider wasNot Called }
        }
    }

    test("shared contract retains a Java default method and a single abstract check") {
        ArcBlockProtectionService::class.java.methods.filter { java.lang.reflect.Modifier.isAbstract(it.modifiers) }
            .map { it.name } shouldBe listOf("isProtected")
        ArcBlockProtectionService::class.java.methods.single { it.name == "permitsSchematicMutation" }.isDefault shouldBe true
    }
})

private fun protectionScenario(block: (MockBukkitTestRuntime) -> Unit) {
    MockBukkitTestRuntime.open().use { paper -> failOnUnsupportedMockBukkitOperation { block(paper) } }
}
