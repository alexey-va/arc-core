package ru.arc.telemetry

/** Bounded operational snapshot. Process-local counters are named explicitly and do not claim historical completeness. */
data class PlayerTelemetryHealth(
    val started: Boolean,
    val accepting: Boolean,
    val sqlReady: Boolean,
    val coverageFrom: Long?,
    val coverageGapFrom: Long?,
    val queuedEvents: Int,
    val durableEvents: Long,
    val durableBytes: Long,
    val deliveredEventsSinceStart: Long,
    val retriesSinceStart: Long,
    val droppedEventsSinceStart: Long,
    val corruptRecords: Int,
    val oldestOutboxAt: Long?,
    val saturated: Boolean,
    val lastFailureCode: String?,
) {
    fun asMap(): Map<String, Any?> = linkedMapOf(
        "started" to started,
        "accepting" to accepting,
        "sqlReady" to sqlReady,
        "coverageFrom" to coverageFrom,
        "coverageGapFrom" to coverageGapFrom,
        "queuedEvents" to queuedEvents,
        "durableEvents" to durableEvents,
        "durableBytes" to durableBytes,
        "deliveredEventsSinceStart" to deliveredEventsSinceStart,
        "retriesSinceStart" to retriesSinceStart,
        "droppedEventsSinceStart" to droppedEventsSinceStart,
        "corruptRecords" to corruptRecords,
        "oldestOutboxAt" to oldestOutboxAt,
        "saturated" to saturated,
        "lastFailureCode" to lastFailureCode,
        "historyCompleteness" to "bounded observations; see coverageFrom and coverageGapFrom",
    )
}
