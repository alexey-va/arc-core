package ru.arc.paper.api

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class ArcTelemetryProviderTest : FreeSpec({
    "recordActivity has a Java-shaped default method without Kotlin default arguments" {
        val api = ArcTelemetryProvider::class.java
        val method = api.getMethod(
            "recordActivity",
            UUID::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Map::class.java,
        )

        method.returnType shouldBe Boolean::class.javaPrimitiveType
        method.isDefault shouldBe true
        api.declaredMethods.none { it.name == "recordActivity\$default" } shouldBe true

        val provider = object : ArcTelemetryProvider {}
        provider.recordActivity(
            UUID(0L, 1L),
            "crates",
            "crate.opened",
            "daily",
            null,
            mapOf("result" to "opened", "duration_ms" to "125"),
        ) shouldBe false
    }
})
