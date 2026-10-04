package ru.arc.telemetry

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlayerTelemetryStoreTest : FunSpec({
    fun event(attributes: Map<String, String> = emptyMap()): PlayerTelemetryEvent =
        PlayerTelemetryEvent(
            occurredAt = System.currentTimeMillis(),
            server = "spawn",
            playerId = "37e2a8ab-85c2-4d9a-88f1-5b52cad487f0",
            playerName = "Player_7",
            sessionId = "6c1372e2-ea9c-44d6-a2b5-35b311acda66",
            source = "arc.menu",
            event = "click",
            subject = "mounts",
            attributes = attributes,
        )

    fun settings() = PlayerTelemetrySettings(
        batchSize = 2,
        captureQueueCapacity = 8,
        maxOutboxEvents = 32,
        maxOutboxBytes = 8L * 1024 * 1024,
        maxRecordBytes = 1024L * 1024,
        flushIntervalMillis = 25,
        retryIntervalMillis = 100,
        shutdownTimeoutMillis = 5_000,
    )

    fun store(root: Path, sql: FakeTelemetrySql, settings: PlayerTelemetrySettings = settings()) =
        PlayerTelemetryStore(
            dataDirectory = root,
            server = "spawn",
            settings = settings,
            gson = Gson(),
            clockMillis = System::currentTimeMillis,
            outboxFactory = { FilePlayerTelemetryOutbox(root, settings, PlayerTelemetryJsonCodec(Gson()), Gson()) },
            sqlFactory = { sql },
        )

    fun await(timeoutSeconds: Long = 5, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("Condition was not met within $timeoutSeconds seconds")
            Thread.sleep(10)
        }
    }

    test("journal keeps committing while SQL sender is blocked") {
        val root = Files.createTempDirectory("arc-player-telemetry-store-")
        val blockedInsert = CompletableFuture<Unit>()
        val firstInsertStarted = CountDownLatch(1)
        val insertCalls = AtomicInteger()
        val sql = FakeTelemetrySql(insertResult = { _ ->
            if (insertCalls.incrementAndGet() == 1) {
                firstInsertStarted.countDown()
                blockedInsert
            } else {
                CompletableFuture.completedFuture(Unit)
            }
        })
        val store = store(root, sql)
        store.start().get(3, TimeUnit.SECONDS)
        await { store.health().sqlReady }

        store.offer(event()) shouldBe true
        firstInsertStarted.await(3, TimeUnit.SECONDS) shouldBe true
        repeat(5) { store.offer(event()) shouldBe true }
        await { store.health().durableEvents >= 6L }
        insertCalls.get() shouldBe 1
        Files.list(root.resolve("player-telemetry/outbox")).use { it.count() >= 3L } shouldBe true

        blockedInsert.complete(Unit)
        await { store.health().deliveredEventsSinceStart == 6L && store.health().durableEvents == 0L }
        store.closeAsync().get(3, TimeUnit.SECONDS)
    }

    test("ambiguous SQL failure replays the same immutable event id and payload") {
        val root = Files.createTempDirectory("arc-player-telemetry-replay-")
        val attempted = CopyOnWriteArrayList<List<PlayerTelemetryEvent>>()
        val sql = FakeTelemetrySql(insertResult = { events ->
            attempted += events
            if (attempted.size == 1) {
                CompletableFuture.failedFuture(IllegalStateException("simulated connection loss after commit"))
            } else {
                CompletableFuture.completedFuture(Unit)
            }
        })
        val store = store(root, sql)
        store.start().get(3, TimeUnit.SECONDS)
        await { store.health().sqlReady }
        val captured = event()
        store.offer(captured) shouldBe true
        await { store.health().deliveredEventsSinceStart == 1L }

        attempted.size shouldBe 2
        attempted[0] shouldBe listOf(captured)
        attempted[1] shouldBe attempted[0]
        store.closeAsync().get(3, TimeUnit.SECONDS)
    }

    test("query admission rejects excess outstanding work and releases capacity on completion") {
        val root = Files.createTempDirectory("arc-player-telemetry-query-cap-")
        val queryGates = CopyOnWriteArrayList<CompletableFuture<PlayerTelemetryPage>>()
        val sql = FakeTelemetrySql(queryResult = {
            CompletableFuture<PlayerTelemetryPage>().also(queryGates::add)
        })
        val store = store(root, sql)
        store.start().get(3, TimeUnit.SECONDS)
        await { store.health().sqlReady }
        val request = PlayerTelemetryQuery(1_800_000_000_000L, 1_800_000_000_010L)
        val firstFour = (1..4).map { store.query(request) }
        val rejected = store.query(request)
        shouldThrow<java.util.concurrent.CompletionException> { rejected.join() }
        queryGates.size shouldBe 4

        queryGates.first().complete(
            PlayerTelemetryPage(
                fromInclusive = request.fromInclusive,
                untilExclusive = request.untilExclusive,
                coverageFrom = null,
                coverageGapFrom = null,
                retainedFrom = 0L,
                historyCovered = false,
                events = emptyList(),
                hasMore = false,
                nextCursor = null,
            ),
        )
        firstFour.first().join().historyCovered shouldBe false
        store.query(request)
        queryGates.size shouldBe 5
        queryGates.forEach { it.completeExceptionally(IllegalStateException("test cleanup")) }
        store.closeAsync().get(3, TimeUnit.SECONDS)
    }

    test("close and offer share a linearized intake boundary") {
        val root = Files.createTempDirectory("arc-player-telemetry-close-race-")
        val sql = FakeTelemetrySql(insertResult = { CompletableFuture() })
        val store = store(root, sql)
        store.start().get(3, TimeUnit.SECONDS)
        await { store.health().sqlReady }
        val attributes = BlockingAttributes()
        val event = event(attributes)
        attributes.arm()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val offering = workers.submit<Boolean> { store.offer(event) }
            attributes.entered.await(3, TimeUnit.SECONDS) shouldBe true
            val closeStarted = CountDownLatch(1)
            val closing = workers.submit<CompletableFuture<Unit>> {
                closeStarted.countDown()
                store.closeAsync()
            }
            closeStarted.await(3, TimeUnit.SECONDS) shouldBe true
            attributes.release.countDown()

            offering.get(3, TimeUnit.SECONDS) shouldBe true
            closing.get(3, TimeUnit.SECONDS).get(3, TimeUnit.SECONDS)
            store.health().durableEvents shouldBe 1L
            Files.list(root.resolve("player-telemetry/outbox")).use { it.count() } shouldBe 1L
        } finally {
            attributes.release.countDown()
            workers.shutdownNow()
            runCatching { store.closeAsync().get(3, TimeUnit.SECONDS) }
        }
    }

    test("failed recovery closes its partial outbox and makes close complete exceptionally") {
        val root = Files.createTempDirectory("arc-player-telemetry-start-failure-")
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val brokenOutbox = object : PlayerTelemetryOutbox {
            override fun recover(now: Long): PlayerTelemetryOutboxSnapshot = error("simulated recovery failure")
            override fun stats(): PlayerTelemetryOutboxSnapshot = error("not recovered")
            override fun commit(batchId: String, events: List<PlayerTelemetryEvent>): PlayerTelemetryOutboxRecord = error("not recovered")
            override fun next(now: Long): PlayerTelemetryOutboxRecord? = error("not recovered")
            override fun acknowledge(record: PlayerTelemetryOutboxRecord) = error("not recovered")
            override fun noteGap(at: Long, droppedEvents: Long): PlayerTelemetryOutboxSnapshot = error("not recovered")
            override fun markCleanShutdown(at: Long) = error("not recovered")
            override fun corruptRecordCount(): Int = 0
            override fun close() { closed.set(true) }
        }
        val store = PlayerTelemetryStore(
            dataDirectory = root,
            server = "spawn",
            settings = settings(),
            gson = Gson(),
            clockMillis = System::currentTimeMillis,
            outboxFactory = { brokenOutbox },
            sqlFactory = { FakeTelemetrySql() },
        )

        shouldThrow<java.util.concurrent.CompletionException> { store.start().join() }
        await { closed.get() }
        shouldThrow<java.util.concurrent.CompletionException> { store.closeAsync().join() }
    }

    test("adapter-reported capture loss persists a coverage gap") {
        val root = Files.createTempDirectory("arc-player-telemetry-capture-loss-")
        val outboxRef = java.util.concurrent.atomic.AtomicReference<PlayerTelemetryOutbox?>()
        val configured = settings()
        val store = PlayerTelemetryStore(
            dataDirectory = root,
            server = "spawn",
            settings = configured,
            gson = Gson(),
            clockMillis = System::currentTimeMillis,
            outboxFactory = {
                FilePlayerTelemetryOutbox(root, configured, PlayerTelemetryJsonCodec(Gson()), Gson())
                    .also(outboxRef::set)
            },
            sqlFactory = { FakeTelemetrySql() },
        )
        store.start().get(3, TimeUnit.SECONDS)

        store.noteCaptureLoss()
        await {
            store.health().droppedEventsSinceStart == 1L &&
                (outboxRef.get()?.stats()?.coverageGapFrom != null)
        }
        (store.health().coverageGapFrom != null) shouldBe true
        store.closeAsync().get(3, TimeUnit.SECONDS)
    }

    test("an acknowledgement snapshot cannot overwrite a later journal commit") {
        val root = Files.createTempDirectory("arc-player-telemetry-ack-snapshot-")
        val configured = settings().copy(batchSize = 1, retryIntervalMillis = 100)
        val clock = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val firstInsert = CompletableFuture<Unit>()
        val secondInsert = CompletableFuture<Unit>()
        val insertCalls = AtomicInteger()
        val pruneStarted = CountDownLatch(1)
        val releasePrune = CountDownLatch(1)
        val ackSnapshotTaken = CountDownLatch(1)
        val releaseAckSnapshot = CountDownLatch(1)
        val outboxRef = java.util.concurrent.atomic.AtomicReference<PlayerTelemetryOutbox>()
        val sql = FakeTelemetrySql(
            insertResult = { _ ->
                when (insertCalls.incrementAndGet()) {
                    1 -> firstInsert
                    2 -> secondInsert
                    else -> CompletableFuture.completedFuture(Unit)
                }
            },
            pruneResult = {
                pruneStarted.countDown()
                check(releasePrune.await(5, TimeUnit.SECONDS)) { "Test did not release SQL sender" }
                CompletableFuture.completedFuture(0)
            },
        )
        val delegateOutbox = FilePlayerTelemetryOutbox(
            root,
            configured,
            PlayerTelemetryJsonCodec(Gson()),
            Gson(),
        )
        val outbox = object : PlayerTelemetryOutbox by delegateOutbox {
            private val blockNextStats = java.util.concurrent.atomic.AtomicBoolean(false)
            private val acknowledgementCount = AtomicInteger()

            override fun recover(now: Long): PlayerTelemetryOutboxSnapshot = delegateOutbox.recover(now)
            override fun stats(): PlayerTelemetryOutboxSnapshot {
                val snapshot = delegateOutbox.stats()
                if (blockNextStats.compareAndSet(true, false)) {
                    ackSnapshotTaken.countDown()
                    check(releaseAckSnapshot.await(5, TimeUnit.SECONDS)) { "Test did not release journal acknowledgement" }
                }
                return snapshot
            }
            override fun commit(batchId: String, events: List<PlayerTelemetryEvent>): PlayerTelemetryOutboxRecord =
                delegateOutbox.commit(batchId, events)
            override fun next(now: Long): PlayerTelemetryOutboxRecord? = delegateOutbox.next(now)
            override fun acknowledge(record: PlayerTelemetryOutboxRecord): ru.arc.persistence.DurableAcknowledgementOutcome =
                delegateOutbox.acknowledge(record).also {
                    if (acknowledgementCount.incrementAndGet() == 1) blockNextStats.set(true)
                }
            override fun noteGap(at: Long, droppedEvents: Long): PlayerTelemetryOutboxSnapshot = delegateOutbox.noteGap(at, droppedEvents)
            override fun markCleanShutdown(at: Long) = delegateOutbox.markCleanShutdown(at)
            override fun corruptRecordCount(): Int = delegateOutbox.corruptRecordCount()
            override fun close() = delegateOutbox.close()
        }
        val store = PlayerTelemetryStore(
            dataDirectory = root,
            server = "spawn",
            settings = configured,
            gson = Gson(),
            clockMillis = clock::get,
            outboxFactory = { outboxRef.set(outbox); outbox },
            sqlFactory = { sql },
        )
        store.start().get(3, TimeUnit.SECONDS)
        await { store.health().sqlReady }
        Thread.sleep(200)

        store.offer(event()) shouldBe true
        await { insertCalls.get() == 1 }
        store.offer(event()) shouldBe true
        await { store.health().durableEvents == 2L }
        clock.addAndGet(60_001L)
        pruneStarted.await(3, TimeUnit.SECONDS) shouldBe true
        firstInsert.complete(Unit)
        ackSnapshotTaken.await(3, TimeUnit.SECONDS) shouldBe true
        store.offer(event()) shouldBe true
        releaseAckSnapshot.countDown()
        await { outboxRef.get().stats().eventCount == 2L }
        store.health().durableEvents shouldBe 2L

        releasePrune.countDown()
        await { insertCalls.get() >= 2 }
        store.health().durableEvents shouldBe 2L
        secondInsert.complete(Unit)
        await { store.health().deliveredEventsSinceStart == 3L }
        store.closeAsync().get(3, TimeUnit.SECONDS)
    }

    test("close rapidly drains multiple journal batches before the periodic flush interval") {
        val root = Files.createTempDirectory("arc-player-telemetry-fast-close-")
        val sql = FakeTelemetrySql()
        val closeSettings = settings().copy(
            batchSize = 2,
            captureQueueCapacity = 32,
            flushIntervalMillis = 5_000,
            shutdownTimeoutMillis = 3_000,
        )
        val store = store(root, sql, closeSettings)
        store.start().get(3, TimeUnit.SECONDS)
        repeat(12) { store.offer(event()) shouldBe true }

        store.closeAsync().get(2, TimeUnit.SECONDS)
        store.health().queuedEvents shouldBe 0
        store.health().durableEvents shouldBe 12L
        Files.list(root.resolve("player-telemetry/outbox")).use { it.count() } shouldBe 6L
    }
})

