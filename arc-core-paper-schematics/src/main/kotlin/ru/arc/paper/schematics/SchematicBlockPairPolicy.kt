package ru.arc.paper.schematics

import ru.arc.paper.api.ArcSchematicPoint

internal data class SchematicBlockPairState(val key: String, val offset: ArcSchematicPoint, val primary: Boolean)

/** Immutable palette-only policy; never reads Bukkit or reaches beyond the supplied snapshot. */
internal object SchematicBlockPairPolicy {
    fun describe(blockData: String): SchematicBlockPairState? {
        val material = blockData.substringBefore('[')
        val properties = blockData.substringAfter('[', "").substringBefore(']').split(',')
            .mapNotNull { entry -> entry.indexOf('=').takeIf { it > 0 }?.let { entry.substring(0, it) to entry.substring(it + 1) } }.toMap()
        if (material.endsWith("_bed")) {
            val primary = when (properties["part"]) { "foot" -> true; "head" -> false; else -> return null }
            val direction = if (primary) 1 else -1
            val facing = properties["facing"]
            val offset = when (facing) {
                "north" -> ArcSchematicPoint(0, 0, -direction)
                "south" -> ArcSchematicPoint(0, 0, direction)
                "east" -> ArcSchematicPoint(direction, 0, 0)
                "west" -> ArcSchematicPoint(-direction, 0, 0)
                else -> return null
            }
            return SchematicBlockPairState("bed/$material/$facing", offset, primary)
        }
        if (!material.endsWith("_door") && material !in verticalPlants) return null
        val primary = when (properties["half"]) {
            "lower", "bottom" -> true
            "upper", "top" -> false
            else -> return null
        }
        return SchematicBlockPairState("vertical/$material", ArcSchematicPoint(0, if (primary) 1 else -1, 0), primary)
    }

    fun matching(first: SchematicBlockPairState, second: SchematicBlockPairState): Boolean =
        first.key == second.key && first.primary != second.primary &&
            first.offset.x == -second.offset.x && first.offset.y == -second.offset.y && first.offset.z == -second.offset.z

    private val verticalPlants = setOf("sunflower", "lilac", "rose_bush", "peony", "tall_grass", "large_fern", "pitcher_plant", "small_dripleaf")
        .mapTo(mutableSetOf()) { "minecraft:$it" }
}
