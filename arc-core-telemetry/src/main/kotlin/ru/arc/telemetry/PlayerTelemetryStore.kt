package ru.arc.telemetry

import com.google.gson.Gson
import ru.arc.sql.SqlConnectionConfig
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

internal interface PlayerTelemetrySql : AutoCloseable {
    fun migrate(): CompletableFuture<Unit>
    fun registerServer(server: String, coverageFrom: Long, coverageGapFrom: Long?, knownDrops: Long): CompletableFuture<Unit>
    fun advanceCoverage(server: String, through: Long, coverageGapFrom: Long?, knownDrops: Long): CompletableFuture<Unit>
    fun insertBatch(events: List<PlayerTelemetryEvent>): CompletableFuture<Unit>
    fun query(request: PlayerTelemetryQuery): CompletableFuture<PlayerTelemetryPage>
    fun summary(request: PlayerTelemetrySummaryQuery): CompletableFuture<PlayerTelemetrySummary>
    fun prune(cutoff: Long): CompletableFuture<Int>
    override fun close()
}

private data class QueuedTelemetryEvent(val event: PlayerTelemetryEvent, val reservationBytes: Long)
private data class PendingJournalCommit(val batchId: String, val queued: List<QueuedTelemetryEvent>)

/**
 * Cross-platform durable player-event store. Event callbacks only validate/copy a bounded event and enqueue it;
 * journal fsync and SQL work run on independent owned executors. SQL retries never hold the journal worker.
 */
