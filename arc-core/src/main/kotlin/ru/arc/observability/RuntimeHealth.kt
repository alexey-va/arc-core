package ru.arc.observability

import java.util.concurrent.atomic.AtomicBoolean

/** Bounded lifecycle state exposed to operators and agent tooling. */
enum class RuntimeHealthState(val wireName: String) {
    STARTING("starting"),
    UP("up"),
    DEGRADED("degraded"),
    DOWN("down"),
}

/**
 * One non-blocking, thread-safe health contribution.
 *
 * Probes are called from lifecycle, log, or ops HTTP threads. They must only
 * read already maintained counters/state; they must not call Bukkit/Velocity,
 * Redis, SQL, the filesystem, or another blocking API while being sampled.
 */
data class RuntimeHealthContribution(
    val state: RuntimeHealthState = RuntimeHealthState.UP,
    val recoveryBacklog: Int = 0,
    val activeLeases: Int = 0,
    val schemas: Map<String, Int> = emptyMap(),
    val dependencies: Map<String, Boolean> = emptyMap(),
) {
    init {
        require(recoveryBacklog >= 0) { "Recovery backlog must not be negative" }
        require(activeLeases >= 0) { "Active lease count must not be negative" }
        require(schemas.size <= MAX_NAMED_VALUES) { "Health contribution contains too many schemas" }
        require(dependencies.size <= MAX_NAMED_VALUES) { "Health contribution contains too many dependencies" }
        schemas.forEach { (name, version) ->
            requireHealthToken(name, "Schema name")
            require(version >= 0) { "Schema version must not be negative" }
        }
        dependencies.keys.forEach { requireHealthToken(it, "Dependency name") }
    }
}

data class RuntimeHealthProbeSnapshot(
    val id: String,
    val contribution: RuntimeHealthContribution,
) {
    init {
        requireHealthToken(id, "Health probe id")
    }
}

/** Immutable aggregate safe to expose through logs and authenticated ops HTTP. */
data class RuntimeHealthSnapshot(
    val component: String,
    val state: RuntimeHealthState,
    val generatedAtMillis: Long,
    val recoveryBacklog: Int,
    val activeLeases: Int,
    val schemas: Map<String, Int>,
    val dependencies: Map<String, Boolean>,
    val probes: List<RuntimeHealthProbeSnapshot>,
) {
    init {
        requireHealthToken(component, "Health component")
        require(generatedAtMillis >= 0L) { "Health snapshot time must not be negative" }
        require(recoveryBacklog >= 0) { "Recovery backlog must not be negative" }
        require(activeLeases >= 0) { "Active lease count must not be negative" }
        require(probes.size <= RuntimeHealthRegistry.MAX_PROBES) { "Health snapshot contains too many probes" }
    }

    val ready: Boolean get() = state == RuntimeHealthState.UP

    /** Stable value-safe representation for Gson/Jackson based ops surfaces. */
    fun asMap(): Map<String, Any?> = linkedMapOf(
        "component" to component,
        "state" to state.wireName,
        "ready" to ready,
        "generatedAtMillis" to generatedAtMillis,
        "recoveryBacklog" to recoveryBacklog,
        "activeLeases" to activeLeases,
        "schemas" to schemas,
        "dependencies" to dependencies,
        "probes" to probes.map { probe ->
            linkedMapOf(
                "id" to probe.id,
                "state" to probe.contribution.state.wireName,
                "recoveryBacklog" to probe.contribution.recoveryBacklog,
                "activeLeases" to probe.contribution.activeLeases,
                "schemas" to probe.contribution.schemas,
                "dependencies" to probe.contribution.dependencies,
            )
        },
    )
}

fun interface RuntimeHealthProvider {
    fun snapshot(): RuntimeHealthSnapshot
}

/**
 * Thread-safe owner of one component's bounded health probes.
 *
 * Registration is explicit and returns an idempotent handle. Probe failures
 * fail closed as a down probe without serializing the exception or payload.
 */
