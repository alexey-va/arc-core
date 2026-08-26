package ru.arc.runtime

import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.observability.RuntimeEvent
import ru.arc.observability.RuntimeEventOutcome
import ru.arc.observability.RuntimeEventType
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthProvider
import ru.arc.observability.RuntimeHealthRegistry
import ru.arc.observability.RuntimeHealthSnapshot
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

enum class PluginRuntimeState {
    CREATED,
    ACTIVE,
    CLOSED,
}

/**
 * Platform-neutral composition root for a plugin lifecycle.
 *
 * Lifecycle methods are called by the platform's primary lifecycle thread.
 * Owned resources close in reverse registration order after reloadable tasks.
 * Health probes must be non-blocking and thread-safe because authenticated ops
 * HTTP and log readback may sample them away from the platform thread.
 */
class PluginRuntime(
    component: String,
    scheduler: TaskScheduler,
    private val eventSink: (RuntimeEvent) -> Unit,
    private val healthSink: (RuntimeHealthSnapshot) -> Unit,
) : RuntimeHealthProvider, AutoCloseable {
    private val monitor = Any()
    private val resources = ArrayDeque<AutoCloseable>()
    private val ownedIdentities = Collections.newSetFromMap(IdentityHashMap<AutoCloseable, Boolean>())
    val tasks = LifecycleTaskScope(scheduler = scheduler, initiallyActive = false)
    val health = RuntimeHealthRegistry(component)

    @Volatile
    var state: PluginRuntimeState = PluginRuntimeState.CREATED
        private set

    fun start(vararg fields: Pair<String, Any?>): LifecycleTaskScope.Token = synchronized(monitor) {
        check(state == PluginRuntimeState.CREATED) { "Plugin runtime may only start once" }
        eventSink(RuntimeEvent(RuntimeEventType.PLUGIN_BOOTSTRAP, health.component, RuntimeEventOutcome.STARTED, fields.toList()))
        val token = tasks.activate()
        state = PluginRuntimeState.ACTIVE
        token
    }

    fun reload(): LifecycleTaskScope.Token = synchronized(monitor) {
        check(state == PluginRuntimeState.ACTIVE) { "Plugin runtime is not active" }
        tasks.restart()
    }

    fun ready(vararg fields: Pair<String, Any?>) {
        synchronized(monitor) { check(state == PluginRuntimeState.ACTIVE) { "Plugin runtime is not active" } }
        health.markReady()
        eventSink(RuntimeEvent(RuntimeEventType.PLUGIN_READY, health.component, RuntimeEventOutcome.OK, fields.toList()))
        emitHealth()
    }

    fun registerHealth(
        id: String,
        probe: () -> RuntimeHealthContribution,
    ): AutoCloseable = own(health.register(id, probe))

    fun emitHealth(): RuntimeHealthSnapshot = snapshot().also(healthSink)

    /**
     * Publishes current in-memory health on the runtime-owned async scheduler.
     * The timer is cancelled automatically on reload or shutdown.
     */
    fun reportHealthEvery(
        periodTicks: Long,
        initialDelayTicks: Long = periodTicks,
    ) {
        check(state == PluginRuntimeState.ACTIVE) { "Plugin runtime is not active" }
        require(periodTicks >= 1L) { "Health report period must be positive" }
        require(initialDelayTicks >= 0L) { "Health report delay must not be negative" }
        checkNotNull(tasks.runTimerAsync(initialDelayTicks, periodTicks, ::emitHealth)) {
            "Plugin runtime task scope is not active"
        }
    }

    override fun snapshot(): RuntimeHealthSnapshot = health.snapshot()

    fun <T : AutoCloseable> own(resource: T): T = synchronized(monitor) {
        check(state != PluginRuntimeState.CLOSED) { "Plugin runtime is closed" }
        check(ownedIdentities.add(resource)) { "Plugin runtime already owns this resource" }
        resources.addLast(resource)
        resource
    }

    fun ownedResourceCount(): Int = synchronized(monitor) { resources.size }

    override fun close() {
        val toClose = synchronized(monitor) {
            if (state == PluginRuntimeState.CLOSED) return
            state = PluginRuntimeState.CLOSED
            health.markDown()
            resources.reversed().toList().also {
                resources.clear()
                ownedIdentities.clear()
            }
        }
        var firstFailure: Throwable? = null
        try {
            tasks.close()
        } catch (failure: Throwable) {
            firstFailure = failure
        }
        toClose.forEach { resource ->
            try {
                resource.close()
            } catch (failure: Throwable) {
                val previous = firstFailure
                if (previous == null) firstFailure = failure else previous.addSuppressed(failure)
            }
        }
        try {
            health.close()
        } catch (failure: Throwable) {
            val previous = firstFailure
            if (previous == null) firstFailure = failure else previous.addSuppressed(failure)
        }
        firstFailure?.let { throw IllegalStateException("Could not close every plugin runtime resource", it) }
    }
}
