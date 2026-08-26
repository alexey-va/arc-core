package ru.arc.redis.safety

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations
import java.util.concurrent.CompletableFuture

class RedisHashUpdaterTest : FreeSpec({
    data class Counter(val id: String, val revision: Int)

    fun codec() = BoundedJsonCodec(
        gson = Gson(),
        type = Counter::class.java,
        rootContract = JsonObjectContract(setOf("id", "revision")),
        bounds = JsonResourceBounds(256),
        validate = { value ->
            require(value.id.matches(Regex("[a-z0-9_-]{1,32}")))
            require(value.revision in 0..1_000_000)
        },
    )

    "creates, updates, rejects, leaves equal values unchanged and deletes" {
        val redis = InMemoryRedis()
        val updater = RedisHashUpdater(redis, "arc:test:counters", codec())

        updater.update("one") { RedisHashDecision.Write(Counter("one", 0)) }.join()
            .shouldBeInstanceOf<RedisHashUpdateResult.Changed<Counter>>()
        updater.update("one") { current -> RedisHashDecision.Write(requireNotNull(current).copy(revision = 1)) }.join()
            .shouldBeInstanceOf<RedisHashUpdateResult.Changed<Counter>>()
        updater.update("one") { current -> RedisHashDecision.Write(requireNotNull(current)) }.join()
            .shouldBeInstanceOf<RedisHashUpdateResult.Unchanged<Counter>>()
        updater.update("one") { RedisHashDecision.Reject }.join()
            .shouldBeInstanceOf<RedisHashUpdateResult.Rejected<Counter>>()
        updater.update("one") { RedisHashDecision.Delete }.join()
            .shouldBeInstanceOf<RedisHashUpdateResult.Changed<Counter>>()
        redis.getHash("arc:test:counters") shouldBe emptyMap()
    }

    "retries real concurrent contention without losing increments" {
        val redis = InMemoryRedis()
        val updater = RedisHashUpdater(redis, "arc:test:counters", codec(), maxAttempts = 64)
        updater.update("shared") { RedisHashDecision.Write(Counter("shared", 0)) }.join()

        val results = (1..24).map {
            CompletableFuture.supplyAsync {
                updater.update("shared") { current ->
                    RedisHashDecision.Write(requireNotNull(current).copy(revision = current.revision + 1))
                }.join()
            }
        }.map(CompletableFuture<RedisHashUpdateResult<Counter>>::join)

        results.filterIsInstance<RedisHashUpdateResult.Changed<Counter>>().shouldHaveSize(24)
        val final = codec().decode(redis.getHash("arc:test:counters").getValue("shared"))
        final.revision shouldBe 24
    }

    "returns typed contention after the exact configured bound" {
        val redis = AlwaysContendedRedis(InMemoryRedis())
        val updater = RedisHashUpdater(redis, "arc:test:counters", codec(), maxAttempts = 3)
        val result = updater.update("one") { RedisHashDecision.Write(Counter("one", 1)) }.join()
        result shouldBe RedisHashUpdateResult.Contended(3)
        redis.casCalls shouldBe 3
    }

    "corrupt state fails closed and is never overwritten as missing" {
        val redis = InMemoryRedis().apply { setHash("arc:test:counters", mapOf("one" to "{bad")) }
        val updater = RedisHashUpdater(redis, "arc:test:counters", codec())
        val future = updater.update("one") { RedisHashDecision.Write(Counter("one", 1)) }
        runCatching { future.join() }.isFailure shouldBe true
        redis.getHash("arc:test:counters").getValue("one") shouldBe "{bad"
    }

    "an invalid storage response fails closed instead of being treated as absence" {
        val delegate = InMemoryRedis()
        val redis = object : RedisOperations by delegate {
            override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> =
                CompletableFuture.completedFuture(emptyList())
        }
        val updater = RedisHashUpdater(redis, "arc:test:counters", codec())
        runCatching {
            updater.update("one") { RedisHashDecision.Write(Counter("one", 1)) }.join()
        }.isFailure shouldBe true
        delegate.getHash("arc:test:counters") shouldBe emptyMap()
    }

    "concurrent consume returns the value exactly once" {
        val redis = InMemoryRedis()
        val updater = RedisHashUpdater(redis, "arc:test:counters", codec())
        updater.update("one") { RedisHashDecision.Write(Counter("one", 7)) }.join()
        val first = CompletableFuture.supplyAsync { updater.consume("one").join() }
        val second = CompletableFuture.supplyAsync { updater.consume("one").join() }
        val results = listOf(first.join(), second.join())
        results.filterIsInstance<RedisHashConsumeResult.Consumed<Counter>>().shouldHaveSize(1)
        results.filterIsInstance<RedisHashConsumeResult.Rejected<Counter>>().shouldHaveSize(1)
    }
}) {
    private class AlwaysContendedRedis(private val delegate: RedisOperations) : RedisOperations by delegate {
        var casCalls = 0
        override fun compareAndSetMapEntry(
            key: String,
            mapKey: String,
            expectedValue: String?,
            replacementValue: String?,
        ): CompletableFuture<Boolean> {
            casCalls++
            return CompletableFuture.completedFuture(false)
        }
    }
}