class RuntimeHealthRegistry(
    val component: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : RuntimeHealthProvider, AutoCloseable {
    private val monitor = Any()
    private val probes = linkedMapOf<String, () -> RuntimeHealthContribution>()
    private var lifecycleState = RuntimeHealthState.STARTING
    private var closed = false

    init {
        requireHealthToken(component, "Health component")
    }

    fun register(
        id: String,
        probe: () -> RuntimeHealthContribution,
    ): AutoCloseable {
        requireHealthToken(id, "Health probe id")
        synchronized(monitor) {
            check(!closed) { "Health registry is closed" }
            check(id !in probes) { "Health probe '$id' is already registered" }
            check(probes.size < MAX_PROBES) { "Health registry probe capacity is exhausted" }
            probes[id] = probe
        }
        val active = AtomicBoolean(true)
        return AutoCloseable {
            if (active.compareAndSet(true, false)) synchronized(monitor) { probes.remove(id) }
        }
    }

    fun markStarting() = setLifecycle(RuntimeHealthState.STARTING)

    fun markReady() = setLifecycle(RuntimeHealthState.UP)

    fun markDegraded() = setLifecycle(RuntimeHealthState.DEGRADED)

    fun markDown() = setLifecycle(RuntimeHealthState.DOWN)

    fun probeCount(): Int = synchronized(monitor) { probes.size }

    override fun snapshot(): RuntimeHealthSnapshot {
        val (lifecycle, currentProbes) = synchronized(monitor) {
            (if (closed) RuntimeHealthState.DOWN else lifecycleState) to probes.toMap()
        }
        val sampled = currentProbes.entries.sortedBy(Map.Entry<String, *>::key).map { (id, probe) ->
            val contribution = try {
                probe()
            } catch (_: Throwable) {
                RuntimeHealthContribution(
                    state = RuntimeHealthState.DOWN,
                    dependencies = mapOf("probe" to false),
                )
            }
            RuntimeHealthProbeSnapshot(id, contribution)
        }
        val state = aggregateState(lifecycle, sampled.map { it.contribution.state })
        val schemas = linkedMapOf<String, Int>()
        val dependencies = linkedMapOf<String, Boolean>()
        sampled.forEach { probe ->
            probe.contribution.schemas.toSortedMap().forEach { (name, version) ->
                schemas["${probe.id}.$name"] = version
            }
            probe.contribution.dependencies.toSortedMap().forEach { (name, ready) ->
                dependencies["${probe.id}.$name"] = ready
            }
        }
        return RuntimeHealthSnapshot(
            component = component,
            state = state,
            generatedAtMillis = clock().also { require(it >= 0L) { "Health clock must not be negative" } },
            recoveryBacklog = sampled.saturatedSum { it.contribution.recoveryBacklog },
            activeLeases = sampled.saturatedSum { it.contribution.activeLeases },
            schemas = schemas,
            dependencies = dependencies,
            probes = sampled,
        )
    }

    override fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
            lifecycleState = RuntimeHealthState.DOWN
            probes.clear()
        }
    }

    private fun setLifecycle(state: RuntimeHealthState) {
        synchronized(monitor) {
            check(!closed) { "Health registry is closed" }
            lifecycleState = state
        }
    }

    companion object {
        const val MAX_PROBES = 64

        private fun aggregateState(
            lifecycle: RuntimeHealthState,
            probes: List<RuntimeHealthState>,
        ): RuntimeHealthState {
            val states = probes + lifecycle
            return when {
                RuntimeHealthState.DOWN in states -> RuntimeHealthState.DOWN
                RuntimeHealthState.DEGRADED in states -> RuntimeHealthState.DEGRADED
                RuntimeHealthState.STARTING in states -> RuntimeHealthState.STARTING
                else -> RuntimeHealthState.UP
            }
        }
    }
}

/** Stable bounded single-line health readback for Loki and MCP log tools. */
class StructuredRuntimeHealthLine {
    private val formatter = StructuredDebugLine(
        prefix = "ARC_HEALTH",
        maxValueCharacters = 4_096,
        maxFields = 10,
    )

    fun line(snapshot: RuntimeHealthSnapshot): String = formatter.line(
        "component" to snapshot.component,
        "state" to snapshot.state.wireName,
        "ready" to snapshot.ready,
        "generated_at_ms" to snapshot.generatedAtMillis,
        "recovery_backlog" to snapshot.recoveryBacklog,
        "active_leases" to snapshot.activeLeases,
        "schemas" to snapshot.schemas.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "-" },
        "dependencies" to snapshot.dependencies.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "-" },
        "probes" to snapshot.probes.joinToString(",") { "${it.id}:${it.contribution.state.wireName}" }.ifEmpty { "-" },
    )
}

private const val MAX_NAMED_VALUES = 32
private val HEALTH_TOKEN = Regex("[a-z][a-z0-9._-]{0,63}")

private fun requireHealthToken(value: String, label: String) {
    require(value.matches(HEALTH_TOKEN)) { "$label must be a stable lowercase token" }
}

private inline fun <T> Iterable<T>.saturatedSum(value: (T) -> Int): Int {
    var total = 0L
    forEach { total = (total + value(it)).coerceAtMost(Int.MAX_VALUE.toLong()) }
    return total.toInt()
}
