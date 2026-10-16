package ru.arc.paper.schematics

import com.sk89q.worldedit.util.SideEffect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import ru.arc.paper.api.ArcSchematicPoint

class NativeSchematicMutationAdapterTest : FreeSpec({
    "WorldEdit native adapter capabilities permit bounded mutation" {
        requireNativeSchematicMutationAdapter(SideEffect.values().toSet())
    }

    "FAWE native adapter does not need to advertise its implemented validation effect" {
        // The shared flags in FAWE's native inventory; its extra flags are absent from upstream WE.
        requireNativeSchematicMutationAdapter(setOf(SideEffect.NEIGHBORS, SideEffect.LIGHTING))
    }

    "generic Bukkit fallback and missing capabilities fail before any mutation" {
        for (supported in listOf(emptySet(), setOf(SideEffect.NEIGHBORS))) {
            shouldThrow<ArcSchematicException> {
                requireNativeSchematicMutationAdapter(supported)
            }.reason shouldBe ArcSchematicFailure.UNSUPPORTED_FORMAT
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
