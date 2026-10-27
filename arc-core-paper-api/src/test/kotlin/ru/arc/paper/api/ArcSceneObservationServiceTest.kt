package ru.arc.paper.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class ArcSceneObservationServiceTest : FreeSpec({
    val world = UUID(0, 1)

    "bounds use explicit worlds and half-open finite coordinates" {
        val bounds = ArcSceneBounds(world, -1.0, 0.0, -1.0, 1.0, 2.0, 1.0)
        bounds.contains(world, -1.0, 0.0, -1.0) shouldBe true
        bounds.contains(world, 0.0, 1.0, 0.0) shouldBe true
        bounds.contains(world, 1.0, 1.0, 0.0) shouldBe false
        bounds.contains(world, 0.0, 2.0, 0.0) shouldBe false
        bounds.contains(world, 0.0, 1.0, 1.0) shouldBe false
        bounds.contains(UUID(0, 2), 0.0, 1.0, 0.0) shouldBe false
        shouldThrow<IllegalArgumentException> { ArcSceneBounds(world, Double.NaN, 0.0, 0.0, 1.0, 1.0, 1.0) }
        shouldThrow<IllegalArgumentException> { ArcSceneBounds(world, 1.0, 0.0, 0.0, 1.0, 1.0, 1.0) }
    }

    "appearance is detached and constructors do not cross Kotlin default argument markers" {
        val item = ArcSceneEquipment("HEAD", "minecraft:leather_helmet", 1, 25, 0x123456)
        val mutable = mutableListOf(item)
        val entity = ArcSceneEntity(world, "npc:one", "PLAYER", "Анна", 0.0, 64.0, 0.0,
            15f, 25f, 5f, "STANDING", 0.75, true, "dGV4dHVyZQ==", mutable)
        mutable.clear()
        entity.equipment.single() shouldBe item
        shouldThrow<UnsupportedOperationException> { (entity.equipment as MutableList).clear() }
        for (type in listOf(ArcSceneBounds::class.java, ArcSceneEntity::class.java,
            ArcSceneEquipment::class.java, ArcSceneLocation::class.java)) {
            type.constructors.size shouldBe 1
            type.constructors.none { it.parameterTypes.any { parameter -> parameter.name == "kotlin.jvm.internal.DefaultConstructorMarker" } } shouldBe true
        }
        shouldThrow<IllegalArgumentException> { ArcSceneEquipment("BODY", "minecraft:stone", 1, null, null) }
        shouldThrow<IllegalArgumentException> { ArcSceneEquipment("HEAD", "minecraft:stone", 100, null, null) }
        shouldThrow<IllegalArgumentException> { ArcSceneLocation("city", "City", world, Double.POSITIVE_INFINITY, 64.0, 0.0, 128.0) }
    }
})
