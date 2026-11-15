package ru.arc.paper.schematics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import ru.arc.paper.api.ArcSchematicPoint

class NativeSchematicMutationAdapterTest : FreeSpec({
    "native NBT and repair are explicit about the verified Paper version" {
        requireNativeSchematicVersion("1.21.11")
        for (version in listOf("1.21.10", "26.1", "")) {
            shouldThrow<ArcSchematicException> { requireNativeSchematicVersion(version) }
                .reason shouldBe ArcSchematicFailure.UNSUPPORTED_FORMAT
        }
    }

    "repair requires only chunks containing cardinal neighbor reads at chunk boundaries" {
        fun chunks(x: Int, z: Int, repair: Boolean = true) = buildSet {
            forEachSchematicMutationChunk(ArcSchematicPoint(x, 100, z), repair) { cx, cz -> add(cx to cz) }
        }
        chunks(6, 6) shouldBe setOf(0 to 0)
        chunks(15, 15) shouldBe setOf(0 to 0, 1 to 0, 0 to 1)
        chunks(16, 16) shouldBe setOf(1 to 1, 0 to 1, 1 to 0)
        chunks(-1, -1) shouldBe setOf(-1 to -1, 0 to -1, -1 to 0)
        chunks(-16, -16) shouldBe setOf(-1 to -1, -2 to -1, -1 to -2)
        chunks(Int.MIN_VALUE, Int.MAX_VALUE) shouldBe setOf(-134217728 to 134217727, -134217729 to 134217727, -134217728 to 134217728)
        chunks(15, 15, repair = false) shouldBe setOf(0 to 0)
    }
})
