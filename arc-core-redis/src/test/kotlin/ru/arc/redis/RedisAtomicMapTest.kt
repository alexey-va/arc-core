package ru.arc.redis

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import java.util.concurrent.CompletableFuture

class RedisAtomicMapTest :
    StringSpec({
        "compare and set distinguishes an absent entry from a present empty value" {
            val redis = InMemoryRedis()

            redis.compareAndSetMapEntry("sessions", "one", null, "").join().shouldBeTrue()
            redis.compareAndSetMapEntry("sessions", "one", null, "other").join().shouldBeFalse()
            redis.compareAndSetMapEntry("sessions", "one", "", "next").join().shouldBeTrue()
            redis.loadMapEntries("sessions", "one").join() shouldContainExactly listOf("next")
        }

        "compare and set deletes only the expected value" {
            val redis = InMemoryRedis()
            redis.setHash("sessions", mapOf("one" to "v1"))

            redis.compareAndSetMapEntry("sessions", "one", "stale", null).join().shouldBeFalse()
            redis.compareAndSetMapEntry("sessions", "one", "v1", null).join().shouldBeTrue()
            redis.loadMapEntries("sessions", "one").join() shouldContainExactly listOf(null)
        }

        "concurrent creators have exactly one winner" {
            val redis = InMemoryRedis()
            val attempts =
                (1..32).map { value ->
                    CompletableFuture.supplyAsync {
                        redis.compareAndSetMapEntry("sessions", "shared", null, value.toString()).join()
                    }
                }

            attempts.count { it.join() }.let { winnerCount ->
                (winnerCount == 1).shouldBeTrue()
            }
        }
    })
