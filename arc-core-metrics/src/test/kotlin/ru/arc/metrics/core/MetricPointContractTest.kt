package ru.arc.metrics.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.charset.StandardCharsets

class MetricPointContractTest : StringSpec({
    "ordinary untagged Kotlin calls use the shared JVM constructor without a Kotlin marker parameter" {
        val point = MetricPointConsumerFixture().create()
        point.tags shouldBe emptyMap()
        MetricPoint::class.java.getConstructor(String::class.java, String::class.java, Double::class.javaPrimitiveType)
            .newInstance("metric", "description", 1.0) shouldBe point

        // Inspect the compiled call site, not just reflective constructor availability:
        // a default primary constructor still compiles but links through a consumer's
        // distinct DefaultConstructorMarker when Kotlin is shaded in both plugins.
        val resource = "/${MetricPointConsumerFixture::class.java.name.replace('.', '/')}.class"
        val bytes = checkNotNull(MetricPointConsumerFixture::class.java.getResourceAsStream(resource)).use { it.readBytes() }
        String(bytes, StandardCharsets.ISO_8859_1) shouldNotContain "kotlin/jvm/internal/DefaultConstructorMarker"
    }
})

private class MetricPointConsumerFixture {
    fun create(): MetricPoint = MetricPoint("metric", "description", 1.0)
}
