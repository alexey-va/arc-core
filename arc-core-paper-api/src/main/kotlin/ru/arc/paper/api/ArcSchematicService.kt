package ru.arc.paper.api

import java.nio.file.Path
import java.util.UUID
import java.util.function.BiConsumer
import java.util.concurrent.CompletableFuture

/** One host-wide budget, shared by every library and consumer. No files are read at installation. */
data class ArcSchematicCacheSettings(
    val ttlMillis: Long,
    val maximumEntries: Int,
    val maximumCachedBytes: Long,
    val maximumFileBytes: Int,
    val maximumInflatedBytes: Int,
    val maximumVolume: Int,
    val maximumPendingRequests: Int,
    val workerThreads: Int,
    val palettePerTick: Int,
    val blocksPerTick: Int,
) {
    init {
        require(ttlMillis in 1_000..3_600_000) { "Schematic TTL must be between one second and one hour" }
        require(maximumEntries in 1..1_024)
        require(maximumCachedBytes in 1..1_073_741_824L)
        require(maximumFileBytes in 1..67_108_864)
        require(maximumInflatedBytes in maximumFileBytes..268_435_456)
        require(maximumVolume in 1..16_000_000)
        require(maximumPendingRequests in 1..1_024)
        require(workerThreads in 1..8)
        require(palettePerTick in 1..1_024)
        require(blocksPerTick in 1..4_096)
    }

    companion object {
        @JvmStatic
        fun defaults(): ArcSchematicCacheSettings = ArcSchematicCacheSettings(
            120_000, 64, 134_217_728, 16_777_216, 67_108_864,
            2_000_000, 64, 2, 128, 512,
        )
    }
}

/** Host-owned service. [open] only records an explicit root; it never scans or preloads a library. */
interface ArcSchematicService {
    fun open(owner: String, root: Path): ArcSchematicLibrary
    fun diagnostics(): ArcSchematicDiagnostics
}

/**
 * Consumer-owned view of the shared cache. Close it on consumer shutdown.
 *
 * IDs are relative, extensionless paths such as `civs/house`. Acquisition,
 * fingerprint verification, invalidation and transforms always run through a
 * bounded worker queue, including cache hits. Never join a future on Paper's
 * primary thread. Completion callbacks have no promised thread affinity.
 *
 * The configured root may itself be a symlink. Links below it, traversal and
 * absolute IDs are rejected. Legacy explicit `.schem`/`.schematic` suffixes
 * remain supported; extensionless IDs reject ambiguous dual-file matches.
 */
interface ArcSchematicLibrary : AutoCloseable {
    fun load(id: String): CompletableFuture<ArcSchematicSnapshot>
    fun load(id: String, contentPolicy: ArcSchematicContentPolicy): CompletableFuture<ArcSchematicSnapshot>

    /** Reads and hashes fresh bytes, then decodes or reuses exactly that version. */
    fun loadVerified(id: String, expectedFileSha256: String): CompletableFuture<ArcSchematicSnapshot>
    fun loadVerified(id: String, expectedFileSha256: String, contentPolicy: ArcSchematicContentPolicy): CompletableFuture<ArcSchematicSnapshot>

    /**
     * Fresh, bounded source bytes for a consumer's durable construction journal.
     * Uses the same safe resolver, worker admission and fingerprint checks as loading.
     * The returned array belongs to the caller; it is never shared with the cache.
     * Does not decode a clipboard or retain the bytes in the provider.
     */
    fun captureSource(id: String, expectedFileSha256: String): CompletableFuture<ByteArray>

    /** Mirror local X first, then rotate around the snapshot origin. The result has origin (0,0,0). */
    fun transform(snapshot: ArcSchematicSnapshot, transform: ArcSchematicTransform): CompletableFuture<ArcSchematicSnapshot>

    /**
     * Worker-only inclusive crop in the snapshot's absolute coordinates. Bounds must be nonempty
     * and entirely inside the source. Coordinates, source origin, fingerprint and source-extra
     * metadata are preserved; the content hash is recomputed and block-entity NBT follows its cell.
     * The returned snapshot belongs to the caller and is not added to the shared cache.
     */
    fun crop(snapshot: ArcSchematicSnapshot, minimum: ArcSchematicPoint, maximum: ArcSchematicPoint): CompletableFuture<ArcSchematicSnapshot> =
        CompletableFuture.failedFuture(ArcSchematicException(ArcSchematicFailure.UNSUPPORTED_CONTENT, "Schematic cropping is unavailable"))

