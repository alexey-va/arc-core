package ru.arc.observability

/** Canonical cross-plugin events used for bounded operator and agent readback. */
enum class RuntimeEventType(val wireName: String) {
    PLUGIN_BOOTSTRAP("plugin-bootstrap"),
    PLUGIN_READY("plugin-ready"),
    RECOVERY_STARTED("recovery-started"),
    RECOVERY_COMPLETED("recovery-completed"),
    RECOVERY_FAILED("recovery-failed"),
    REDIS_MESSAGE_REJECTED("redis-message-rejected"),
    NETWORK_PEER_EXPIRED("network-peer-expired"),
    DURABLE_RECORD_REPLAYED("durable-record-replayed"),
}

enum class RuntimeEventOutcome(val wireName: String) {
    STARTED("started"),
    OK("ok"),
    DEGRADED("degraded"),
    REJECTED("rejected"),
    FAILED("failed"),
}

/**
 * One structured runtime fact. Values are formatted only through
 * [StructuredRuntimeEventLine], which bounds and escapes them.
 */
data class RuntimeEvent(
    val type: RuntimeEventType,
    val component: String,
    val outcome: RuntimeEventOutcome,
    val fields: List<Pair<String, Any?>> = emptyList(),
) {
    init {
        require(component.matches(COMPONENT)) { "Runtime event component must be a stable token" }
        require(fields.size <= MAX_FIELDS) { "Runtime event contains too many fields" }
    }

    private companion object {
        const val MAX_FIELDS = 24
        val COMPONENT = Regex("[a-z][a-z0-9_.-]{0,63}")
    }
}

/** Renders one stable `ARC_RUNTIME` line without serializing domain payloads. */
class StructuredRuntimeEventLine(
    prefix: String = "ARC_RUNTIME",
) {
    private val formatter = StructuredDebugLine(prefix = prefix, maxFields = 27)

    fun line(event: RuntimeEvent): String = formatter.line(
        buildList {
            add("event" to event.type.wireName)
            add("component" to event.component)
            add("outcome" to event.outcome.wireName)
            addAll(event.fields)
        },
    )
}
