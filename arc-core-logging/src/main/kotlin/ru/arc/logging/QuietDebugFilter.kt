package ru.arc.logging

/** Filters DEBUG console noise by caller package prefix (see logging.yml `debug-quiet-sources`). */
object QuietDebugFilter {
    @JvmStatic
    fun matchesQuietSource(
        className: String,
        sources: Collection<String>,
    ): Boolean {
        if (sources.isEmpty()) return false
        return sources.any { prefix ->
            val p = prefix.trim()
            p.isNotEmpty() && (className == p || className.startsWith("$p."))
        }
    }

    @JvmStatic
    fun shouldSkipStackFrame(className: String): Boolean =
        className.startsWith("ru.arc.logging") ||
            className.startsWith("ru.arc.util.Logging") ||
            className.startsWith("java.lang.Thread") ||
            className.startsWith("jdk.internal") ||
            className.startsWith("java.util.concurrent") ||
            className.startsWith("kotlinx.coroutines") ||
            className.startsWith("kotlin.coroutines") ||
            className.startsWith("sun.reflect") ||
            className.startsWith("java.lang.reflect")

    @JvmStatic
    fun shouldSkipStructuredCallerFrame(className: String): Boolean =
        shouldSkipStackFrame(className) ||
            className.startsWith("java.lang.invoke") ||
            className.startsWith("kotlin.jvm") ||
            className.startsWith("kotlin.coroutines.jvm")
}
