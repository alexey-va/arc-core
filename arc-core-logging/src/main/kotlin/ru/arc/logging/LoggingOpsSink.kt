package ru.arc.logging

/** Optional hook for WARN/ERROR side channels (Paper: ops HTTP buffer). */
fun interface LoggingOpsSink {
    fun append(
        level: String,
        plainMessage: String,
    )
}