private class FakeTelemetrySql(
    private val insertResult: (List<PlayerTelemetryEvent>) -> CompletableFuture<Unit> = {
        CompletableFuture.completedFuture(Unit)
    },
    private val pruneResult: (Long) -> CompletableFuture<Int> = {
        CompletableFuture.completedFuture(0)
    },
    private val queryResult: () -> CompletableFuture<PlayerTelemetryPage> = {
        CompletableFuture.failedFuture(UnsupportedOperationException("query fake not configured"))
    },
) : PlayerTelemetrySql {
    private val inserted = CopyOnWriteArrayList<List<PlayerTelemetryEvent>>()

    override fun migrate() = CompletableFuture.completedFuture(Unit)

    override fun registerServer(server: String, coverageFrom: Long, coverageGapFrom: Long?, knownDrops: Long) =
        CompletableFuture.completedFuture(Unit)

    override fun advanceCoverage(server: String, through: Long, coverageGapFrom: Long?, knownDrops: Long) =
        CompletableFuture.completedFuture(Unit)

    override fun insertBatch(events: List<PlayerTelemetryEvent>): CompletableFuture<Unit> {
        inserted += events
        return insertResult(events)
    }

    override fun query(request: PlayerTelemetryQuery): CompletableFuture<PlayerTelemetryPage> = queryResult()

    override fun summary(request: PlayerTelemetrySummaryQuery): CompletableFuture<PlayerTelemetrySummary> =
        CompletableFuture.failedFuture(UnsupportedOperationException("summary fake not configured"))

    override fun prune(cutoff: Long) = pruneResult(cutoff)

    override fun close() = Unit
}

private class BlockingAttributes : kotlin.collections.AbstractMap<String, String>() {
    private val value = mapOf("button" to "equip")
    private val armed = java.util.concurrent.atomic.AtomicBoolean(false)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override val entries: Set<Map.Entry<String, String>>
        get() {
            if (armed.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "Test did not release the event copy" }
            }
            return value.entries
        }

    fun arm() {
        armed.set(true)
    }
}