class PlayerTelemetryStore private constructor(
    private val dataDirectory: Path,
    private val server: String,
    private val settings: PlayerTelemetrySettings,
    private val gson: Gson,
    private val clockMillis: () -> Long,
    private val outboxFactory: () -> PlayerTelemetryOutbox,
    private val sqlFactory: () -> PlayerTelemetrySql,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : AutoCloseable {
    constructor(
        connectionConfig: SqlConnectionConfig,
        dataDirectory: Path,
        server: String,
        settings: PlayerTelemetrySettings = PlayerTelemetrySettings(),
    ) : this(
        dataDirectory = dataDirectory,
        server = validateServer(server),
        settings = settings,
        gson = Gson(),
        clockMillis = System::currentTimeMillis,
        marker = Unit,
        outboxFactory = {
            FilePlayerTelemetryOutbox(dataDirectory, settings, PlayerTelemetryJsonCodec(Gson()), Gson())
        },
        sqlFactory = {
            PlayerTelemetrySqlRepository.open(
                connectionConfig = connectionConfig,
                settings = settings,
                codec = PlayerTelemetryJsonCodec(Gson()),
                gson = Gson(),
                clockMillis = System::currentTimeMillis,
                runtimeName = "ARC-player-telemetry",
            )
        },
    )

    internal constructor(
        dataDirectory: Path,
        server: String,
        settings: PlayerTelemetrySettings,
        gson: Gson,
        clockMillis: () -> Long,
        outboxFactory: () -> PlayerTelemetryOutbox,
        sqlFactory: () -> PlayerTelemetrySql,
    ) : this(dataDirectory, validateServer(server), settings, gson, clockMillis, outboxFactory, sqlFactory, Unit)

    private data class Capture(val event: PlayerTelemetryEvent, val estimatedBytes: Long)

    private val codec = PlayerTelemetryJsonCodec(gson)
    private val queue = ArrayBlockingQueue<Capture>(settings.captureQueueCapacity)
    private val querySlots = Semaphore(MAX_ONGOING_QUERIES)
    private val queueEvents = AtomicInteger()
    private val pendingJournalEvents = AtomicInteger()
    private val durableEvents = AtomicLong()
    private val durableBytes = AtomicLong()
    private val deliveredSinceStart = AtomicLong()
    private val retriesSinceStart = AtomicLong()
    private val droppedSinceStart = AtomicLong()
    private val corruptRecords = AtomicInteger()
    private val coverageFrom = AtomicLong(0L)
    private val coverageGapFrom = AtomicLong(0L)
    private val knownDropCount = AtomicLong(0L)
    private val oldestOutboxAt = AtomicLong(0L)
    private val reservedEvents = AtomicLong()
    private val reservedBytes = AtomicLong()
    private val pendingPersistedDrops = AtomicLong()
    private val pendingGapAt = AtomicLong(0L)
    private val lastFailureCode = AtomicReference<String?>(null)
    private val accepting = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val senderBusy = AtomicBoolean(false)
    private val sqlReady = AtomicBoolean(false)
    private val sqlConnecting = AtomicBoolean(false)
    private val coverageAdvanceInFlight = AtomicBoolean(false)
    private val saturated = AtomicBoolean(false)
    private val stateLock = Any()
    private val pendingCapture = ArrayDeque<QueuedTelemetryEvent>() // Journal thread only.
    private var pendingCommit: PendingJournalCommit? = null // Journal thread only.
    private val startedFuture = AtomicReference<CompletableFuture<Unit>?>(null)
    private val closeFuture = AtomicReference<CompletableFuture<Unit>?>(null)
    private val startupFailure = AtomicReference<Throwable?>(null)
    private val outboxRef = AtomicReference<PlayerTelemetryOutbox?>(null)
    private val sqlRef = AtomicReference<PlayerTelemetrySql?>(null)
    private val journalExecutor = executor("player-telemetry-journal")
    private val senderExecutor = executor("player-telemetry-sender")
    private var journalSchedule: ScheduledFuture<*>? = null
    private var senderSchedule: ScheduledFuture<*>? = null
    private var sqlAttemptAt = 0L
    private var retentionAt = 0L
    private var registrationAt = 0L
    private val coverageAdvancedAt = AtomicLong(0L)
    private val runtimeGap = AtomicLong(0L)
    private val shutdownTimedOut = AtomicBoolean(false)
    private val journalFinished = AtomicBoolean(false)

    /** Returns when local journal recovery finishes. SQL initialization continues separately in the background. */
    @Synchronized
    fun start(): CompletableFuture<Unit> {
        startedFuture.get()?.let { return it }
        if (closing.get()) {
            return CompletableFuture.failedFuture(PlayerTelemetryStoreUnavailableException("Player telemetry store is closing"))
        }
        val result = CompletableFuture<Unit>()
        startedFuture.set(result)
        journalExecutor.execute {
            try {
                val outbox = outboxFactory()
                outboxRef.set(outbox)
                val recovery = outbox.recover(clockMillis())
                durableEvents.set(recovery.eventCount)
                durableBytes.set(recovery.byteCount)
                reservedEvents.set(recovery.eventCount)
                reservedBytes.set(recovery.byteCount)
                coverageFrom.set(recovery.coverageFrom)
                recovery.coverageGapFrom?.let(coverageGapFrom::set)
                knownDropCount.set(recovery.knownDropCount)
                recovery.oldestAt?.let(oldestOutboxAt::set)
                corruptRecords.set(recovery.corruptRecordCount)
                synchronized(stateLock) {
                    saturated.set(recovery.eventCount >= settings.maxOutboxEvents || recovery.byteCount >= settings.maxOutboxBytes)
                }
                started.set(true)
                accepting.set(recovery.eventCount < settings.maxOutboxEvents && recovery.byteCount < settings.maxOutboxBytes)
                journalSchedule = journalExecutor.scheduleWithFixedDelay(
                    ::journalTick,
                    settings.flushIntervalMillis,
                    settings.flushIntervalMillis,
                    TimeUnit.MILLISECONDS,
                )
                if (!closing.get()) {
                    senderSchedule = senderExecutor.scheduleWithFixedDelay(
                        ::senderTick,
                        0,
                        settings.retryIntervalMillis,
                        TimeUnit.MILLISECONDS,
                    )
                }
                result.complete(Unit)
                if (closing.get() && !journalExecutor.isShutdown) journalExecutor.execute(::journalTick)
            } catch (failure: Throwable) {
                corruptRecords.incrementAndGet()
                startupFailure.set(failure)
                accepting.set(false)
                started.set(false)
                closing.set(true)
                setFailure(failure, "JOURNAL_RECOVERY_FAILED")
                runCatching { outboxRef.getAndSet(null)?.close() }
                    .onFailure { setFailure(it, "JOURNAL_CLOSE_FAILED") }
                journalSchedule?.cancel(false)
                senderSchedule?.cancel(false)
                journalExecutor.shutdown()
                senderExecutor.shutdownNow()
                val unavailable = PlayerTelemetryStoreUnavailableException("Local telemetry journal recovery failed")
                result.completeExceptionally(unavailable)
                closeFuture.get()?.completeExceptionally(unavailable)
            }
        }
        return result
    }

    /** Nonblocking callback boundary; false is visible through process-local drop counters and a persisted gap marker. */
    fun offer(event: PlayerTelemetryEvent): Boolean {
        if (!started.get() || closing.get()) return false
        synchronized(stateLock) {
            if (!started.get() || closing.get()) return false
            if (!accepting.get()) {
                lose(clockMillis())
                return false
            }
            val snapshot = try {
                event.validatedCopy()
            } catch (_: RuntimeException) {
                lose(clockMillis())
                return false
            }
            val estimate = snapshot.estimatedBytes()
            if (estimate + 96L > settings.maxRecordBytes) {
                lose(clockMillis())
                return false
            }
            val eventTotal = reservedEvents.get()
            val byteTotal = reservedBytes.get()
            if (eventTotal >= settings.maxOutboxEvents || estimate > settings.maxOutboxBytes - byteTotal) {
                saturated.set(true)
                lose(clockMillis())
                return false
            }
            if (!queue.offer(Capture(snapshot, estimate))) {
                saturated.set(true)
                lose(clockMillis())
                return false
            }
            reservedEvents.incrementAndGet()
            reservedBytes.addAndGet(estimate)
            queueEvents.incrementAndGet()
            return true
        }
    }

    /** Records an event that the caller intended to capture but could not construct or validate. */
    fun noteCaptureLoss() {
        synchronized(stateLock) {
            if (!started.get() || closing.get()) return
            lose(clockMillis())
        }
    }

    fun query(request: PlayerTelemetryQuery): CompletableFuture<PlayerTelemetryPage> {
        validateQuery(request)
        val sql = sqlRef.get()?.takeIf { sqlReady.get() }
            ?: return CompletableFuture.failedFuture(PlayerTelemetryStoreUnavailableException("Player telemetry SQL is not ready"))
        return withQuerySlot { sql.query(request) }
    }

    fun summary(request: PlayerTelemetrySummaryQuery): CompletableFuture<PlayerTelemetrySummary> {
        validateSummary(request)
        val sql = sqlRef.get()?.takeIf { sqlReady.get() }
            ?: return CompletableFuture.failedFuture(PlayerTelemetryStoreUnavailableException("Player telemetry SQL is not ready"))
        return withQuerySlot { sql.summary(request) }
    }

    fun health(): PlayerTelemetryHealth = PlayerTelemetryHealth(
        started = started.get(),
        accepting = accepting.get(),
        sqlReady = sqlReady.get(),
        coverageFrom = coverageFrom.get().takeIf { it > 0L },
        coverageGapFrom = listOfNotNull(coverageGapFrom.get().takeIf { it > 0L }, runtimeGap.get().takeIf { it > 0L }).minOrNull(),
        queuedEvents = queueEvents.get() + pendingJournalEvents.get(),
        durableEvents = durableEvents.get(),
        durableBytes = durableBytes.get(),
        deliveredEventsSinceStart = deliveredSinceStart.get(),
        retriesSinceStart = retriesSinceStart.get(),
        droppedEventsSinceStart = droppedSinceStart.get(),
        corruptRecords = corruptRecords.get(),
        oldestOutboxAt = oldestOutboxAt.get().takeIf { it > 0L },
        saturated = saturated.get(),
        lastFailureCode = lastFailureCode.get(),
    )

    /** Stops new offers immediately and asynchronously waits at most the configured time for queued events to reach disk. */
    @Synchronized
    override fun close() {
        closeAsync()
    }

    @Synchronized
    fun closeAsync(): CompletableFuture<Unit> {
        closeFuture.get()?.let { return it }
        val result = CompletableFuture<Unit>()
        if (!closeFuture.compareAndSet(null, result)) return requireNotNull(closeFuture.get())
        if (startupFailure.get() != null) {
            result.completeExceptionally(PlayerTelemetryStoreUnavailableException("Local telemetry journal recovery failed"))
            return result
        }
        synchronized(stateLock) {
            closing.set(true)
            accepting.set(false)
        }
        senderSchedule?.cancel(false)
        val startup = startedFuture.get()
        if (startup == null) {
            try {
                senderExecutor.execute(::closeSql)
                journalExecutor.execute {
                    runCatching { outboxRef.getAndSet(null)?.close() }
                        .onFailure { setFailure(it, "JOURNAL_CLOSE_FAILED") }
                    journalFinished.set(true)
                    journalExecutor.shutdown()
                    result.complete(Unit)
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                result.completeExceptionally(PlayerTelemetryStoreUnavailableException("Telemetry shutdown workers are unavailable"))
            }
            return result
        }
        startup.whenComplete { _, failure ->
            if (result.isDone) return@whenComplete
            if (failure != null) {
                result.completeExceptionally(PlayerTelemetryStoreUnavailableException("Local telemetry journal recovery failed"))
                return@whenComplete
            }
            try {
                senderExecutor.execute(::closeSql)
                journalExecutor.execute(::journalTick)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                if (!result.isDone) {
                    result.completeExceptionally(PlayerTelemetryStoreUnavailableException("Telemetry shutdown workers are unavailable"))
                }
            }
        }
        CompletableFuture.delayedExecutor(settings.shutdownTimeoutMillis, TimeUnit.MILLISECONDS).execute {
            if (!result.isDone) {
                shutdownTimedOut.set(true)
                setFailure(IllegalStateException("shutdown drain timeout"), "SHUTDOWN_DRAIN_TIMEOUT")
                journalSchedule?.cancel(false)
                journalExecutor.shutdown()
                result.completeExceptionally(PlayerTelemetryStoreUnavailableException("Telemetry outbox did not drain to disk before shutdown timeout"))
            }
        }
        return result
    }

    private fun journalTick() {
        val outbox = outboxRef.get() ?: return
        try {
            persistDroppedGaps(outbox)
            val progressed = flushCapture(outbox)
            if (closing.get() && queue.isEmpty() && pendingCapture.isEmpty() && pendingCommit == null &&
                pendingPersistedDrops.get() == 0L
            ) {
                if (journalFinished.get()) return
                outbox.markCleanShutdown(clockMillis())
                outbox.close()
                journalFinished.set(true)
                journalSchedule?.cancel(false)
                closeFuture.get()?.complete(Unit)
                journalExecutor.shutdown()
            } else if (closing.get() && progressed && !shutdownTimedOut.get()) {
                runCatching { journalExecutor.execute(::journalTick) }
            }
        } catch (failure: Throwable) {
            setFailure(failure, "JOURNAL_WRITE_FAILED")
        }
    }

    private fun flushCapture(outbox: PlayerTelemetryOutbox): Boolean {
        while (pendingCapture.size < settings.batchSize && queue.peek() != null) {
            val next = queue.poll() ?: break
            pendingCapture.addLast(QueuedTelemetryEvent(next.event, next.estimatedBytes))
            queueEvents.decrementAndGet()
            pendingJournalEvents.incrementAndGet()
        }
        if (pendingCapture.isEmpty()) return false
        val pending = pendingCommit ?: createPendingCommit() ?: return false
        pendingCommit = pending
        val events = pending.queued.map { it.event }
        try {
            val stored = outbox.commit(pending.batchId, events)
            repeat(pending.queued.size) { pendingCapture.removeFirst() }
            pendingJournalEvents.addAndGet(-pending.queued.size)
            pendingCommit = null
            durableEvents.addAndGet(stored.events.size.toLong())
            durableBytes.addAndGet(stored.encodedBytes)
            val estimates = pending.queued.sumOf(QueuedTelemetryEvent::reservationBytes)
            reservedBytes.addAndGet(stored.encodedBytes - estimates)
            oldestOutboxAt.updateAndGet { previous -> if (previous == 0L) stored.oldestAt else minOf(previous, stored.oldestAt) }
            updateSaturation()
            if (!closing.get()) submitSender(::sendOne)
            return true
        } catch (failure: Throwable) {
            setFailure(failure, "JOURNAL_COMMIT_RETRY")
            retriesSinceStart.incrementAndGet()
            return false
        }
    }

    private fun createPendingCommit(): PendingJournalCommit? {
        while (true) {
            val first = pendingCapture.peekFirst() ?: return null
            if (first.reservationBytes + 96L <= settings.maxRecordBytes) break
            pendingCapture.removeFirst()
            pendingJournalEvents.decrementAndGet()
            reservedEvents.decrementAndGet()
            reservedBytes.addAndGet(-first.reservationBytes)
            markGap(clockMillis())
        }
        val chosen = ArrayList<QueuedTelemetryEvent>(settings.batchSize)
        var reservation = 96L
        for (item in pendingCapture) {
            if (chosen.size >= settings.batchSize) break
            if (reservation + item.reservationBytes > settings.maxRecordBytes) break
            chosen += item
            reservation += item.reservationBytes
        }
        if (chosen.isEmpty()) return null
        return PendingJournalCommit(UUID.randomUUID().toString(), chosen)
    }

    private fun senderTick() {
        if (closing.get()) return
        val now = clockMillis()
        val repository = sqlRef.get()
        if (repository == null || !sqlReady.get()) {
            if (now - sqlAttemptAt >= settings.retryIntervalMillis) initializeSql(now)
            return
        }
        if (now - retentionAt >= RETENTION_CHECK_INTERVAL_MILLIS) {
            retentionAt = now
            repository.prune(now - settings.retentionDays * MILLIS_PER_DAY).whenComplete { _, failure ->
                if (failure != null) {
                    retriesSinceStart.incrementAndGet()
                    setFailure(failure, "SQL_RETENTION_RETRY")
                }
            }
        }
        if (now - registrationAt >= NODE_HEARTBEAT_INTERVAL_MILLIS) {
            registrationAt = now
            repository.registerServer(
                server,
                coverageFrom.get().takeIf { it > 0L } ?: now,
                coverageGapFrom.get().takeIf { it > 0L },
                knownDropCount.get(),
            ).whenComplete { _, failure ->
                if (failure != null) {
                    retriesSinceStart.incrementAndGet()
                    setFailure(failure, "SQL_HEARTBEAT_RETRY")
                } else {
                    advanceCoverageIfIdle(repository)
                }
            }
        }
        if (!senderBusy.get()) sendOne()
    }

    private fun initializeSql(now: Long) {
        if (!sqlConnecting.compareAndSet(false, true)) return
        sqlAttemptAt = now
        val repository = try {
            sqlRef.get() ?: sqlFactory().also(sqlRef::set)
        } catch (failure: Throwable) {
            sqlConnecting.set(false)
            retriesSinceStart.incrementAndGet()
            setFailure(failure, "SQL_INIT_RETRY")
            return
        }
        repository.migrate().thenCompose {
            repository.registerServer(
                server,
                coverageFrom.get().takeIf { it > 0L } ?: now,
                coverageGapFrom.get().takeIf { it > 0L },
                knownDropCount.get(),
            )
        }.whenComplete { _, failure ->
            sqlConnecting.set(false)
            if (failure == null) {
                registrationAt = now
                sqlReady.set(true)
                lastFailureCode.set(null)
                advanceCoverageIfIdle(repository, force = true)
                sendOne()
            } else {
                sqlReady.set(false)
                retriesSinceStart.incrementAndGet()
                setFailure(failure, "SQL_INIT_RETRY")
            }
        }
    }

    private fun sendOne() {
        val repository = sqlRef.get() ?: return
        if (!sqlReady.get() || closing.get() || !senderBusy.compareAndSet(false, true)) return
        try {
            journalExecutor.execute {
                val record = try {
                    outboxRef.get()?.next(clockMillis())
                } catch (failure: Throwable) {
                    corruptRecords.incrementAndGet()
                    setFailure(failure, "OUTBOX_READ_FAILED")
                    null
                }
                corruptRecords.set(maxOf(corruptRecords.get(), runCatching { outboxRef.get()?.corruptRecordCount() ?: 0 }.getOrDefault(0)))
                submitSender {
                    if (record == null || closing.get()) {
                        senderBusy.set(false)
                        return@submitSender
                    }
                    repository.insertBatch(record.events).whenComplete { _, failure ->
                        if (failure != null) {
                            submitSender {
                                senderBusy.set(false)
                                retriesSinceStart.incrementAndGet()
                                setFailure(failure, failure.cause?.let(::failureCode) ?: "SQL_SEND_RETRY")
                                sqlReady.set(false)
                            }
                            return@whenComplete
                        }
                        if (closing.get()) {
                            senderBusy.set(false)
                            return@whenComplete
                        }
                        try {
                            journalExecutor.execute {
                                val acknowledgement = runCatching { outboxRef.get()?.acknowledge(record) }
                                val snapshot = runCatching { outboxRef.get()?.stats() }
                                when (val outcome = acknowledgement.getOrNull()) {
                                    ru.arc.persistence.DurableAcknowledgementOutcome.ACKNOWLEDGED,
                                    ru.arc.persistence.DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED,
                                    -> {
                                        val current = snapshot.getOrNull()
                                        durableEvents.set(current?.eventCount ?: (durableEvents.get() - record.events.size))
                                        durableBytes.set(current?.byteCount ?: (durableBytes.get() - record.encodedBytes))
                                        reservedEvents.addAndGet(-record.events.size.toLong())
                                        reservedBytes.addAndGet(-record.encodedBytes)
                                        deliveredSinceStart.addAndGet(record.events.size.toLong())
                                        oldestOutboxAt.set(current?.oldestAt ?: 0L)
                                        updateSaturation()
                                        lastFailureCode.set(null)
                                        if (reservedEvents.get() == 0L) advanceCoverageIfIdle(repository, force = true)
                                    }
                                    ru.arc.persistence.DurableAcknowledgementOutcome.CONTENT_MISMATCH -> {
                                        corruptRecords.incrementAndGet()
                                        setFailure(IllegalStateException("outbox content changed"), "OUTBOX_ACK_MISMATCH")
                                    }
                                    null -> {
                                        retriesSinceStart.incrementAndGet()
                                        setFailure(acknowledgement.exceptionOrNull() ?: IllegalStateException("outbox ack unavailable"), "OUTBOX_ACK_RETRY")
                                    }
                                }
                                submitSender {
                                    senderBusy.set(false)
                                    if (durableEvents.get() > 0L) sendOne()
                                }
                            }
                        } catch (_: java.util.concurrent.RejectedExecutionException) {
                            senderBusy.set(false)
                        }
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            senderBusy.set(false)
        }
    }

    private fun persistDroppedGaps(outbox: PlayerTelemetryOutbox) {
        val drops = pendingPersistedDrops.getAndSet(0)
        val gap = pendingGapAt.getAndSet(0L)
        if (drops == 0L && gap == 0L) return
        val at = gap.takeIf { it > 0L } ?: clockMillis()
        try {
            val snapshot = outbox.noteGap(at, drops)
            coverageFrom.set(snapshot.coverageFrom)
            snapshot.coverageGapFrom?.let(coverageGapFrom::set)
            knownDropCount.set(snapshot.knownDropCount)
        } catch (failure: Throwable) {
            pendingPersistedDrops.addAndGet(drops)
            pendingGapAt.updateAndGet { previous -> if (previous == 0L) at else minOf(previous, at) }
            throw failure
        }
    }

    private fun advanceCoverageIfIdle(repository: PlayerTelemetrySql, force: Boolean = false) {
        if (closing.get() || !sqlReady.get() || reservedEvents.get() != 0L || pendingPersistedDrops.get() != 0L) return
        val now = clockMillis()
        if (!force && now - coverageAdvancedAt.get() < NODE_HEARTBEAT_INTERVAL_MILLIS) return
        if (!coverageAdvanceInFlight.compareAndSet(false, true)) return
        try {
            journalExecutor.execute {
                val snapshot = try {
                    outboxRef.get()?.stats()
                } catch (failure: Throwable) {
                    setFailure(failure, "COVERAGE_SNAPSHOT_FAILED")
                    null
                }
                val stillIdle = snapshot != null && snapshot.eventCount == 0L && synchronized(stateLock) {
                    reservedEvents.get() == 0L && queue.isEmpty() && pendingJournalEvents.get() == 0 &&
                        pendingPersistedDrops.get() == 0L && !closing.get()
                }
                if (!stillIdle) {
                    coverageAdvanceInFlight.set(false)
                    return@execute
                }
                val through = (clockMillis() - COVERAGE_WATERMARK_LAG_MILLIS).coerceAtLeast(snapshot.coverageFrom)
                val gap = listOfNotNull(
                    coverageGapFrom.get().takeIf { it > 0L },
                    runtimeGap.get().takeIf { it > 0L },
                ).minOrNull()
                submitSender {
                    repository.advanceCoverage(server, through, gap, knownDropCount.get()).whenComplete { _, failure ->
                        if (failure != null) {
                            retriesSinceStart.incrementAndGet()
                            setFailure(failure, "SQL_COVERAGE_ADVANCE_RETRY")
                        } else {
                            coverageAdvancedAt.set(clockMillis())
                        }
                        coverageAdvanceInFlight.set(false)
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            coverageAdvanceInFlight.set(false)
        }
    }

    private fun lose(at: Long) {
        droppedSinceStart.incrementAndGet()
        pendingPersistedDrops.incrementAndGet()
        pendingGapAt.updateAndGet { previous -> if (previous == 0L) at else minOf(previous, at) }
        runtimeGap.updateAndGet { previous -> if (previous == 0L) at else minOf(previous, at) }
        // The bounded journal tick coalesces concurrent losses into one marker write.
    }

    private fun markGap(at: Long) = lose(at)

    private fun validateQuery(request: PlayerTelemetryQuery) {
        require(request.limit <= settings.maxQueryLimit) { "Query limit exceeds configured maximum" }
        require(request.untilExclusive - request.fromInclusive <= settings.retentionDays * MILLIS_PER_DAY) {
            "Query window exceeds configured telemetry retention"
        }
    }

    private fun <T> withQuerySlot(query: () -> CompletableFuture<T>): CompletableFuture<T> {
        if (!querySlots.tryAcquire()) {
            return CompletableFuture.failedFuture(PlayerTelemetryStoreUnavailableException("Player telemetry query capacity is busy"))
        }
        val result = try {
            query()
        } catch (failure: Throwable) {
            querySlots.release()
            return CompletableFuture.failedFuture(failure)
        }
        return result.whenComplete { _, _ -> querySlots.release() }
    }

    private fun validateSummary(request: PlayerTelemetrySummaryQuery) {
        require(request.limit <= settings.maxSummaryGroups) { "Summary limit exceeds configured maximum" }
        require(request.untilExclusive - request.fromInclusive <= settings.retentionDays * MILLIS_PER_DAY) {
            "Summary window exceeds configured telemetry retention"
        }
    }

    private fun updateSaturation() {
        synchronized(stateLock) {
            saturated.set(
                reservedEvents.get() >= settings.maxOutboxEvents || reservedBytes.get() >= settings.maxOutboxBytes ||
                    queue.remainingCapacity() == 0,
            )
            accepting.set(started.get() && !closing.get() && reservedEvents.get() < settings.maxOutboxEvents &&
                reservedBytes.get() < settings.maxOutboxBytes)
        }
    }

    private fun closeSql() {
        runCatching { sqlRef.getAndSet(null)?.close() }
            .onFailure { setFailure(it, "SQL_CLOSE_FAILED") }
        sqlReady.set(false)
        senderExecutor.shutdown()
    }

    private fun submitSender(action: () -> Unit) {
        try {
            senderExecutor.execute(action)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            senderBusy.set(false)
        }
    }

    private fun setFailure(failure: Throwable, fallback: String) {
        lastFailureCode.set(failureCode(failure).takeIf { it != "RuntimeException" } ?: fallback)
    }

    private fun failureCode(failure: Throwable): String =
        failure::class.simpleName?.uppercase()?.replace(Regex("[^A-Z0-9_]"), "")?.take(48) ?: "STORAGE_FAILURE"

    private fun executor(name: String): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, name).apply { isDaemon = true } }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val RETENTION_CHECK_INTERVAL_MILLIS = 60_000L
        private const val NODE_HEARTBEAT_INTERVAL_MILLIS = 60_000L
        private const val COVERAGE_WATERMARK_LAG_MILLIS = 1_000L
        private const val MAX_ONGOING_QUERIES = 4
        private val SERVER_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}")

        private fun validateServer(server: String): String {
            require(SERVER_ID.matches(server)) { "server must be a bounded safe identifier" }
            return server
        }
    }
}

class PlayerTelemetryStoreUnavailableException(message: String) : IllegalStateException(message)
