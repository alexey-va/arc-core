package ru.arc.paper.scene

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import ru.arc.paper.api.ArcSceneBounds
import ru.arc.paper.api.ArcSceneEntityInfo
import ru.arc.paper.api.ArcSceneEntityInfoService
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class PaperSceneEntityInfoServiceProviderTest : FreeSpec({
    val bounds = ArcSceneBounds(UUID(0, 1), 0.0, 0.0, 0.0, 10.0, 100.0, 10.0)
    fun info(id: String) = ArcSceneEntityInfo(id, "resident", "Анна", emptyList())

    "Main queries pass detached requested IDs filter foreign and duplicate records and share remaining capacity" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcCore")
            val plugin = paper.createSimplePlugin("Residents")
            PaperSceneEntityInfoServiceProvider(host).use { service ->
                val requested = linkedSetOf("one", "two", "three")
                service.registerSource(plugin, "first") { captureBounds, ids, limit ->
                    Bukkit.isPrimaryThread() shouldBe true
                    captureBounds shouldBe bounds
                    limit shouldBe 3
                    shouldThrow<UnsupportedOperationException> { (ids as MutableSet).clear() }
                    requested.clear()
                    ids shouldBe setOf("one", "two", "three")
                    listOf(info("one"), info("one"), info("foreign"))
                }
                service.registerSource(plugin, "second") { _, ids, limit ->
                    ids shouldBe setOf("one", "two", "three")
                    limit shouldBe 2
                    listOf(info("two"), info("three"))
                }
                val result = service.information(bounds, requested, 3)
                result.map { it.entityId } shouldBe listOf("one", "two", "three")
                shouldThrow<UnsupportedOperationException> { (result as MutableList).clear() }
            }
        }
    }

    "registry query and source record limits fail without exposing partial results" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcCore")
            PaperSceneEntityInfoServiceProvider(plugin).use { service ->
                val leases = (0 until 8).map { service.registerSource(plugin, "source:$it") { _, _, _ -> emptyList() } }
                shouldThrow<IllegalStateException> { service.registerSource(plugin, "overflow") { _, _, _ -> emptyList() } }
                shouldThrow<IllegalArgumentException> { service.registerSource(plugin, "source:0") { _, _, _ -> emptyList() } }
                for (limit in listOf(0, 65)) shouldThrow<IllegalArgumentException> { service.information(bounds, setOf("one"), limit) }
                shouldThrow<IllegalArgumentException> { service.information(bounds, (0..64).map { "id:$it" }.toSet(), 64) }
                shouldThrow<IllegalArgumentException> { service.information(bounds, setOf("x".repeat(129)), 1) }
                shouldThrow<IllegalArgumentException> { service.information(bounds, setOf(""), 1) }
                leases.forEach(AutoCloseable::close)
                service.registerSource(plugin, "overrun") { _, _, _ -> listOf(info("one"), info("two")) }
                service.information(bounds, emptySet(), 1) shouldBe emptyList()
                shouldThrow<IllegalStateException> { service.information(bounds, setOf("one", "two"), 1) }
            }
        }
    }

    "lease disable host close and replacement provider never reuse old callbacks" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcCore")
            val plugin = paper.createSimplePlugin("Residents")
            val service = PaperSceneEntityInfoServiceProvider(host)
            var calls = 0
            val lease = service.registerSource(plugin, "residents") { _, _, _ -> calls++; listOf(info("one")) }
            service.information(bounds, setOf("one"), 1).size shouldBe 1
            lease.close(); lease.close()
            service.information(bounds, setOf("one"), 1) shouldBe emptyList()
            service.registerSource(plugin, "residents") { _, _, _ -> calls++; listOf(info("one")) }
            paper.server.pluginManager.disablePlugin(plugin)
            service.information(bounds, setOf("one"), 1) shouldBe emptyList()
            calls shouldBe 1
            service.close(); service.close(); lease.close()
            paper.server.servicesManager.load(ArcSceneEntityInfoService::class.java) shouldBe null
            shouldThrow<IllegalStateException> { service.information(bounds, setOf("one"), 1) }
            PaperSceneEntityInfoServiceProvider(host).use { replacement ->
                replacement.information(bounds, setOf("one"), 1) shouldBe emptyList()
                shouldThrow<IllegalStateException> { PaperSceneEntityInfoServiceProvider(host) }
                paper.server.servicesManager.load(ArcSceneEntityInfoService::class.java) shouldBe replacement
                val failure = AtomicReference<Throwable>()
                Thread { try { replacement.information(bounds, setOf("one"), 1) } catch (error: Throwable) { failure.set(error) } }
                    .apply { start(); join(5_000) }
                (failure.get() is IllegalStateException) shouldBe true
            }
        }
    }

    "a source removed during capture cannot publish its returned record and exceptions fail the query" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcCore")
            PaperSceneEntityInfoServiceProvider(plugin).use { service ->
                lateinit var lease: AutoCloseable
                lease = service.registerSource(plugin, "retiring") { _, _, _ -> lease.close(); listOf(info("one")) }
                service.information(bounds, setOf("one"), 1) shouldBe emptyList()
                service.registerSource(plugin, "good") { _, _, _ -> listOf(info("one")) }
                service.registerSource(plugin, "failed") { _, _, _ -> error("source failed") }
                shouldThrow<IllegalStateException> { service.information(bounds, setOf("one", "two"), 2) }
            }
        }
    }
})
