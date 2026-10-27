package ru.arc.paper.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class ArcSceneEntityInfoServiceTest : FreeSpec({
    "public fields are detached immutable and have full argument Java constructors" {
        val field = ArcSceneInfoField("profession", "Профессия", "Фермер")
        val input = mutableListOf(field)
        val info = ArcSceneEntityInfo("packet-npc:one", "resident", "Анна", input)
        input.clear()
        info.fields.single() shouldBe field
        shouldThrow<UnsupportedOperationException> { (info.fields as MutableList).clear() }
        for (type in listOf(ArcSceneEntityInfo::class.java, ArcSceneInfoField::class.java)) {
            type.constructors.size shouldBe 1
            type.constructors.none { it.parameterTypes.any { parameter -> parameter.name == "kotlin.jvm.internal.DefaultConstructorMarker" } } shouldBe true
        }
    }

    "public information rejects oversized text too many fields and duplicate keys" {
        shouldThrow<IllegalArgumentException> { ArcSceneEntityInfo("x".repeat(129), "resident", "Анна", emptyList()) }
        shouldThrow<IllegalArgumentException> { ArcSceneEntityInfo("id", "x".repeat(65), "Анна", emptyList()) }
        shouldThrow<IllegalArgumentException> { ArcSceneEntityInfo("id", "resident", "x".repeat(129), emptyList()) }
        shouldThrow<IllegalArgumentException> { ArcSceneInfoField("x".repeat(49), "label", "value") }
        shouldThrow<IllegalArgumentException> { ArcSceneInfoField("key", "x".repeat(65), "value") }
        shouldThrow<IllegalArgumentException> { ArcSceneInfoField("key", "label", "x".repeat(257)) }
        val fields = (0..16).map { ArcSceneInfoField("field$it", "label", "value") }
        shouldThrow<IllegalArgumentException> { ArcSceneEntityInfo("id", "resident", "Анна", fields) }
        shouldThrow<IllegalArgumentException> { ArcSceneEntityInfo("id", "resident", "Анна", listOf(fields[0], fields[0])) }
        ArcSceneEntityInfo("x".repeat(128), "k".repeat(64), "t".repeat(128), fields.take(16)).fields.size shouldBe 16
    }
})
