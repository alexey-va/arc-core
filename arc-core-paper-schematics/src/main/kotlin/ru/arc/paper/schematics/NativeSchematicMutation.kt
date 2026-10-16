package ru.arc.paper.schematics

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.extension.platform.Capability
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.util.SideEffect
import com.sk89q.worldedit.util.SideEffectSet
import com.sk89q.worldedit.util.concurrency.LazyReference
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
import java.util.AbstractList
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Constructed on a worker. All world access belongs to [step] on the Paper thread. */
internal class NativeSchematicMutation private constructor(
    private val worldId: UUID,
    private val cells: List<ArcSchematicBlock>,
    private val source: SchematicSnapshot?,
    private val options: ArcSchematicPasteOptions,
) : SchematicMainThread.Work {
    val completion = CompletableFuture<ArcSchematicMutationResult>()
    private val lock = Any()
    private var cancelled = false
    private var changed = 0
    private var skipped = 0
    private var index = 0
    private var repairing = source == null
    private val chunks: List<Pair<Int, Int>>
    private var chunkIndex = 0
    private val minimumY: Int
    private val maximumY: Int

    init {
        val needed = LinkedHashSet<Pair<Int, Int>>()
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (cell in cells) {
            needed += (cell.position.x shr 4) to (cell.position.z shr 4)
            minY = minOf(minY, cell.position.y)
            maxY = maxOf(maxY, cell.position.y)
        }
        chunks = needed.toList()
        minimumY = minY
        maximumY = maxY
    }

    override val done get() = completion.isDone

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
        val used = synchronized(lock) {
            if (completion.isDone || cancelled) return 0
            check(Bukkit.isPrimaryThread()) { "Schematic world mutations require the Paper primary thread" }
            val world = Bukkit.getWorld(worldId) ?: failure(ArcSchematicFailure.WORLD_UNAVAILABLE, "Schematic target world is unavailable")
            if (cells.isNotEmpty() && (minimumY < world.minHeight || maximumY >= world.maxHeight)) {
                failure(ArcSchematicFailure.LIMIT_EXCEEDED, "Schematic target exceeds the world's build height")
            }
            if (SideEffect.VALIDATION !in WorldEdit.getInstance().platformManager.queryCapability(Capability.WORLD_EDITING).supportedSideEffects) {
                failure(ArcSchematicFailure.UNSUPPORTED_FORMAT, "WorldEdit's native Paper adapter is unavailable")
            }
            var consumed = 0
            // Validate all target chunks before the first write; never cause sync chunk generation.
            while (chunkIndex < chunks.size && consumed < budget) {
                val (x, z) = chunks[chunkIndex++]
                if (!world.isChunkLoaded(x, z)) failure(ArcSchematicFailure.WORLD_UNAVAILABLE, "Schematic target chunk must already be loaded")
                consumed++
            }
            if (chunkIndex < chunks.size) return@synchronized consumed
            val nativeWorld = BukkitAdapter.adapt(world)
            while (index < cells.size && consumed < budget) {
                val cell = cells[index]
                val pos = cell.position
                if (!world.isChunkLoaded(pos.x shr 4, pos.z shr 4)) failure(ArcSchematicFailure.WORLD_UNAVAILABLE, "Schematic target chunk unloaded during mutation")
                if (repairing) {
                    repair(world, nativeWorld, cell)
                } else if ((options.ignoreAir && cell.isAir) || (options.ignoreBarriers && cell.isBarrier)) {
                    skipped++
                } else {
                    val snapshot = checkNotNull(source)
                    val state = snapshot.entry(index).native
                    val nbt = snapshot.nbt(index)
                    val full = if (nbt == null) state.toBaseBlock() else state.toBaseBlock(LazyReference.computed(nbt))
                    if (nativeWorld.setBlock(BlockVector3.at(pos.x, pos.y, pos.z), full, WRITE_EFFECTS)) changed++ else skipped++
                }
                index++
                consumed++
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
        }
        result?.let(completion::complete)
        return used
    }

    private fun repair(world: World, nativeWorld: com.sk89q.worldedit.world.World, cell: ArcSchematicBlock) {
        if ((options.ignoreAir && cell.isAir) || (options.ignoreBarriers && cell.isBarrier)) return
        val pos = cell.position
        val block = world.getBlockAt(pos.x, pos.y, pos.z)
        val current = block.blockData
        if (current !is Fence && current !is Wall && current !is GlassPane) return
        if (current.asString != cell.blockData) { skipped++; return }
        // VALIDATION calls native Block.updateFromNeighbourShapes at this position.
        // NEIGHBORS/UPDATE stay off: no recursive physics outside the supplied scope.
        if (nativeWorld.setBlock(BlockVector3.at(pos.x, pos.y, pos.z), BukkitAdapter.adapt(current), REPAIR_EFFECTS)) changed++ else skipped++
    }

    companion object {
        private val WRITE_EFFECTS = SideEffectSet.none()
            .with(SideEffect.NETWORK, SideEffect.State.ON)
            .with(SideEffect.LIGHTING, SideEffect.State.ON)
            .with(SideEffect.POI_UPDATE, SideEffect.State.ON)
        private val REPAIR_EFFECTS = WRITE_EFFECTS.with(SideEffect.VALIDATION, SideEffect.State.ON)

        fun paste(snapshot: SchematicSnapshot, target: ArcSchematicPasteTarget, options: ArcSchematicPasteOptions): NativeSchematicMutation {
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
            return NativeSchematicMutation(target.worldId, cells, snapshot, options)
        }

        fun repair(worldId: UUID, cells: List<ArcSchematicBlock>): NativeSchematicMutation =
            NativeSchematicMutation(worldId, cells, null, ArcSchematicPasteOptions(false, false, false))
    }
}
