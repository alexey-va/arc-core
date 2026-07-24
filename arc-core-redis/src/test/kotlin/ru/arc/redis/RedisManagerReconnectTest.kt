package ru.arc.redis

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.slf4j.Logger
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.exceptions.JedisConnectionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RedisManagerReconnectTest : FreeSpec({
    "publish reconnects once and retries the message" {
        val subscription = mockk<JedisPooled>(relaxed = true)
        val brokenPublisher = mockk<JedisPooled>(relaxed = true)
        val restoredPublisher = mockk<JedisPooled>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)
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
            manager.publish("channel", "payload")

            delivered.await(2, TimeUnit.SECONDS) shouldBe true
            manager.isConnected() shouldBe true
            verify(exactly = 1) {
                restoredPublisher.publish("channel", RedisWire.encode("velocity", "payload"))
            }
            verify(exactly = 1) { logger.info("Redis publish connection restored") }
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
})
