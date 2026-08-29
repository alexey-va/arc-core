package ru.arc.paper.testing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import net.bytebuddy.description.type.TypeDescription

class MockBukkitCompatibilityMatcherTest : FreeSpec({
    "the compatibility matcher selects one exact declared descriptor" {
        val matcher = MockBukkitCompatibility.exactMethodMatcher(
            OverloadedTarget::class.java,
            "operation",
            String::class.java,
            String::class.java,
        )

        TypeDescription.ForLoadedType(OverloadedTarget::class.java)
            .declaredMethods
            .filter(matcher::matches)
            .map { method -> method.actualName + method.parameters.joinToString(prefix = "(", postfix = ")") { it.type.asErasure().actualName } }
            .shouldContainExactly("operation(java.lang.String)")
    }
})

private class OverloadedTarget {
    fun operation(value: String): String = value

    fun operation(value: Int): String = value.toString()

    fun operation(value: String, suffix: String): String = value + suffix

    fun other(value: String): String = value
}
