package ru.arc.paper.schematics

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.TaskScheduler
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicCacheSettings
import ru.arc.paper.api.ArcSchematicContentPolicy
import ru.arc.paper.api.ArcSchematicDiagnostics
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import ru.arc.paper.api.ArcSchematicLibrary
import ru.arc.paper.api.ArcSchematicMutationResult
import ru.arc.paper.api.ArcSchematicOperation
import ru.arc.paper.api.ArcSchematicPasteOptions
import ru.arc.paper.api.ArcSchematicPasteTarget
import ru.arc.paper.api.ArcSchematicService
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.api.ArcSchematicTransform
import java.nio.file.Path
import java.util.Collections
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/** Optional WorldEdit integration, installed and closed once by the ArcCore host. */
class PaperSchematicServiceProvider(
    plugin: Plugin,
    settings: CompletableFuture<ArcSchematicCacheSettings>,
) : AutoCloseable {
    constructor(plugin: Plugin, settings: ArcSchematicCacheSettings) : this(plugin, CompletableFuture.completedFuture(settings))

    private val services = plugin.server.servicesManager
    private val scheduler: TaskScheduler = BukkitTaskScheduler(plugin)
    private val service = PreparingSchematicService()
    private val closed = AtomicBoolean()

    init {
        try {
            check(services.load(ArcSchematicService::class.java) == null) { "An ArcSchematicService provider is already registered" }
            services.register(ArcSchematicService::class.java, service, plugin, ServicePriority.Normal)
            settings.whenComplete { prepared, preparationFailure ->
                if (preparationFailure != null) service.fail(preparationFailure)
                else if (!closed.get()) {
                    val attach = Runnable {
                        if (!closed.get()) {
                            try {
                                service.attach(SharedSchematicService(prepared, scheduler, WorldEditSchematicCodec(prepared), System::nanoTime))
                            } catch (problem: Throwable) { service.fail(problem) }
                        }
                    }
                    if (Bukkit.isPrimaryThread()) attach.run()
                    else try { scheduler.runSync(attach) } catch (problem: Throwable) { service.fail(problem) }
                }
            }
        } catch (problem: Throwable) {
            service.close()
            scheduler.close()
            throw problem
        }
    }

    fun diagnostics(): ArcSchematicDiagnostics = service.diagnostics()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        services.unregister(ArcSchematicService::class.java, service)
        try { service.close() } finally { scheduler.close() }
    }
}

