package ru.arc.paper.redis

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import redis.clients.jedis.Jedis
import ru.arc.paper.api.ArcRedisListener
import ru.arc.paper.api.ArcRedisService
import ru.arc.paper.api.ArcRedisSettings
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.redis.RedisWire
import ru.arc.testing.containers.RedisTestService
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class PaperRedisServiceProviderIntegrationTest : FreeSpec({
    "shared lease topology replacement keeps the live Redis subscriber and peer delivery" {
        RedisTestService.start().use { redis ->
            MockBukkitTestRuntime.open().use { paper ->
                val host = paper.createSimplePlugin("ArcHost")
                PaperRedisServiceProvider(host).use { provider ->
                    val service = checkNotNull(host.server.servicesManager.load(ArcRedisService::class.java))
                    val settings = ArcRedisSettings(redis.endpoint.host, redis.endpoint.port, null, null)
                    val first = service.acquire("first", settings, "survival")
                    val second = service.acquire("second", settings, "survival")
                    val prefix = "arc:integration:redis-provider:${UUID.randomUUID()}"
                    val oldOne = "$prefix:old-one"
                    val oldTwo = "$prefix:old-two"
                    val newOne = "$prefix:new-one"
                    val newTwo = "$prefix:new-two"
                    val final = "$prefix:final"
                    val events = ConcurrentLinkedQueue<String>()
                    val firstOldOne = recordingListener("first-old-one", events)
                    val firstOldTwo = recordingListener("first-old-two", events)
                    val secondOldOne = recordingListener("second-old-one", events)
                    val newOneListener = recordingListener("new-one", events)
                    val newTwoListener = recordingListener("new-two", events)
                    val finalListener = recordingListener("final", events)

                    first.registerChannelUnique(oldOne, firstOldOne)
                    first.registerChannelUnique(oldTwo, firstOldTwo)
                    second.registerChannelUnique(oldOne, secondOldOne)

                    Jedis(settings.host, settings.port).use { publisher ->
                        awaitChannelSet(publisher, setOf(oldOne, oldTwo))
                        publisher.publish(oldOne, RedisWire.encode("peer", "initial"))
                        awaitEvents(
                            events,
                            setOf(
                                "first-old-one:$oldOne:initial:peer",
                                "second-old-one:$oldOne:initial:peer",
                            ),
                        )

                        second.unregisterChannel(oldOne, secondOldOne)
                        first.registerChannelUnique(newOne, newOneListener)
                        first.registerChannelUnique(newTwo, newTwoListener)
                        first.unregisterChannel(oldOne, firstOldOne)
                        first.unregisterChannel(oldTwo, firstOldTwo)

                        awaitChannelSet(publisher, setOf(newOne, newTwo))
                        first.isSubscriptionActive() shouldBe true
                        publisher.publish(newOne, RedisWire.encode("peer", "replacement"))
                        awaitEvents(events, setOf("new-one:$newOne:replacement:peer"))

                        first.registerChannelUnique(final, finalListener)
                        first.unregisterChannel(newOne, newOneListener)
                        first.unregisterChannel(newTwo, newTwoListener)
                        awaitChannelSet(publisher, setOf(final))
                        first.isSubscriptionActive() shouldBe true
                        publisher.publish(final, RedisWire.encode("peer", "still-live"))
                        awaitEvents(events, setOf("final:$final:still-live:peer"))

                        first.close()
                        awaitChannelSet(publisher, emptySet())
                        second.close()
                    }

                    provider.diagnostics().activeLeases shouldBe 0
                    provider.diagnostics().connectionGroups shouldBe 0
                }
            }
        }
    }
})

private fun recordingListener(
    id: String,
    events: MutableCollection<String>,
): ArcRedisListener = ArcRedisListener { channel, message, origin ->
    events += "$id:$channel:$message:$origin"
}

private fun awaitChannelSet(
    redis: Jedis,
    expected: Set<String>,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
        if (redis.pubsubChannels().toSet().filter { it.startsWith("arc:integration:redis-provider:") }.toSet() == expected) {
            return
        }
        Thread.sleep(20)
    }
    check(false) {
        "Redis pub/sub channels did not converge to expected topology $expected; current channels are " +
            redis.pubsubChannels().filter { it.startsWith("arc:integration:redis-provider:") }
    }
}

private fun awaitEvents(
    events: Collection<String>,
    expected: Set<String>,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline && !events.containsAll(expected)) Thread.sleep(20)
    events.containsAll(expected) shouldBe true
}
