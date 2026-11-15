package ru.arc.paper.schematics

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
import ru.arc.paper.api.ArcSchematicFailure
import ru.arc.paper.api.ArcSchematicPoint
import java.io.ByteArrayInputStream
import java.io.DataInput
import java.io.DataInputStream
import java.lang.reflect.Method

/** The public Paper structure reader applies native DataVersion upgrades to source palette states. */
internal fun nativeSchematicState(bytes: ByteArray): BlockState {
    check(Bukkit.isPrimaryThread()) { "Schematic NBT binding requires the Paper primary thread" }
    val structure = ByteArrayInputStream(bytes).use { Bukkit.getStructureManager().loadStructure(it) }
    val palette = structure.palettes.singleOrNull() ?: malformed("Native schematic template has no single palette")
    return palette.blocks.singleOrNull() ?: malformed("Native schematic template has no single block")
}

/** Main-thread native seam. Its small palette is private to one bounded mutation. */
internal class PaperSchematicBlocks {
    private val palette = HashMap<String, BlockData>()
    private val defaults = HashMap<String, BlockState>()

    fun requireRepair() { NativeShapeRepair.binding() }
    fun requireBlockEntities() { NativeBlockEntityNbt.binding() }

    fun write(world: World, position: ArcSchematicPoint, text: String, nbt: PreparedSchematicBlockEntity?): Boolean {
        check(Bukkit.isPrimaryThread())
        if (nbt != null) requireBlockEntities()
        val data = palette.getOrPut(text) { Bukkit.createBlockData(text) }
        val block = world.getBlockAt(position.x, position.y, position.z)
        val state = defaults.getOrPut(text) { data.createBlockState() }
        if (nbt != null && state !is TileState) malformed("Schematic block entity cannot bind to its native block")
        if (state is TileState) {
            // Load only the owned copy: cached defaults must stay empty for subsequent cells.
            // Updating an empty TileState clears stale target contents even without retained NBT.
            val placed = state.copy(Location(world, position.x.toDouble(), position.y.toDouble(), position.z.toDouble()))
            if (nbt != null) NativeBlockEntityNbt.binding().load(placed, nbt)
            placed.blockData = data
            return placed.update(true, false)
        }
        if (block.blockData == data) return false
        // Paper uses CLIENTS | KNOWN_SHAPE | SKIP_ON_PLACE, still updates light/POIs,
        // and removes the previous block entity when changing its block type.
        block.setBlockData(data, false)
        return true
    }

    fun repair(block: Block): Boolean {
        val data = NativeShapeRepair.binding().updated(block)
        if (data == block.blockData) return false
        block.setBlockData(data, false)
        return true
    }
}

/**
 * Paper's public structure reader cannot combine an old NBT payload with a current palette:
 * pre-flattening conversion would interpret the current block name as legacy numeric data.
 * Upgrade only TILE_ENTITY data, then load it into an owned current native state. Both operations
 * are verified on Paper/Purpur 1.21.11; no native object leaves this main-thread boundary.
 */
internal object NativeBlockEntityNbt {
    private val resolved by lazy { runCatching {
        requireNativeSchematicVersion(Bukkit.getMinecraftVersion())
        Binding()
    } }

    fun binding(): Binding = resolved.getOrElse {
        failure(ArcSchematicFailure.UNSUPPORTED_FORMAT, "Native schematic block-entity binding is unavailable for this Paper runtime")
    }

    internal class Binding {
        private val compound = Class.forName("net.minecraft.nbt.CompoundTag")
        private val accounter = Class.forName("net.minecraft.nbt.NbtAccounter")
        private val createAccounter: Method = accounter.getMethod("create", Long::class.javaPrimitiveType)
        private val read: Method = Class.forName("net.minecraft.nbt.NbtIo").getMethod("read", DataInput::class.java, accounter)
        private val dataType = Class.forName("ca.spottedleaf.dataconverter.minecraft.datatypes.MCDataType")
        private val blockEntityType = Class.forName("ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry")
            .getField("TILE_ENTITY").get(null)
        private val convert: Method = Class.forName("ca.spottedleaf.dataconverter.minecraft.MCDataConverter")
            .getMethod("convertTag", dataType, compound, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val craftState = Class.forName("org.bukkit.craftbukkit.block.CraftBlockEntityState")
        private val loadData: Method = craftState.getMethod("loadData", compound)
        @Suppress("DEPRECATION")
        private val currentVersion = Bukkit.getUnsafe().dataVersion

        fun load(state: BlockState, payload: PreparedSchematicBlockEntity) {
            check(Bukkit.isPrimaryThread())
            if (!craftState.isInstance(state)) malformed("Schematic block entity requires a native Paper tile state")
            // These bytes were serialized from the bounded immutable Adventure tree, never supplied
            // by a caller. 64x allows native per-tag accounting (including empty compound/list nodes)
            // while retaining a finite size/depth bound for this second parser.
            val quota = createAccounter.invoke(null, payload.bytes.size.toLong() * 64L + 1024L)
            val tag = DataInputStream(ByteArrayInputStream(payload.bytes)).use { read.invoke(null, it, quota) }
            val upgraded = if (payload.dataVersion in 0 until currentVersion) {
                convert.invoke(null, blockEntityType, tag, payload.dataVersion, currentVersion)
            } else tag
            loadData.invoke(state, upgraded)
        }
    }
}

/**
 * Paper 1.21.11 has no public API for native shape calculation without recursive physics.
 * This isolated Mojang-mapped seam computes a state only; public setBlockData performs the write.
 * Bind once, fail before paste on another version/signature, never guess an alternative method.
 */
internal object NativeShapeRepair {
    private val resolved by lazy { runCatching {
        requireNativeSchematicVersion(Bukkit.getMinecraftVersion())
        Binding()
    } }

    fun binding(): Binding = resolved.getOrElse {
        failure(ArcSchematicFailure.UNSUPPORTED_FORMAT, "Native schematic connection repair is unavailable for this Paper runtime")
    }

    internal class Binding {
        private val craftBlock = Class.forName("org.bukkit.craftbukkit.block.CraftBlock")
        private val nativeState = Class.forName("net.minecraft.world.level.block.state.BlockState")
        private val nativeLevel = Class.forName("net.minecraft.world.level.LevelAccessor")
        private val nativePosition = Class.forName("net.minecraft.core.BlockPos")
        private val getState: Method = craftBlock.getMethod("getNMS")
        private val getLevel: Method = craftBlock.getMethod("getHandle")
        private val getPosition: Method = craftBlock.getMethod("getPosition")
        private val update: Method = Class.forName("net.minecraft.world.level.block.Block")
            .getMethod("updateFromNeighbourShapes", nativeState, nativeLevel, nativePosition)
        private val toData: Method = Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData")
            .getMethod("fromData", nativeState)

        fun updated(block: Block): BlockData {
            check(Bukkit.isPrimaryThread())
            return toData.invoke(null, update.invoke(null, getState.invoke(block), getLevel.invoke(block), getPosition.invoke(block))) as BlockData
        }
    }
}

internal fun requireNativeSchematicVersion(version: String) {
    if (version != "1.21.11") failure(ArcSchematicFailure.UNSUPPORTED_FORMAT,
        "Native schematic NBT and connection repair support Paper 1.21.11; this runtime requires compatibility verification")
}