internal class SharedSchematicService(
    private val settings: ArcSchematicCacheSettings,
    scheduler: TaskScheduler,
    private val codec: SchematicCodec,
    private val nanoTime: () -> Long,
) : ArcSchematicService, AutoCloseable {
    private data class Cached(val identity: FileIdentity, val snapshot: SchematicSnapshot, var accessed: Long)
    private data class LoadKey(val path: Path, val identity: FileIdentity)
    private data class DecodeKey(val path: Path, val sha256: String)
    private data class PendingPipeline(val run: Runnable, val reject: () -> Unit)
    private val monitor = Any()
    private val producer = Any()
    private val files = SchematicFiles(settings)
    private val workers = ThreadPoolExecutor(
        settings.workerThreads, settings.workerThreads, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(settings.maximumPendingRequests * 2),
        { runnable -> Thread(runnable, "arc-schematic-${THREAD_NUMBER.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val main = SchematicMainThread(scheduler, settings)
    private val cache = LinkedHashMap<Path, Cached>(16, 0.75f, true)
    private val loads = HashMap<LoadKey, CompletableFuture<SchematicSnapshot>>()
    private val decodes = HashMap<DecodeKey, CompletableFuture<SchematicSnapshot>>()
    private val pipelines = Collections.newSetFromMap(IdentityHashMap<CompletableFuture<*>, Boolean>())
    private val waitingPipelines = ArrayDeque<PendingPipeline>()
    private val requests = IdentityHashMap<CompletableFuture<*>, Library>()
    private val libraries = Collections.newSetFromMap(IdentityHashMap<Library, Boolean>())
    private var cachedBytes = 0L
    private var activePipelines = 0
    private var revision = 0L
    private var closed = false
    private val sweep = scheduler.runTimerAsync(
        (settings.ttlMillis / 50).coerceIn(20, 1_200),
        (settings.ttlMillis / 50).coerceIn(20, 1_200),
        Runnable { synchronized(monitor) { expireLocked() } },
    )

    override fun open(owner: String, root: Path): ArcSchematicLibrary {
        require(owner.isNotBlank() && owner.length <= 128 && owner.none(Char::isISOControl)) { "Schematic owner must be a bounded name" }
        val library = Library(owner, root.toAbsolutePath().normalize())
        synchronized(monitor) {
            ensureOpen()
            if (libraries.size >= 256) failure(ArcSchematicFailure.BUSY, "Schematic library lease limit reached")
            libraries += library
        }
        return library
    }

    override fun diagnostics(): ArcSchematicDiagnostics = synchronized(monitor) {
        ArcSchematicDiagnostics(cache.size, cachedBytes, requests.size, libraries.size, closed)
    }

    internal fun pipelineCounts(): Pair<Int, Int> = synchronized(monitor) { activePipelines to waitingPipelines.size }

    override fun close() {
        val pending: List<CompletableFuture<*>>
        val flights: List<CompletableFuture<*>>
        val owners: List<Library>
        synchronized(monitor) {
            if (closed) return
            closed = true
            revision++
            cache.clear()
            cachedBytes = 0
            pending = requests.keys.toList()
            flights = loads.values.toList() + decodes.values.toList() + pipelines.toList()
            owners = libraries.toList()
            loads.clear()
            decodes.clear()
            waitingPipelines.clear()
            pipelines.clear()
            libraries.clear()
            requests.clear()
        }
        // Never run consumer callbacks while holding the cache monitor.
        owners.forEach { it.cancelOperations() }
        val problem = ArcSchematicException(ArcSchematicFailure.CLOSED, "Schematic provider is closed")
        pending.forEach { it.completeExceptionally(problem) }
        flights.forEach { it.completeExceptionally(problem) }
        sweep.cancel()
        main.close()
        workers.shutdownNow() // no main-thread join; late results cannot re-enter the cache
    }

    private fun ensureOpen() {
        if (closed) failure(ArcSchematicFailure.CLOSED, "Schematic provider is closed")
    }

    private fun <T> request(library: Library, action: () -> CompletionStage<T>): CompletableFuture<T> {
        val client = CompletableFuture<T>()
        val rejection = synchronized(monitor) {
            when {
                closed || library.closed -> ArcSchematicFailure.CLOSED
                requests.size >= settings.maximumPendingRequests -> ArcSchematicFailure.BUSY
                else -> { requests[client] = library; null }
            }
        }
        if (rejection != null) {
            client.completeExceptionally(ArcSchematicException(rejection, "Schematic request was not admitted"))
            return client
        }
        fun finish(value: T?, problem: Throwable?) {
            val usable = synchronized(monitor) {
                requests.remove(client)
                !closed && !library.closed
            }
            when {
                problem != null -> client.completeExceptionally(unwrap(problem))
                !usable -> client.completeExceptionally(ArcSchematicException(ArcSchematicFailure.CLOSED, "Schematic library is closed"))
                else -> client.complete(value)
            }
        }
        try {
            workers.execute {
                try {
                    synchronized(monitor) {
                        ensureOpen()
                        if (library.closed) failure(ArcSchematicFailure.CLOSED, "Schematic library is closed")
                    }
                    action().whenComplete { value, problem -> finish(value, problem) }
                } catch (problem: Throwable) { finish(null, problem) }
            }
        } catch (problem: RejectedExecutionException) {
            finish(null, ArcSchematicException(ArcSchematicFailure.BUSY, "Schematic worker queue is full").also { it.initCause(problem) })
        }
        return client
    }

    private fun acquire(root: Path, id: String, fresh: Boolean): CompletionStage<SchematicSnapshot> {
        val file = files.resolve(root, id)
        val key = LoadKey(file.path, file.identity)
        val result = CompletableFuture<SchematicSnapshot>()
        val startRevision: Long
        synchronized(monitor) {
            ensureOpen()
            expireLocked()
            val current = cache[file.path]
            if (!fresh && current != null && current.identity == file.identity) {
                current.accessed = nanoTime()
                return CompletableFuture.completedFuture(current.snapshot)
            }
            // A verified acquisition must read its own fresh bytes. Only ordinary
            // acquisitions may coalesce before reading, and only at the same identity.
            if (!fresh) {
                loads[key]?.let { return it }
                loads[key] = result
            }
            startRevision = revision
        }
        pipeline { decode(files.read(file)) }.whenComplete { snapshot, problem ->
            val usable = synchronized(monitor) {
                if (loads[key] === result) loads.remove(key)
                if (!closed && revision == startRevision && problem == null) putLocked(file, snapshot)
                !closed
            }
            when {
                problem != null -> result.completeExceptionally(unwrap(problem))
                !usable -> result.completeExceptionally(ArcSchematicException(ArcSchematicFailure.CLOSED, "Schematic provider is closed"))
                else -> result.complete(snapshot)
            }
        }
        return result
    }

    private fun decode(bytes: SchematicBytes): CompletionStage<SchematicSnapshot> {
        val key = DecodeKey(bytes.file.path, bytes.sha256)
        val result = CompletableFuture<SchematicSnapshot>()
        synchronized(monitor) {
            ensureOpen()
            cache[bytes.file.path]?.snapshot?.takeIf { it.fileSha256 == bytes.sha256 }?.let { return CompletableFuture.completedFuture(it) }
            decodes[key]?.let { return it }
            decodes[key] = result
        }
        val preparation = try {
            val decoded = codec.decode(bytes)
            main.palette(decoded.palette, codec::canonicalize).thenApplyAsync({ palette ->
                SchematicSnapshot(
                    producer, decoded.minimum, decoded.maximum, decoded.origin, bytes.sha256,
                    decoded.hasBiomes, decoded.entityCount, palette, decoded.indices, decoded.blockEntities,
                )
            }, workers)
        } catch (problem: Throwable) { CompletableFuture.failedFuture(problem) }
        preparation.whenComplete { snapshot, problem ->
            synchronized(monitor) { if (decodes[key] === result) decodes.remove(key) }
            if (problem != null) result.completeExceptionally(unwrap(problem)) else result.complete(snapshot)
        }
        return result
    }

    /** Holds one shared permit through decode/transform, Paper palette preparation and final hashing. */
    private fun <T> pipeline(action: () -> CompletionStage<T>): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        val terminal = AtomicBoolean()
        fun finish(value: T?, problem: Throwable?) {
            if (!terminal.compareAndSet(false, true)) return
            val next = synchronized(monitor) {
                activePipelines--
                pipelines.remove(result)
                if (closed) null else waitingPipelines.pollFirst()?.also { activePipelines++ }
            }
            if (next != null) {
                try { workers.execute(next.run) } catch (_: RejectedExecutionException) { next.reject() }
            }
            if (problem != null) result.completeExceptionally(unwrap(problem)) else result.complete(value)
        }
        val pending = PendingPipeline(
            Runnable {
                try {
                    synchronized(monitor) { ensureOpen() }
                    action().whenComplete { value, problem -> finish(value, problem) }
                } catch (problem: Throwable) { finish(null, problem) }
            },
            { finish(null, ArcSchematicException(ArcSchematicFailure.CLOSED, "Schematic worker stopped")) },
        )
        val startNow = synchronized(monitor) {
            ensureOpen()
            pipelines += result
            if (activePipelines < settings.workerThreads) { activePipelines++; true }
            else { waitingPipelines.addLast(pending); false }
        }
        if (startNow) pending.run.run()
        return result
    }

    private fun putLocked(file: SchematicFile, snapshot: SchematicSnapshot) {
        cache.remove(file.path)?.let { cachedBytes -= it.snapshot.retainedBytes }
        if (snapshot.retainedBytes > settings.maximumCachedBytes) return
        cache[file.path] = Cached(file.identity, snapshot, nanoTime())
        cachedBytes += snapshot.retainedBytes
        while (cache.size > settings.maximumEntries || cachedBytes > settings.maximumCachedBytes) {
            val iterator = cache.entries.iterator()
            cachedBytes -= iterator.next().value.snapshot.retainedBytes
            iterator.remove()
        }
    }

    private fun expireLocked() {
        val now = nanoTime()
        val ttl = TimeUnit.MILLISECONDS.toNanos(settings.ttlMillis)
        val iterator = cache.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val age = now - entry.accessed
            if (age < 0 || age >= ttl) { cachedBytes -= entry.snapshot.retainedBytes; iterator.remove() }
        }
    }

    private fun owned(snapshot: ArcSchematicSnapshot): SchematicSnapshot =
        (snapshot as? SchematicSnapshot)?.takeIf { it.producer === producer }
            ?: failure(ArcSchematicFailure.INVALID_SNAPSHOT, "Schematic snapshot belongs to another provider")

    private inner class Library(val owner: String, val root: Path) : ArcSchematicLibrary {
        @Volatile var closed = false
        private val operations = Collections.newSetFromMap(IdentityHashMap<DeferredOperation, Boolean>())

        override fun load(id: String): CompletableFuture<ArcSchematicSnapshot> = load(id, ArcSchematicContentPolicy.REQUIRE_BLOCKS_ONLY)
        override fun load(id: String, contentPolicy: ArcSchematicContentPolicy): CompletableFuture<ArcSchematicSnapshot> =
            loadInternal(id, null, contentPolicy)

        override fun loadVerified(id: String, expectedFileSha256: String): CompletableFuture<ArcSchematicSnapshot> =
            loadVerified(id, expectedFileSha256, ArcSchematicContentPolicy.REQUIRE_BLOCKS_ONLY)

        override fun loadVerified(id: String, expectedFileSha256: String, contentPolicy: ArcSchematicContentPolicy): CompletableFuture<ArcSchematicSnapshot> =
            loadInternal(id, expectedFileSha256, contentPolicy)

        override fun captureSource(id: String, expectedFileSha256: String): CompletableFuture<ByteArray> = request(this) {
            if (!expectedFileSha256.matches(Regex("[0-9a-f]{64}"))) failure(ArcSchematicFailure.HASH_MISMATCH, "Invalid expected schematic fingerprint")
            val source = files.read(files.resolve(root, id))
            if (source.sha256 != expectedFileSha256) failure(ArcSchematicFailure.HASH_MISMATCH, "Schematic fingerprint changed")
            CompletableFuture.completedFuture(source.bytes)
        }

        private fun loadInternal(id: String, expected: String?, policy: ArcSchematicContentPolicy): CompletableFuture<ArcSchematicSnapshot> = request(this) {
            if (expected != null && !expected.matches(Regex("[0-9a-f]{64}"))) failure(ArcSchematicFailure.HASH_MISMATCH, "Invalid expected schematic fingerprint")
            acquire(root, id, expected != null).thenApply { snapshot ->
                if (expected != null && expected != snapshot.fileSha256) failure(ArcSchematicFailure.HASH_MISMATCH, "Schematic fingerprint changed")
                if (policy == ArcSchematicContentPolicy.REQUIRE_BLOCKS_ONLY && (snapshot.hasBiomes || snapshot.entityCount != 0)) {
                    failure(ArcSchematicFailure.UNSUPPORTED_CONTENT, "Schematic contains entities or biomes; choose an explicit block-only extraction policy")
                }
                snapshot
            }
        }

        override fun transform(snapshot: ArcSchematicSnapshot, transform: ArcSchematicTransform): CompletableFuture<ArcSchematicSnapshot> = request(this) {
            val original = owned(snapshot)
            pipeline {
                main.palette(original.palette) { codec.transform(it, transform) }
                    .thenApplyAsync({ palette -> original.transformed(transform, palette) }, workers)
            }
        }

        override fun crop(snapshot: ArcSchematicSnapshot, minimum: ArcSchematicPoint, maximum: ArcSchematicPoint): CompletableFuture<ArcSchematicSnapshot> = request(this) {
            val original = owned(snapshot)
            pipeline { CompletableFuture.completedFuture(original.cropped(minimum, maximum, ::checkWorkerCurrent)) }
        }

        override fun inspectBlockEntityNbt(snapshot: ArcSchematicSnapshot, visitor: java.util.function.BiConsumer<ArcSchematicPoint, String>): CompletableFuture<Void> = request(this) {
            val original = owned(snapshot)
            pipeline {
                original.inspectBlockEntityNbt(visitor, ::checkWorkerCurrent)
                CompletableFuture.completedFuture<Void>(null)
            }
        }

        private fun checkWorkerCurrent() {
            if (closed || Thread.currentThread().isInterrupted) failure(ArcSchematicFailure.CLOSED, "Schematic library is closed")
        }

        override fun invalidate(id: String): CompletableFuture<Boolean> = request(this) {
            val candidates = files.candidates(root, id)
            val removed = synchronized(monitor) {
                revision++
                var any = false
                candidates.forEach { path ->
                    loads.keys.removeIf { it.path == path }
                    decodes.keys.removeIf { it.path == path }
                    cache.remove(path)?.let { cachedBytes -= it.snapshot.retainedBytes; any = true }
                }
                any
            }
            CompletableFuture.completedFuture(removed)
        }

        override fun paste(snapshot: ArcSchematicSnapshot, target: ArcSchematicPasteTarget, options: ArcSchematicPasteOptions): ArcSchematicOperation =
            mutation { NativeSchematicMutation.paste(owner, owned(snapshot), target, options) }

        override fun paste(
            snapshot: ArcSchematicSnapshot,
            target: ArcSchematicPasteTarget,
            options: ArcSchematicPasteOptions,
            acceptedBlock: Consumer<ArcSchematicBlock>,
        ): ArcSchematicOperation =
            mutation { NativeSchematicMutation.paste(owner, owned(snapshot), target, options, acceptedBlock) }

        override fun paste(
            snapshot: ArcSchematicSnapshot,
            target: ArcSchematicPasteTarget,
            options: ArcSchematicPasteOptions,
            onPlaced: Consumer<ArcSchematicBlock>,
            ready: BooleanSupplier,
            intervalTicks: Int,
        ): ArcSchematicOperation {
            require(intervalTicks >= 1) { "Schematic paste interval must be at least one tick" }
            return mutation { NativeSchematicMutation.paste(owner, owned(snapshot), target, options, onPlaced, ready, intervalTicks) }
        }

        override fun repairConnections(worldId: UUID, blocks: List<ArcSchematicBlock>): ArcSchematicOperation = mutation {
            if (blocks.size > settings.maximumVolume) failure(ArcSchematicFailure.LIMIT_EXCEEDED, "Connection repair exceeds the block limit")
            NativeSchematicMutation.repair(owner, worldId, blocks.toList())
        }

        private fun mutation(create: () -> NativeSchematicMutation): ArcSchematicOperation {
            val operation = DeferredOperation()
            synchronized(operations) { operations += operation }
            val future = request(this) {
                if (operation.cancelled.get()) CompletableFuture.completedFuture(ArcSchematicMutationResult(0, 0, true))
                else {
                    val work = create()
                    operation.attach(work)
                    main.enqueueMutation(work, work.completion)
                }
            }
            future.whenComplete { result, problem ->
                synchronized(operations) { operations.remove(operation) }
                if (problem != null) operation.completion.completeExceptionally(unwrap(problem))
                else operation.completion.complete(result)
            }
            return operation
        }

        fun cancelOperations() = synchronized(operations) { operations.toList() }.forEach { it.cancel() }

        override fun close() {
            val pending = synchronized(monitor) {
                if (closed) return
                closed = true
                libraries.remove(this)
                requests.entries.filter { it.value === this }.map { it.key }
            }
            cancelOperations()
            val problem = ArcSchematicException(ArcSchematicFailure.CLOSED, "Schematic library is closed")
            pending.forEach { it.completeExceptionally(problem) }
        }
    }

    private class DeferredOperation : ArcSchematicOperation {
        override val completion = CompletableFuture<ArcSchematicMutationResult>()
        val cancelled = AtomicBoolean(false)
        @Volatile private var work: NativeSchematicMutation? = null

        fun attach(value: NativeSchematicMutation) {
            work = value
            if (cancelled.get()) value.cancel()
        }

        override fun cancel(): Boolean {
            if (completion.isDone || !cancelled.compareAndSet(false, true)) return false
            work?.cancel()
            return true
        }
    }

    companion object {
        private val THREAD_NUMBER = AtomicInteger()
        private fun unwrap(problem: Throwable): Throwable = if (problem is CompletionException && problem.cause != null) problem.cause!! else problem
    }
}
