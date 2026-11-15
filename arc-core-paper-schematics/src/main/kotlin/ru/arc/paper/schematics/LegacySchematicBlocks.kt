package ru.arc.paper.schematics

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.NumberBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag
import org.bukkit.Bukkit
import org.bukkit.Note
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.NoteBlock

/** Pre-flattening block variants lived in tile NBT rather than the numeric block metadata. */
internal fun legacyStateNbt(id: Int, tag: CompoundBinaryTag?): CompoundBinaryTag? {
    if (tag == null) return null
    val keys = when (id) {
        25 -> listOf("note")
        26 -> listOf("color")
        140 -> listOf("Item", "Data")
        144 -> listOf("SkullType", "Rot")
        176, 177 -> listOf("Base")
        else -> return null
    }
    return CompoundBinaryTag.from(keys.mapNotNull { key -> tag[key]?.let { key to it } }.toMap())
}

internal fun legacyBlockEntityNbt(tag: CompoundBinaryTag): CompoundBinaryTag {
    val id = tag.getString("id")
    val normalized = when (id) {
        "Cauldron" -> "brewing_stand"
        "Control" -> "command_block"
        "DLDetector" -> "daylight_detector"
        "Trap" -> "dispenser"
        "EnchantTable" -> "enchanting_table"
        "EndGateway" -> "end_gateway"
        "AirPortal" -> "end_portal"
        "EnderChest" -> "ender_chest"
        "FlowerPot" -> "flower_pot"
        "RecordPlayer" -> "jukebox"
        "MobSpawner" -> "mob_spawner"
        "Music", "noteblock" -> "note_block"
        "Structure" -> "structure_block"
        "Chest", "Sign", "Banner", "Beacon", "Comparator", "Dropper", "Furnace", "Hopper", "Skull" -> id.lowercase()
        else -> id
    }
    val qualified = if (':' in normalized) normalized else "minecraft:$normalized"
    var result = tag.putString("id", qualified)
    if (qualified == "minecraft:sign") {
        // MCEdit predates JSON sign lines. Preserve existing component objects verbatim;
        // all other legacy text is literal, matching the former reader's compatibility rule.
        for (line in 1..4) {
            val key = "Text$line"
            val text = (tag[key] as? StringBinaryTag)?.value() ?: continue
            val component = text.startsWith('{') && try {
                JsonParser.parseString(text).isJsonObject
            } catch (_: JsonParseException) { false }
            if (!component) result = result.putString(key, JsonObject().apply { addProperty("text", text) }.toString())
        }
    }
    return result
}

internal fun applyLegacyStateNbt(data: BlockData, state: SchematicState): BlockData {
    val tag = state.legacyNbt ?: return data
    if (data is NoteBlock) {
        val note = tag.number("note", 0)
        if (note !in 0..24) malformed("Legacy note block pitch is outside its native range")
        data.note = Note(note)
        return data
    }
    val variant = legacyVariant(state.legacyId, state.legacyData, tag) ?: return data
    val replacement = Bukkit.createBlockData(variant)
    data.copyTo(replacement)
    if (state.legacyId == 144 && state.legacyData == 1) {
        return Bukkit.createBlockData("$variant[rotation=${tag.number("Rot", 0) and 15}]")
    }
    return replacement
}

/** Pure vanilla 1.12 field mapping; it neither binds registries nor retains native objects. */
internal fun legacyVariant(id: Int, data: Int, tag: CompoundBinaryTag): String? = when (id) {
    26 -> "minecraft:${WOOL_COLORS.getOrNull(tag.number("color", 14)) ?: "red"}_bed"
    176, 177 -> "minecraft:${WOOL_COLORS.getOrNull(15 - tag.number("Base", 0)) ?: "black"}_${if (id == 177) "wall_" else ""}banner"
    144 -> {
        val kind = listOf("skeleton", "wither_skeleton", "zombie", "player", "creeper", "dragon").getOrNull(tag.number("SkullType", 0)) ?: "skeleton"
        "minecraft:${kind}_${if (data != 1) "wall_" else ""}${if (kind.endsWith("skeleton")) "skull" else "head"}"
    }
    140 -> {
        val item = when (val value = tag["Item"]) {
            is StringBinaryTag -> value.value().removePrefix("minecraft:")
            is NumberBinaryTag -> when (value.intValue()) {
                6 -> "sapling"; 31 -> "tallgrass"; 32 -> "deadbush"; 37 -> "yellow_flower"; 38 -> "red_flower"
                39 -> "brown_mushroom"; 40 -> "red_mushroom"; 81 -> "cactus"; else -> ""
            }
            else -> ""
        }
        val variant = tag.number("Data", 0)
        val plant = when (item) {
            "sapling" -> listOf("oak", "spruce", "birch", "jungle", "acacia", "dark_oak").getOrNull(variant)?.plus("_sapling")
            "red_flower" -> listOf("poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy").getOrNull(variant)
            "yellow_flower" -> "dandelion"
            "tallgrass" -> if (variant == 2) "fern" else if (variant == 0) "dead_bush" else null
            "deadbush" -> "dead_bush"
            "red_mushroom", "brown_mushroom", "cactus" -> item
            else -> null
        }
        plant?.let { "minecraft:potted_$it" }
    }
    else -> null
}

private fun CompoundBinaryTag.number(key: String, default: Int): Int = (get(key) as? NumberBinaryTag)?.intValue() ?: default
private val WOOL_COLORS = listOf("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")
