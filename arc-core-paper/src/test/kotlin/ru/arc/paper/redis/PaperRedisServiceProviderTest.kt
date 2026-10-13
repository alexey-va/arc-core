package ru.arc.paper.redis

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.event.server.PluginDisableEvent
import ru.arc.paper.api.ArcRedisListener
import ru.arc.paper.api.ArcRedisService
import ru.arc.paper.api.ArcRedisSettings
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisConnection
import ru.arc.redis.RedisManager
import ru.arc.redis.RedisTelemetrySink

class PaperRedisServiceProviderTest : FreeSpec({
    "connection groups include every credential and origin field and primary changes only future leases" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcHost")
            val factory = RecordingRedisFactory()
            PaperRedisServiceProvider(host, factory::create).use { provider ->
                val service = checkNotNull(host.server.servicesManager.load(ArcRedisService::class.java))
                val base = ArcRedisSettings("redis.internal", 6379, "arc", "secret-one")

                val leases = listOf(
                    service.acquire("one", base, "survival"),
                    service.acquire("two", base, "survival"),
                    service.acquire("one", base.copy(host = "redis-backup"), "survival"),
                    service.acquire("one", base.copy(port = 6380), "survival"),
                    service.acquire("one", base.copy(username = "other-user"), "survival"),
                    service.acquire("one", base.copy(password = "secret-two"), "survival"),
                    service.acquire("one", base, "lobby"),
                )

                factory.handles.size shouldBe 6
                provider.diagnostics().activeLeases shouldBe 7
                provider.diagnostics().connectionGroups shouldBe 6
                provider.diagnostics().connectedGroups shouldBe 6

                provider.configurePrimary(base, "survival")
                val firstPrimary = service.acquirePrimary("primary-one")
                factory.handles.size shouldBe 6
                provider.configurePrimary(base.copy(port = 6390), "survival")
                val secondPrimary = service.acquirePrimary("primary-two")
                factory.handles.size shouldBe 7
                provider.clearPrimary()
                shouldThrow<IllegalStateException> { service.acquirePrimary("after-clear") }
                firstPrimary.isConnected() shouldBe true
                secondPrimary.isConnected() shouldBe true

                val telemetry = mockk<RedisTelemetrySink>(relaxed = true)
                provider.installTelemetry(telemetry)
                factory.handles.forEach { handle ->
                    verify(exactly = 1) { handle.manager.installTelemetry(telemetry) }
                }

                (leases + firstPrimary + secondPrimary).forEach(AutoCloseable::close)
                provider.diagnostics().activeLeases shouldBe 0
                provider.diagnostics().connectionGroups shouldBe 0
                factory.handles.forEach { handle -> verify(exactly = 1) { handle.manager.close() } }
            }
        }
    }

    "one manager dispatcher multiplexes lease callbacks and close removes only its owner" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcHost")
            val factory = RecordingRedisFactory()
            PaperRedisServiceProvider(host, factory::create).use { provider ->
                val service = checkNotNull(host.server.servicesManager.load(ArcRedisService::class.java))
                val settings = ArcRedisSettings("localhost", 6379, null, null)
                val first = service.acquire("first", settings, "survival")
                val second = service.acquire("second", settings, "survival")
                val received = mutableListOf<String>()
                val firstListener = ArcRedisListener { channel, message, origin ->
                    received += "first:$channel:$message:$origin"
                }
                val secondOnlyListener = ArcRedisListener { _, _, _ -> Unit }
                val secondListener = ArcRedisListener { channel, message, origin ->
                    received += "second:$channel:$message:$origin"
                }
                val replacementListener = ArcRedisListener { channel, message, origin ->
                    received += "replacement:$channel:$message:$origin"
                }
                val manager = factory.handles.single().manager
                val dispatchers = factory.handles.single().dispatchers

                first.registerChannelUnique("arc:events", firstListener)
                second.registerChannelUnique("arc:events", secondListener)
                second.registerChannelUnique("arc:peer-only", secondOnlyListener)
                first.channels() shouldBe setOf("arc:events")
                first.channelCount() shouldBe 1
                second.channels() shouldBe setOf("arc:events", "arc:peer-only")
                second.channelCount() shouldBe 2
                verify(exactly = 1) { manager.registerChannelUnique("arc:events", any()) }
                dispatchers.getValue("arc:events").consume("arc:events", "one", "wire-origin")
                received shouldBe listOf(
                    "first:arc:events:one:wire-origin",
                    "second:arc:events:one:wire-origin",
                )

                second.registerChannelUnique("arc:events", replacementListener)
                second.unregisterChannel("arc:events", secondListener)
                dispatchers.getValue("arc:events").consume("arc:events", "two", "wire-origin")
                received.takeLast(2) shouldBe listOf(
                    "first:arc:events:two:wire-origin",
                    "replacement:arc:events:two:wire-origin",
                )

                second.close()
                verify(exactly = 1) { manager.unregisterChannel("arc:peer-only", any()) }
                verify(exactly = 0) { manager.unregisterChannel("arc:events", any()) }
                dispatchers.getValue("arc:events").consume("arc:events", "three", "wire-origin")
                received.last() shouldBe "first:arc:events:three:wire-origin"
                provider.diagnostics().activeChannels shouldBe 1

                first.close()
                verify(exactly = 1) { manager.unregisterChannel("arc:events", any()) }
                verify(exactly = 1) { manager.close() }
                provider.diagnostics().activeChannels shouldBe 0
            }
        }
    }

    "consumer disable event does not preempt explicit client shutdown" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcHost")
            val consumer = paper.createSimplePlugin("RedisConsumer")
            val factory = RecordingRedisFactory()
            val provider = PaperRedisServiceProvider(host, factory::create)
            val service = checkNotNull(host.server.servicesManager.load(ArcRedisService::class.java))
            provider.configurePrimary(ArcRedisSettings("localhost", 6379, null, null), "survival")

            val activeClient = PaperRedisClient.openPrimary(consumer)
            paper.callEvent(PluginDisableEvent(consumer))
            activeClient.publish("arc:shutdown", "withdraw")

            verify(exactly = 1) { factory.handles.single().manager.publish("arc:shutdown", "withdraw") }
            verify(exactly = 0) { factory.handles.single().manager.close() }
            activeClient.close()
            verify(exactly = 1) { factory.handles.single().manager.close() }
            provider.close()
            service.diagnostics().activeLeases shouldBe 0
        }
    }

    "failed asynchronous operations are completed exceptionally after lease close" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcHost")
            val factory = RecordingRedisFactory()
            PaperRedisServiceProvider(host, factory::create).use { provider ->
                val service = checkNotNull(host.server.servicesManager.load(ArcRedisService::class.java))
                val lease = service.acquire(
                    "consumer",
                    ArcRedisSettings("localhost", 6379, null, null),
                    "survival",
                )
                lease.close()
                lease.loadMap("players").isCompletedExceptionally shouldBe true
                lease.healthCheck().isCompletedExceptionally shouldBe true
                shouldThrow<IllegalStateException> { lease.init() }
            }
        }
    }
})

private class RecordingRedisFactory {
    val handles = mutableListOf<RecordingRedisManager>()

    fun create(connection: RedisConnection, origin: String): RedisManager {
        val manager = mockk<RedisManager>(relaxed = true)
        val registeredListeners = linkedMapOf<String, ChannelListener>()
        every { manager.isConnected() } returns true
        every { manager.isSubscriptionActive() } returns true
        every { manager.getChannelCount() } answers { registeredListeners.size }
        every { manager.getChannels() } answers { registeredListeners.keys.toSet() }
        every { manager.registerChannelUnique(any(), any()) } answers {
            registeredListeners[firstArg<String>()] = secondArg<ChannelListener>()
        }
        every { manager.unregisterChannel(any(), any()) } answers {
            registeredListeners.remove(firstArg<String>())
        }
        handles += RecordingRedisManager(connection, origin, manager, registeredListeners)
        return manager
    }
}

private data class RecordingRedisManager(
    val connection: RedisConnection,
    val origin: String,
    val manager: RedisManager,
    val dispatchers: MutableMap<String, ChannelListener>,
)
