package ru.arc.paper.schematics

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.data.type.Fence
import org.bukkit.block.data.type.GlassPane
import org.bukkit.block.data.type.Wall
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import ru.arc.paper.api.ArcSchematicMutationResult
import ru.arc.paper.api.ArcSchematicPasteOptions
import ru.arc.paper.api.ArcSchematicPasteTarget
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.protection.PaperBlockProtection
import java.util.AbstractList
import java.util.BitSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.logging.Level

/** Constructed on a worker. All world access belongs to [step] on the Paper thread. */
internal class NativeSchematicMutation private constructor(
    private val owner: String,
    private val worldId: UUID,
    private val cells: List<ArcSchematicBlock>,
    private val source: SchematicSnapshot?,
    private val options: ArcSchematicPasteOptions,
    private var acceptedBlock: Consumer<ArcSchematicBlock>?,
    private val placementReady: BooleanSupplier?,
    private val intervalTicks: Int,
    private val native: PaperSchematicBlocks,
) : SchematicMainThread.Work {
    val completion = CompletableFuture<ArcSchematicMutationResult>()
    private val lock = Any()
    private var cancelled = false
    private var changed = 0
    private var skipped = 0
    private var index = 0
    private var repairing = source == null
    private val pairStates = source?.palette?.map { SchematicBlockPairPolicy.describe(it.text) }
    private val placedCompanions = BitSet()
    // Encode only detached NBT on the preparation worker; native binding is charged to writes.
    private val blockEntities = source?.prepareBlockEntities().orEmpty()
    private val chunks: List<Pair<Int, Int>>
    private var chunkIndex = 0
    private val minimumY: Int
    private val maximumY: Int
    private var tick = 0L
    private var nextTick = 0L

    init {
        val needed = LinkedHashSet<Pair<Int, Int>>()
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (cell in cells) {
            forEachSchematicMutationChunk(cell.position, source == null || options.repairConnections) { x, z ->
                needed += x to z
            }
            minY = minOf(minY, cell.position.y)
            maxY = maxOf(maxY, cell.position.y)
        }
        chunks = needed.toList()
        minimumY = minY
        maximumY = maxY
    }

    override val done get() = completion.isDone

    override fun canRun(tick: Long): Boolean {
        this.tick = tick
        return placementReady == null || tick >= nextTick
    }

    fun cancel(): Boolean {
        val result = synchronized(lock) {
            if (completion.isDone || cancelled) return false
            cancelled = true
            ArcSchematicMutationResult(changed, skipped, true)
        }
        completion.complete(result)
        return true
    }

    override fun fail(problem: Throwable) {
        val wrapped = synchronized(lock) {
            if (completion.isDone) return
            cancelled = true
            ArcSchematicException(
                (problem as? ArcSchematicException)?.reason ?: ArcSchematicFailure.IO_ERROR,
                "Schematic mutation stopped after $changed changes and $skipped skips; no rollback was performed",
            ).also { it.initCause(problem) }
        }
        completion.completeExceptionally(wrapped)
    }

    override fun step(budget: Int): Int {
        var result: ArcSchematicMutationResult? = null
        val accepted = if (acceptedBlock == null) null else mutableListOf<ArcSchematicBlock>()
        val used = try { synchronized(lock) {
            if (completion.isDone || cancelled) return 0
            if (placementReady != null && tick < nextTick) return 0
            check(Bukkit.isPrimaryThread()) { "Schematic world mutations require the Paper primary thread" }
            val world = Bukkit.getWorld(worldId) ?: failure(ArcSchematicFailure.WORLD_UNAVAILABLE, "Schematic target world is unavailable")
            if (cells.isNotEmpty() && (minimumY < world.minHeight || maximumY >= world.maxHeight)) {
                failure(ArcSchematicFailure.LIMIT_EXCEEDED, "Schematic target exceeds the world's build height")
            }
            if (source == null || options.repairConnections) native.requireRepair()
            if (blockEntities.isNotEmpty()) native.requireBlockEntities()
            var consumed = 0
            // Validate all target chunks before the first write; never cause sync chunk generation.
            while (chunkIndex < chunks.size && consumed < budget) {
                val (x, z) = chunks[chunkIndex++]
                if (!world.isChunkLoaded(x, z)) failure(ArcSchematicFailure.WORLD_UNAVAILABLE, "Schematic target chunk must already be loaded")
                consumed++
            }
            if (chunkIndex < chunks.size) return@synchronized consumed
            var acceptedUnit = false
            while (index < cells.size && consumed < budget) {
                if (!repairing && placedCompanions[index]) {
                    // The companion was charged when written; charge this bookkeeping visit too so a full
                    // layer of already-written upper halves cannot become an unbounded main-thread scan.
                    index++
                    consumed++
                    continue
                }
                val cell = cells[index]
                val pos = cell.position
                val ignored = (options.ignoreAir && cell.isAir) || (options.ignoreBarriers && cell.isBarrier)
                if (placementReady != null && acceptedUnit && (repairing || !ignored)) break
                if (!world.isChunkLoaded(pos.x shr 4, pos.z shr 4)) failure(ArcSchematicFailure.WORLD_UNAVAILABLE, "Schematic target chunk unloaded during mutation")
                val before = changed
                if (placementReady != null && (repairing || !ignored)) {
                    if (!placementReady.asBoolean) {
                        nextTick = tick + 1
                        break
                    }
                    if (completion.isDone || cancelled) break
                }
                if (repairing) {
                    repair(world, cell)
                } else if (ignored) {
                    skipped++
                } else {
                    val snapshot = checkNotNull(source)
                    val companion = companionIndex(snapshot, index)
                    if (companion == null) {
                        requirePermission(pos)
                        write(world, snapshot, index, accepted)
                    } else {
                        val primary = if (checkNotNull(pairStates)[snapshot.paletteIndex(index)]!!.primary) index else companion
                        val secondary = if (primary == index) companion else index
                        for (part in intArrayOf(primary, secondary)) {
                            val position = cells[part].position
                            if (!world.isChunkLoaded(position.x shr 4, position.z shr 4))
                                failure(ArcSchematicFailure.WORLD_UNAVAILABLE, "Schematic target chunk unloaded during mutation")
                            requirePermission(position)
                        }
                        // One native pair is indivisible: at most one block may exceed the remaining tick budget.
                        write(world, snapshot, primary, accepted)
                        write(world, snapshot, secondary, accepted)
                        placedCompanions.set(companion)
                        consumed++
                    }
                }
                index++
                consumed++
                if (placementReady != null && changed != before) {
                    nextTick = tick + intervalTicks
                    acceptedUnit = true
                }
            }
            if (index == cells.size) {
                if (!repairing && options.repairConnections) {
                    repairing = true
                    index = 0
                } else {
                    result = ArcSchematicMutationResult(changed, skipped, false)
                }
            }
            consumed
        } } finally {
            // Emit after the indivisible native batch: a reentrant observer cancellation cannot
            // complete the operation while this same batch still has world writes to perform.
            accepted?.forEach(::observeAccepted)
        }
        result?.let(completion::complete)
        return used
    }

    private fun companionIndex(snapshot: SchematicSnapshot, index: Int): Int? {
        val states = checkNotNull(pairStates)
        val first = states[snapshot.paletteIndex(index)] ?: return null
        val position = snapshot.position(index)
        val x = position.x.toLong() + first.offset.x
        val y = position.y.toLong() + first.offset.y
        val z = position.z.toLong() + first.offset.z
        if (x !in snapshot.minimum.x.toLong()..snapshot.maximum.x.toLong() ||
            y !in snapshot.minimum.y.toLong()..snapshot.maximum.y.toLong() ||
            z !in snapshot.minimum.z.toLong()..snapshot.maximum.z.toLong()) return null
        val companion = snapshot.index(x.toInt(), y.toInt(), z.toInt())
        val second = states[snapshot.paletteIndex(companion)] ?: return null
        val cell = cells[companion]
        return companion.takeIf { SchematicBlockPairPolicy.matching(first, second) &&
            !(options.ignoreAir && cell.isAir) && !(options.ignoreBarriers && cell.isBarrier) }
    }

    private fun write(world: World, snapshot: SchematicSnapshot, index: Int, accepted: MutableList<ArcSchematicBlock>?) {
        if (native.write(world, cells[index].position, snapshot.entry(index).text, blockEntities[index])) {
            changed++
            accepted?.add(cells[index])
        } else skipped++
    }

    private fun observeAccepted(block: ArcSchematicBlock) {
        val observer = acceptedBlock ?: return
        try {
            observer.accept(block)
        } catch (problem: Throwable) {
            acceptedBlock = null
            Bukkit.getLogger().log(
                Level.WARNING,
                "Schematic accepted-write observer disabled for owner=$owner world=$worldId position=${block.position}; native mutation continues",
                problem,
            )
        }
    }

    private fun repair(world: World, cell: ArcSchematicBlock) {
        if ((options.ignoreAir && cell.isAir) || (options.ignoreBarriers && cell.isBarrier)) return
        val pos = cell.position
        val block = world.getBlockAt(pos.x, pos.y, pos.z)
        val current = block.blockData
        if (current !is Fence && current !is Wall && current !is GlassPane) return
        if (current.asString != cell.blockData) { skipped++; return }
        forEachSchematicMutationChunk(pos, true) { x, z ->
            if (!world.isChunkLoaded(x, z)) failure(ArcSchematicFailure.WORLD_UNAVAILABLE, "Schematic repair neighbor chunk must already be loaded")
        }
        requirePermission(pos)
        // Calculate only this cell, then write without recursive neighbour physics.
        if (native.repair(block)) changed++ else skipped++
    }

    private fun requirePermission(position: ArcSchematicPoint) {
        if (!PaperBlockProtection.permitsSchematicMutation(owner, worldId, position.x, position.y, position.z)) {
            failure(ArcSchematicFailure.PROTECTED_AREA, "Schematic target is protected")
        }
    }

    companion object {
        fun paste(
            owner: String,
            snapshot: SchematicSnapshot,
            target: ArcSchematicPasteTarget,
            options: ArcSchematicPasteOptions,
            acceptedBlock: Consumer<ArcSchematicBlock>? = null,
            ready: BooleanSupplier? = null,
            intervalTicks: Int = 0,
            native: PaperSchematicBlocks = PaperSchematicBlocks(),
        ): NativeSchematicMutation {
            require(ready == null && intervalTicks == 0 || ready != null && intervalTicks >= 1) {
                "Paced schematic paste requires readiness and an interval of at least one tick"
            }
            val cells = object : AbstractList<ArcSchematicBlock>() {
                override val size get() = snapshot.volume.toInt()
                override fun get(index: Int): ArcSchematicBlock {
                    val cell = snapshot.blocks()[index]
                    val relative = subtract(cell.position, snapshot.origin)
                    return cell.copy(position = ArcSchematicPoint(
                        Math.addExact(target.anchor.x, relative.x),
                        Math.addExact(target.anchor.y, relative.y),
                        Math.addExact(target.anchor.z, relative.z),
                    ))
                }
            }
            return NativeSchematicMutation(owner, target.worldId, cells, snapshot, options, acceptedBlock, ready, intervalTicks, native)
        }

        fun repair(owner: String, worldId: UUID, cells: List<ArcSchematicBlock>, native: PaperSchematicBlocks = PaperSchematicBlocks()): NativeSchematicMutation =
            NativeSchematicMutation(owner, worldId, cells, null, ArcSchematicPasteOptions(false, false, false), null, null, 0, native)
    }
}

internal inline fun forEachSchematicMutationChunk(position: ArcSchematicPoint, repairNeighbors: Boolean, action: (Int, Int) -> Unit) {
    val x = position.x shr 4
    val z = position.z shr 4
    action(x, z)
    if (!repairNeighbors) return
    // Native shape validation reads cardinal neighbors. Chunk coordinates avoid block-coordinate overflow.
    if (position.x and 15 == 0) action(x - 1, z)
    if (position.x and 15 == 15) action(x + 1, z)
    if (position.z and 15 == 0) action(x, z - 1)
    if (position.z and 15 == 15) action(x, z + 1)
}
