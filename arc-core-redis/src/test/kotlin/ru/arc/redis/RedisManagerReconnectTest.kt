package ru.arc.redis

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.slf4j.Logger
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.JedisPubSub
import redis.clients.jedis.exceptions.JedisConnectionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CompletionException
import io.kotest.assertions.throwables.shouldThrow

class RedisManagerReconnectTest : FreeSpec({
    "publish reconnects once and retries the message" {
        val subscription = mockk<JedisPooled>(relaxed = true)
        val brokenPublisher = mockk<JedisPooled>(relaxed = true)
        val restoredPublisher = mockk<JedisPooled>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)
        val telemetry = mockk<RedisTelemetrySink>(relaxed = true)
        val delivered = CountDownLatch(1)
        val pools = ArrayDeque(listOf(subscription, brokenPublisher, restoredPublisher))

        every {
            brokenPublisher.publish(any<String>(), any<String>())
        } throws JedisConnectionException("timeout")
        every { restoredPublisher.ping() } returns "PONG"
        every { restoredPublisher.publish("channel", any<String>()) } answers {
            delivered.countDown()
            1L
        }

        val manager =
            RedisManager(
                RedisConnection("localhost", 6379),
                ServerIdentity { "velocity" },
                logger,
                poolFactory = { pools.removeFirst() },
            )
        try {
            manager.installTelemetry(telemetry)
            manager.publish("channel", "payload")

            delivered.await(2, TimeUnit.SECONDS) shouldBe true
            manager.isConnected() shouldBe true
            verify(exactly = 1) {
                restoredPublisher.publish("channel", RedisWire.encode("velocity", "payload"))
            }
            verify(exactly = 1) { logger.info("Redis publish connection restored") }
            verify(exactly = 1) {
                telemetry.onReconnect(RedisReconnectPath.PUBLISH, RedisReconnectResult.ATTEMPT)
            }
            verify(exactly = 1) {
                telemetry.onReconnect(RedisReconnectPath.PUBLISH, RedisReconnectResult.SUCCESS)
            }
            verify(exactly = 1) {
                telemetry.onOperation(
                    RedisOperation.PUBLISH,
                    RedisOperationResult.SUCCESS,
                    any<Long>(),
                )
            }
        } finally {
            manager.close()
        }
    }

    "failed reconnect is backed off instead of retrying every publish" {
        val subscription = mockk<JedisPooled>(relaxed = true)
        val brokenPublisher = mockk<JedisPooled>(relaxed = true)
        val failedReconnect = mockk<JedisPooled>(relaxed = true)
        val secondReconnect = mockk<JedisPooled>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)
        val clock = AtomicLong(100_000L)
        val factoryCalls = AtomicInteger(0)
        val firstFailure = CountDownLatch(1)
        val secondAttempt = CountDownLatch(1)
        val pools = ArrayDeque(listOf(subscription, brokenPublisher, failedReconnect, secondReconnect))

        every {
            brokenPublisher.publish(any<String>(), any<String>())
        } throws JedisConnectionException("timeout")
        every { failedReconnect.ping() } answers {
            firstFailure.countDown()
            throw JedisConnectionException("still down")
        }
        every { secondReconnect.ping() } answers {
            secondAttempt.countDown()
            throw JedisConnectionException("still down")
        }

        val manager =
            RedisManager(
                RedisConnection("localhost", 6379),
                ServerIdentity { "velocity" },
                logger,
                poolFactory = {
                    factoryCalls.incrementAndGet()
                    pools.removeFirst()
                },
                clockMs = clock::get,
            )
        try {
            manager.publish("channel", "first")
            firstFailure.await(2, TimeUnit.SECONDS) shouldBe true

            repeat(10) { manager.publish("channel", "during-backoff-$it") }
            Thread.sleep(150)
            factoryCalls.get() shouldBe 3

            clock.addAndGet(5_000L)
            manager.publish("channel", "after-backoff")
            secondAttempt.await(2, TimeUnit.SECONDS) shouldBe true
            factoryCalls.get() shouldBe 4

            verify(exactly = 1) { logger.warn("Redis publish reconnect failed", any<Exception>()) }
        } finally {
            manager.close()
        }
    }

    "save reports a disconnected manager instead of pretending success" {
        val subscription = mockk<JedisPooled>(relaxed = true)
        val publisher = mockk<JedisPooled>(relaxed = true)
        val pools = ArrayDeque(listOf(subscription, publisher))
        val manager =
            RedisManager(
                RedisConnection("localhost", 6379),
                ServerIdentity { "velocity" },
                poolFactory = { pools.removeFirst() },
            )
        manager.close()

        shouldThrow<CompletionException> {
            manager.saveMapEntries("players", "Steve", "{}").join()
        }
    }

    "loads report a disconnected manager instead of pretending data is absent" {
        val subscription = mockk<JedisPooled>(relaxed = true)
        val publisher = mockk<JedisPooled>(relaxed = true)
        val pools = ArrayDeque(listOf(subscription, publisher))
        val manager =
            RedisManager(
                RedisConnection("localhost", 6379),
                ServerIdentity { "velocity" },
                poolFactory = { pools.removeFirst() },
            )
        manager.close()

        shouldThrow<CompletionException> {
            manager.loadMap("players").join()
        }
        shouldThrow<CompletionException> {
            manager.loadMapEntries("players", "Steve").join()
        }
    }

    "explicit reconnect preserves registered channel listeners" {
        val firstSubscription = mockk<JedisPooled>(relaxed = true)
        val firstPublisher = mockk<JedisPooled>(relaxed = true)
        val secondSubscription = mockk<JedisPooled>(relaxed = true)
        val secondPublisher = mockk<JedisPooled>(relaxed = true)
        val pools =
            ArrayDeque(
                listOf(firstSubscription, firstPublisher, secondSubscription, secondPublisher),
            )
        val manager =
            RedisManager(
                RedisConnection("localhost", 6379),
                ServerIdentity { "velocity" },
                poolFactory = { pools.removeFirst() },
            )
        try {
            manager.registerChannelUnique("arc.updates") { _, _, _ -> }

            manager.connect(RedisConnection("localhost", 6380))

            manager.getChannels() shouldBe setOf("arc.updates")
        } finally {
            manager.close()
        }
    }

    "operation reconnect restores both transport halves after initial connection failure" {
        val restoredSubscription = mockk<JedisPooled>(relaxed = true)
        val restoredPublisher = mockk<JedisPooled>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)
        val subscribed = CountDownLatch(1)
        val pools = ArrayDeque<JedisPooled>()
        var initialAttempt = true
        pools.add(restoredSubscription)
        pools.add(restoredPublisher)

        every { restoredPublisher.ping() } returns "PONG"
        every { restoredSubscription.ping() } returns "PONG"
        every {
            restoredSubscription.subscribe(any<JedisPubSub>(), *anyVararg())
        } answers {
            subscribed.countDown()
            Unit
        }
        every { restoredPublisher.hgetAll("players") } returns mapOf("Steve" to "{}")

        val manager =
            RedisManager(
                RedisConnection("localhost", 6379),
                ServerIdentity { "velocity" },
                logger,
                poolFactory = {
                    if (initialAttempt) {
                        initialAttempt = false
                        throw JedisConnectionException("initial outage")
                    }
                    pools.removeFirst()
                },
            )
        try {
            manager.registerChannelUnique("arc.updates") { _, _, _ -> }

            manager.loadMap("players").join() shouldBe mapOf("Steve" to "{}")

            manager.isConnected() shouldBe true
            subscribed.await(2, TimeUnit.SECONDS) shouldBe true
        } finally {
            manager.close()
        }
    }

    "subscription init replaces a broken subscription connection" {
        val brokenSubscription = mockk<JedisPooled>(relaxed = true)
        val publisher = mockk<JedisPooled>(relaxed = true)
        val restoredSubscription = mockk<JedisPooled>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)
        val subscribed = CountDownLatch(1)
        val pools = ArrayDeque(listOf(brokenSubscription, publisher, restoredSubscription))

        every { brokenSubscription.ping() } throws JedisConnectionException("subscription down")
        every { restoredSubscription.ping() } returns "PONG"
        every {
            restoredSubscription.subscribe(any<JedisPubSub>(), *anyVararg())
        } answers {
            subscribed.countDown()
            Unit
        }

        val manager =
            RedisManager(
                RedisConnection("localhost", 6379),
                ServerIdentity { "velocity" },
                logger,
                poolFactory = { pools.removeFirst() },
            )
        try {
            manager.registerChannelUnique("arc.updates") { _, _, _ -> }

            manager.init()

            subscribed.await(2, TimeUnit.SECONDS) shouldBe true
            verify(exactly = 1) { logger.info("Redis subscription connection restored") }
        } finally {
            manager.close()
        }
    }
})
