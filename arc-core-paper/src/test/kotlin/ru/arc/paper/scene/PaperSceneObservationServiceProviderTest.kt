package ru.arc.paper.scene

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import ru.arc.paper.api.ArcSceneBounds
import ru.arc.paper.api.ArcSceneEntity
import ru.arc.paper.api.ArcSceneLocation
import ru.arc.paper.api.ArcSceneObservationService
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class PaperSceneObservationServiceProviderTest : FreeSpec({
    val world = UUID(0, 1)
    val bounds = ArcSceneBounds(world, 0.0, 0.0, 0.0, 10.0, 100.0, 10.0)
    fun entity(id: String, x: Double = 1.0, worldId: UUID = world) = ArcSceneEntity(worldId, id, "PLAYER", "Resident",
        x, 64.0, 1.0, 0f, 0f, 0f, "STANDING", 1.0, false, null, emptyList())

    "queries capture on Main enforce bounds and hand only remaining capacity to each source" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcCore")
            val plugin = paper.createSimplePlugin("Residents")
            PaperSceneObservationServiceProvider(host).use { service ->
                var secondLimit = 0
                val first = service.registerEntitySource(plugin, "first") { captureBounds, limit ->
                    Bukkit.isPrimaryThread() shouldBe true
                    captureBounds shouldBe bounds
                    limit shouldBe 3
                    listOf(entity("one"), entity("edge", 10.0), entity("foreign", worldId = UUID(0, 2)))
                }
                service.registerEntitySource(plugin, "second") { _, limit ->
                    secondLimit = limit
                    listOf(entity("two"), entity("three"))
                }
                val result = service.entities(bounds, 3)
                result.map { it.id } shouldBe listOf("one", "two", "three")
                secondLimit shouldBe 2
                shouldThrow<UnsupportedOperationException> { (result as MutableList).clear() }
                first.close(); first.close()
                service.entities(bounds, 3).map { it.id } shouldBe listOf("two", "three")
            }
        }
    }

    "registry admission query caps and source overrun fail before unchecked records escape" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcCore")
            val plugin = paper.createSimplePlugin("Residents")
            PaperSceneObservationServiceProvider(host).use { service ->
                val leases = (0 until 32).map { service.registerEntitySource(plugin, "actors:$it") { _, _ -> emptyList() } }
                shouldThrow<IllegalStateException> { service.registerEntitySource(plugin, "overflow") { _, _ -> emptyList() } }
                shouldThrow<IllegalArgumentException> { service.registerEntitySource(plugin, "actors:0") { _, _ -> emptyList() } }
                leases.forEach(AutoCloseable::close)
                repeat(8) { service.registerLocationSource(plugin, "places:$it") { emptyList() } }
                shouldThrow<IllegalStateException> { service.registerLocationSource(plugin, "overflow") { emptyList() } }
                for (limit in listOf(0, 513)) {
                    shouldThrow<IllegalArgumentException> { service.entities(bounds, limit) }
                    shouldThrow<IllegalArgumentException> { service.locations(limit) }
                }
                service.registerEntitySource(plugin, "overrun") { _, _ -> listOf(entity("one"), entity("two")) }
                shouldThrow<IllegalStateException> { service.entities(bounds, 1) }
            }
        }
    }

    "lease consumer disable and host close remove callbacks and a fresh provider stays independent" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcCore")
            val plugin = paper.createSimplePlugin("Residents")
            val service = PaperSceneObservationServiceProvider(host)
            var calls = 0
            val lease = service.registerLocationSource(plugin, "places") {
                calls++
                listOf(ArcSceneLocation("city:one", "Village", world, 1.0, 64.0, 1.0, 128.0))
            }
            service.locations(1).single().radius shouldBe 128.0
            calls shouldBe 1
            paper.server.pluginManager.disablePlugin(plugin)
            service.locations(1) shouldBe emptyList()
            calls shouldBe 1
            service.close(); service.close(); lease.close()
            paper.server.servicesManager.load(ArcSceneObservationService::class.java) shouldBe null
            shouldThrow<IllegalStateException> { service.locations(1) }
            PaperSceneObservationServiceProvider(host).use { replacement ->
                replacement.locations(512) shouldBe emptyList()
                paper.server.servicesManager.load(ArcSceneObservationService::class.java) shouldBe replacement
                shouldThrow<IllegalStateException> { PaperSceneObservationServiceProvider(host) }
                paper.server.servicesManager.load(ArcSceneObservationService::class.java) shouldBe replacement
                val failure = AtomicReference<Throwable>()
                Thread { try { replacement.locations(1) } catch (error: Throwable) { failure.set(error) } }.apply { start(); join(5_000) }
                (failure.get() is IllegalStateException) shouldBe true
            }
        }
    }

    "chunk hints are Main-only bounded and stop immediately after lease close disable or listener failure" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcCore")
            val plugin = paper.createSimplePlugin("WorldView")
            PaperSceneObservationServiceProvider(host).use { service ->
                val changed = mutableListOf<Triple<UUID, Int, Int>>()
                val lease = service.registerChunkListener(plugin, "terrain") { worldId, x, z ->
                    Bukkit.isPrimaryThread() shouldBe true
                    changed += Triple(worldId, x, z)
                }
                service.chunkChanged(world, -1, 2)
                changed shouldBe listOf(Triple(world, -1, 2))
                lease.close(); lease.close()
                service.chunkChanged(world, -1, 2)
                changed.size shouldBe 1
                var failures = 0
                service.registerChunkListener(plugin, "failed") { _, _, _ -> failures++; error("observer failed") }
                service.chunkChanged(world, 0, 0)
                service.chunkChanged(world, 0, 0)
                failures shouldBe 1
                repeat(8) { service.registerChunkListener(plugin, "listener:$it") { _, _, _ -> changed += Triple(world, 0, 0) } }
                shouldThrow<IllegalStateException> { service.registerChunkListener(plugin, "overflow") { _, _, _ -> } }
                paper.server.pluginManager.disablePlugin(plugin)
                service.chunkChanged(world, 0, 0)
                changed.size shouldBe 1
            }
        }
    }
})