    /**
     * Visits each retained block entity on a bounded provider worker, never on Paper's thread.
     * [visitor] receives its absolute snapshot position and detached LinCompoundTag.toString text,
     * with the same representation as WorldEdit BaseBlock.nbtReference.value.toString(). No native
     * object or mutable map escapes. The visitor must not access Bukkit/world/player state.
     * Throwing rejects this future; await success before any consumer mutation. Inspection itself
     * supplies no security policy and never changes the snapshot. Completion has no thread affinity.
     */
    fun inspectBlockEntityNbt(snapshot: ArcSchematicSnapshot, visitor: BiConsumer<ArcSchematicPoint, String>): CompletableFuture<Void> =
        CompletableFuture.failedFuture(ArcSchematicException(ArcSchematicFailure.UNSUPPORTED_CONTENT, "Schematic NBT inspection is unavailable"))

    fun invalidate(id: String): CompletableFuture<Boolean>

    /**
     * Bounded primary-thread block paste. Does not supply protection, payment,
     * undo or a durable transaction; those remain the caller's responsibility.
     */
    fun paste(snapshot: ArcSchematicSnapshot, target: ArcSchematicPasteTarget, options: ArcSchematicPasteOptions): ArcSchematicOperation

    /**
     * Repairs native fence/wall/pane shapes only at supplied WORLD coordinates.
     * A block is skipped if its current state differs from the expected string.
     * Invoke only after a consumer's durable transaction has committed.
     */
    fun repairConnections(worldId: UUID, blocks: List<ArcSchematicBlock>): ArcSchematicOperation

    /** Terminal. Cancels this view's requests and mutations without closing other consumers. */
    override fun close()
}

/**
 * Immutable, independently retainable snapshot; no Clipboard or mutable BlockData escapes.
 *
 * Reads are memory-only. Iterating a large snapshot is CPU work and belongs on
 * a worker. Cache eviction/close releases the provider's reference; consumers
 * must release their snapshots when a project/preview ends. The cache budget
 * cannot bound references deliberately retained by callers.
 *
 * Block coordinates are absolute within [minimum]..[maximum]. A transformed
 * snapshot uses origin-relative coordinates and origin zero. Air is retained.
 */
interface ArcSchematicSnapshot {
    val minimum: ArcSchematicPoint
    val maximum: ArcSchematicPoint
    val origin: ArcSchematicPoint
    val sizeX: Int
    val sizeY: Int
    val sizeZ: Int
    val volume: Long
    val nonBarrierCount: Int
    /** Source extras are reported even when the caller explicitly selected block-only extraction. */
    val hasBiomes: Boolean
    val entityCount: Int
    val fileSha256: String
    /** Historical Bukkit-string hash: dimensions, then non-air cells ordered y,x,z, relative to minimum. */
    val contentSha256: String
    fun blockAt(x: Int, y: Int, z: Int): ArcSchematicBlock
    fun blocks(): List<ArcSchematicBlock>
}

data class ArcSchematicPoint(val x: Int, val y: Int, val z: Int)
data class ArcSchematicBlock(val position: ArcSchematicPoint, val blockData: String, val isAir: Boolean, val isBarrier: Boolean)
data class ArcSchematicTransform(val quarterTurns: Int, val mirrorX: Boolean)
enum class ArcSchematicContentPolicy { REQUIRE_BLOCKS_ONLY, IGNORE_ENTITIES_AND_BIOMES }

/** Pure ID validation shared by catalogs, persisted books and the runtime resolver. */
object ArcSchematicIds {
    @JvmStatic
    fun isValid(id: String): Boolean = id.length in 1..160 &&
        !id.startsWith('/') && '\\' !in id && ':' !in id &&
        id.none { it.isISOControl() } &&
        id.split('/').all { it.isNotBlank() && it != "." && it != ".." }
}
data class ArcSchematicPasteTarget(val worldId: UUID, val anchor: ArcSchematicPoint)
data class ArcSchematicPasteOptions(val ignoreAir: Boolean, val ignoreBarriers: Boolean, val repairConnections: Boolean)

/**
 * Cancellation is cooperative between bounded batches. A provider-produced
 * completion guarantees no later writes. Treat [completion] as read-only and
 * stop work through [cancel]; directly completing/cancelling its future is not
 * an operation cancellation API.
 */
interface ArcSchematicOperation {
    val completion: CompletableFuture<ArcSchematicMutationResult>
    fun cancel(): Boolean
}

data class ArcSchematicMutationResult(val changed: Int, val skipped: Int, val cancelled: Boolean)
data class ArcSchematicDiagnostics(val entries: Int, val cachedBytes: Long, val pendingRequests: Int, val activeLibraries: Int, val closed: Boolean)

enum class ArcSchematicFailure {
    INVALID_ID, NOT_FOUND, UNSUPPORTED_FORMAT, UNSUPPORTED_CONTENT, LIMIT_EXCEEDED,
    BUSY, CLOSED, FILE_CHANGED, HASH_MISMATCH, WORLD_UNAVAILABLE, INVALID_SNAPSHOT, IO_ERROR, PROTECTED_AREA,
}

class ArcSchematicException(val reason: ArcSchematicFailure, message: String) : RuntimeException(message)
