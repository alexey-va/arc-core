package ru.arc.paper.api

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.function.BooleanSupplier
import java.util.function.Consumer

class ArcSchematicLibraryTest : FreeSpec({
    "paste preserves the original descriptor and adds an explicit Java Consumer overload" {
        val api = ArcSchematicLibrary::class.java
        val original = api.getMethod("paste", ArcSchematicSnapshot::class.java,
            ArcSchematicPasteTarget::class.java, ArcSchematicPasteOptions::class.java)
        val observed = api.getMethod("paste", ArcSchematicSnapshot::class.java,
            ArcSchematicPasteTarget::class.java, ArcSchematicPasteOptions::class.java, Consumer::class.java)
        val paced = api.getMethod("paste", ArcSchematicSnapshot::class.java,
            ArcSchematicPasteTarget::class.java, ArcSchematicPasteOptions::class.java,
            Consumer::class.java, BooleanSupplier::class.java, Int::class.javaPrimitiveType)

        original.returnType shouldBe ArcSchematicOperation::class.java
        observed.returnType shouldBe ArcSchematicOperation::class.java
        paced.returnType shouldBe ArcSchematicOperation::class.java
        observed.isDefault shouldBe true
        paced.isDefault shouldBe true
        api.declaredMethods.none { it.name == "paste\$default" } shouldBe true
    }
})
