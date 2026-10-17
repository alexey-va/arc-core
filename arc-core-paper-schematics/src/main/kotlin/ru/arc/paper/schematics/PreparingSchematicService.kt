package ru.arc.paper.schematics

import ru.arc.paper.api.*
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Stable native service identity while the host prepares its file-backed limits. */
internal class PreparingSchematicService : ArcSchematicService, AutoCloseable {
    private val monitor = Any()
    private val ready = CompletableFuture<SharedSchematicService>()
    private val libraries = mutableSetOf<Library>()
    private val requests = mutableSetOf<CompletableFuture<*>>()
    private var delegate: SharedSchematicService? = null
    private var closed = false

    /** Owner-thread attach, with no filesystem work. Late candidates are closed instead. */
    fun attach(service: SharedSchematicService) {
        val accepted = synchronized(monitor) {
            if (closed) false else { delegate = service; true }
        }
        if (accepted) ready.complete(service) else service.close()
    }

    fun fail(failure: Throwable) { ready.completeExceptionally(failure) }

    override fun open(owner: String, root: Path): ArcSchematicLibrary {
        require(owner.isNotBlank() && owner.length <= 128 && owner.none(Char::isISOControl))
        return synchronized(monitor) {
            check(!closed) { "Schematic provider is closed" }
            if (libraries.size >= 256) throw ArcSchematicException(ArcSchematicFailure.BUSY, "Schematic library lease limit reached")
            Library(owner, root.toAbsolutePath().normalize()).also { libraries += it }
        }
    }

    override fun diagnostics(): ArcSchematicDiagnostics = synchronized(monitor) {
        delegate?.diagnostics() ?: ArcSchematicDiagnostics(0, 0, requests.size, libraries.size, closed)
    }

    override fun close() {
        val owned = synchronized(monitor) {
            if (closed) return
            closed = true
            libraries.toList().also { libraries.clear() }
        }
        owned.forEach(Library::close)
        ready.completeExceptionally(ArcSchematicException(ArcSchematicFailure.CLOSED, "Schematic provider is closed"))
        delegate?.close()
    }

    private inner class Library(owner: String, root: Path) : ArcSchematicLibrary {
        private var stopped = false
        private val ownedRequests = mutableSetOf<CompletableFuture<*>>()
        private val backing = ready.thenApply { service ->
            synchronized(monitor) { check(!closed && !stopped) { "Schematic library is closed" } }
            service.open(owner, root)
        }

        private fun <T> request(action: (ArcSchematicLibrary) -> CompletableFuture<T>): CompletableFuture<T> {
            val result = CompletableFuture<T>()
            val rejected = synchronized(monitor) {
                when {
                    closed || stopped -> ArcSchematicFailure.CLOSED
                    requests.size >= 1024 -> ArcSchematicFailure.BUSY
                    else -> { requests += result; ownedRequests += result; null }
                }
            }
            if (rejected != null) return CompletableFuture.failedFuture(ArcSchematicException(rejected, "Schematic request was not admitted"))
            backing.thenCompose(action).whenComplete { value, failure ->
                val usable = synchronized(monitor) {
                    requests.remove(result)
                    ownedRequests.remove(result)
                    !closed && !stopped
                }
                when {
                    failure != null -> result.completeExceptionally(failure)
                    !usable -> result.completeExceptionally(ArcSchematicException(ArcSchematicFailure.CLOSED, "Schematic library is closed"))
                    else -> result.complete(value)
                }
            }
            return result
        }

        override fun load(id: String) = request { it.load(id) }
        override fun load(id: String, contentPolicy: ArcSchematicContentPolicy) = request { it.load(id, contentPolicy) }
        override fun loadVerified(id: String, expectedFileSha256: String) = request { it.loadVerified(id, expectedFileSha256) }
        override fun loadVerified(id: String, expectedFileSha256: String, contentPolicy: ArcSchematicContentPolicy) = request { it.loadVerified(id, expectedFileSha256, contentPolicy) }
        override fun captureSource(id: String, expectedFileSha256: String) = request { it.captureSource(id, expectedFileSha256) }
        override fun transform(snapshot: ArcSchematicSnapshot, transform: ArcSchematicTransform) = request { it.transform(snapshot, transform) }
        override fun crop(snapshot: ArcSchematicSnapshot, minimum: ArcSchematicPoint, maximum: ArcSchematicPoint) = request { it.crop(snapshot, minimum, maximum) }
        override fun inspectBlockEntityNbt(snapshot: ArcSchematicSnapshot, visitor: java.util.function.BiConsumer<ArcSchematicPoint, String>) = request { it.inspectBlockEntityNbt(snapshot, visitor) }
        override fun invalidate(id: String) = request { it.invalidate(id) }

        private fun active(): ArcSchematicLibrary {
            synchronized(monitor) { check(!closed && !stopped) { "Schematic library is closed" } }
            return backing.getNow(null) ?: throw ArcSchematicException(ArcSchematicFailure.BUSY, "Schematic provider is starting")
        }

        override fun paste(snapshot: ArcSchematicSnapshot, target: ArcSchematicPasteTarget, options: ArcSchematicPasteOptions) = active().paste(snapshot, target, options)
        override fun repairConnections(worldId: UUID, blocks: List<ArcSchematicBlock>) = active().repairConnections(worldId, blocks)

        override fun close() {
            val pending = synchronized(monitor) {
                if (stopped) return
                stopped = true
                libraries.remove(this)
                ownedRequests.toList().also { requests.removeAll(it.toSet()); ownedRequests.clear() }
            }
            pending.forEach { it.completeExceptionally(ArcSchematicException(ArcSchematicFailure.CLOSED, "Schematic library is closed")) }
            backing.thenAccept(AutoCloseable::close)
        }
    }
}
