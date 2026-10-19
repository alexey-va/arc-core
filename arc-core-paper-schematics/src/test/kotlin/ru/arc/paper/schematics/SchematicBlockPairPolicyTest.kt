package ru.arc.paper.schematics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.api.ArcSchematicPoint

class SchematicBlockPairPolicyTest : FunSpec({
    test("beds match their opposite part only with the same material and cardinal facing") {
        val directions = mapOf("north" to ArcSchematicPoint(0, 0, -1), "south" to ArcSchematicPoint(0, 0, 1),
            "east" to ArcSchematicPoint(1, 0, 0), "west" to ArcSchematicPoint(-1, 0, 0))
        for ((facing, offset) in directions) {
            val foot = SchematicBlockPairPolicy.describe("minecraft:red_bed[facing=$facing,occupied=false,part=foot]")!!
            val head = SchematicBlockPairPolicy.describe("minecraft:red_bed[facing=$facing,occupied=false,part=head]")!!
            foot.primary shouldBe true
            foot.offset shouldBe offset
            head.primary shouldBe false
            head.offset shouldBe ArcSchematicPoint(-offset.x, 0, -offset.z)
            SchematicBlockPairPolicy.matching(foot, head) shouldBe true
            SchematicBlockPairPolicy.matching(foot, foot) shouldBe false
            SchematicBlockPairPolicy.matching(foot, SchematicBlockPairPolicy.describe("minecraft:blue_bed[facing=$facing,part=head]")!!) shouldBe false
        }
        val north = SchematicBlockPairPolicy.describe("minecraft:red_bed[facing=north,part=foot]")!!
        val south = SchematicBlockPairPolicy.describe("minecraft:red_bed[facing=south,part=head]")!!
        SchematicBlockPairPolicy.matching(north, south) shouldBe false
    }

    test("doors and tall plants use lower first without treating stairs or trapdoors as pairs") {
        for (material in listOf("oak_door", "iron_door", "sunflower", "lilac", "rose_bush", "peony",
                "tall_grass", "large_fern", "pitcher_plant", "small_dripleaf")) {
            val lower = SchematicBlockPairPolicy.describe("minecraft:$material[half=lower]")!!
            val upper = SchematicBlockPairPolicy.describe("minecraft:$material[half=upper]")!!
            lower.primary shouldBe true
            lower.offset shouldBe ArcSchematicPoint(0, 1, 0)
            upper.offset shouldBe ArcSchematicPoint(0, -1, 0)
            SchematicBlockPairPolicy.matching(lower, upper) shouldBe true
            SchematicBlockPairPolicy.matching(lower, lower) shouldBe false
        }
        for (state in listOf("minecraft:oak_stairs[half=bottom]", "minecraft:oak_trapdoor[half=bottom]",
                "minecraft:stone", "minecraft:red_bed[facing=up,part=foot]", "minecraft:oak_door[half=sideways]")) {
            SchematicBlockPairPolicy.describe(state) shouldBe null
        }
    }
})
